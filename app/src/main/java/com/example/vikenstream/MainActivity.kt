package com.example.vikenstream

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.util.Log
import kotlinx.coroutines.delay
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vikenstream.databinding.ActivityMainBinding
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.Logging
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.AudioTrack
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var eglBase: EglBase
    private var room: Room? = null
    private var accessToken: String? = null
    private var localVideoTrack: VideoTrack? = null
    private val remoteVideoTracks = mutableMapOf<String, VideoTrack>()
    private var isLocalVideoInitialized = false // Track initialization state

    companion object {
        init {
            try {
                System.loadLibrary("webrtc")
                Log.d("MainActivity", "WebRTC native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("MainActivity", "Failed to load WebRTC native library: ${e.message}")
            }
        }
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize EglBase with shared context for all renderers
        eglBase = EglBase.create()
        val eglContext = eglBase.eglBaseContext

        try {
            Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE)
            Log.d("MainActivity", "WebRTC verbose logging enabled")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("MainActivity", "Failed to enable WebRTC logging: ${e.message}")
            Toast.makeText(this, "WebRTC logging unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Initialize all renderers with the same EGL context
        initRenderer(binding.remoteVideoView1, eglContext)
        initRenderer(binding.remoteVideoView2, eglContext)
        initRenderer(binding.remoteVideoView3, eglContext)
        initRenderer(binding.remoteVideoView4, eglContext)

        // Initialize TextureViewRenderer for local video with the same EGL context
        initTextureRenderer(binding.localVideoView, eglContext)

        setupUI()
        checkPermissions()
    }

    private fun initRenderer(renderer: SurfaceViewRenderer, eglContext: EglBase.Context?) {
        try {
            renderer.init(null, null)
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(false)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            Log.d("MainActivity", "SurfaceViewRenderer initialized: ${renderer.id}")
        } catch (e: Exception) {
            Log.e("MainActivity", "Renderer initialization failed: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Video renderer failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initTextureRenderer(renderer: SurfaceViewRenderer, eglContext: EglBase.Context?) {
        try {
            renderer.release() // Ensure clean state
            renderer.init(eglContext ?: EglBase.create().eglBaseContext, null)
            renderer.setMirror(true)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            renderer.setEnableHardwareScaler(true)
            renderer.setZOrderMediaOverlay(true)
            isLocalVideoInitialized = true
            Log.d("MainActivity", "SurfaceViewRenderer initialized: eglContext=${eglContext != null}")
        } catch (e: Exception) {
            Log.e("MainActivity", "SurfaceViewRenderer initialization failed: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Renderer initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            isLocalVideoInitialized = false
            // Retry initialization
            lifecycleScope.launch {
                delay(500)
                try {
                    renderer.release()
                    renderer.init(EglBase.create().eglBaseContext, null)
                    renderer.setMirror(true)
                    renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    renderer.setEnableHardwareScaler(true)
                    renderer.setZOrderMediaOverlay(true)
                    isLocalVideoInitialized = true
                    Log.d("MainActivity", "SurfaceViewRenderer retry initialized: isInitialized=${renderer}")
                } catch (e2: Exception) {
                    Log.e("MainActivity", "SurfaceViewRenderer retry failed: ${e2.message}", e2)
                }
            }
        }
    }


    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allPermissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (!allPermissionsGranted) {
                Toast.makeText(
                    this,
                    "Camera and microphone permissions are required for video calling",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupUI() {
        binding.loginButton.setOnClickListener {
            val username = binding.usernameEditText.text.toString().trim()
            val password = binding.passwordEditText.text.toString().trim()

            if (validateInput(username, password)) {
                connectToBackend(username, password)
            }
        }

        binding.roomList.layoutManager = LinearLayoutManager(this)
    }

    private fun validateInput(username: String, password: String): Boolean {
        return when {
            username.isEmpty() -> {
                binding.usernameEditText.error = "Username is required"
                false
            }
            password.isEmpty() -> {
                binding.passwordEditText.error = "Password is required"
                false
            }
            username.length < 3 -> {
                binding.usernameEditText.error = "Username must be at least 3 characters"
                false
            }
            password.length < 6 -> {
                binding.passwordEditText.error = "Password must be at least 6 characters"
                false
            }
            else -> true
        }
    }

    private fun connectToBackend(username: String, password: String) {
        binding.loginButton.isEnabled = false
        binding.loginButton.text = "Connecting..."

        lifecycleScope.launch {
            try {
                // Authenticate user → get access token
                accessToken = fetchTokenFromServer(username, password)

                // Fetch list of rooms from backend
                val rooms = fetchRooms(accessToken!!)

                runOnUiThread { showRoomsList(rooms) }

            } catch (e: Exception) {
                runOnUiThread {
                    binding.loginButton.isEnabled = true
                    binding.loginButton.text = "Login"
                    Toast.makeText(
                        this@MainActivity,
                        "Login failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun fetchTokenFromServer(username: String, password: String): String {
        return suspendCancellableCoroutine { cont ->
            val client = OkHttpClient()

            val json = JSONObject()
                .put("username", username)
                .put("password", password)

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://viken.stream:8443/login")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            if (cont.isActive) cont.resumeWithException(
                                IOException("Unexpected code $response")
                            )
                            return
                        }

                        val body = response.body?.string()
                        val jsonResp = JSONObject(body ?: "{}")
                        if (jsonResp.optBoolean("success")) {
                            val token = jsonResp.getString("access_token")
                            if (cont.isActive) cont.resume(token)
                        } else {
                            if (cont.isActive) cont.resumeWithException(
                                Exception("Invalid credentials")
                            )
                        }
                    }
                }
            })
        }
    }

    private suspend fun fetchRooms(token: String): List<String> {
        return suspendCancellableCoroutine { cont ->
            val client = OkHttpClient()

            val request = Request.Builder()
                .url("https://viken.stream:8443/rooms")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            if (cont.isActive) cont.resumeWithException(
                                IOException("Unexpected code $response")
                            )
                            return
                        }

                        val body = response.body?.string()
                        val jsonObj = JSONObject(body ?: "{}")
                        val jsonArr = jsonObj.getJSONArray("rooms")
                        val rooms = mutableListOf<String>()
                        for (i in 0 until jsonArr.length()) {
                            val room = jsonArr.getJSONObject(i)
                            rooms.add(room.getString("name"))
                        }
                        if (cont.isActive) cont.resume(rooms)
                    }
                }
            })
        }
    }

    private fun showRoomsList(rooms: List<String>) {
        binding.loginContainer.visibility = View.GONE
        binding.roomListContainer.visibility = View.VISIBLE

        val adapter = RoomsAdapter(rooms) { roomName ->
            joinRoom(roomName)
        }
        binding.roomList.adapter = adapter
    }

    private fun joinRoom(roomName: String) {
        lifecycleScope.launch {
            try {
                // Disconnect previous room if exists
                room?.disconnect()
                room = null

                // Request LiveKit room token from backend
                val liveKitToken = fetchLiveKitToken(roomName, accessToken!!)

                // Create new Room instance
                room = LiveKit.create(this@MainActivity)

                // Connect to the LiveKit room using the token
                room?.connect(
                    url = "wss://viken.stream:8443",
                    token = liveKitToken
                )

                // Setup room event handlers
                setupRoomEvents(room!!)

                // Show video call UI
                showVideoCallScreen()

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to join room: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Fetch LiveKit JWT token for this user and room from your backend
     */
    private suspend fun fetchLiveKitToken(roomName: String, accessToken: String): String {
        return suspendCancellableCoroutine { cont ->
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://viken.stream:8443/token?room=$roomName")
                .get()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            if (cont.isActive) cont.resumeWithException(
                                IOException("Unexpected code $response")
                            )
                            return
                        }

                        val body = response.body?.string()
                        val json = JSONObject(body ?: "{}")
                        val token = json.getString("token") // backend must return { "token": "<jwt>" }

                        if (cont.isActive) cont.resume(token)
                    }
                }
            })
        }
    }

    private fun setupRoomEvents(room: Room) {
        lifecycleScope.launch {
            room.events.collect { event ->
                Log.d("MainActivity", "Processing event: $event")
                when (event) {
                    is RoomEvent.Connected -> {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "✅ Connected to room!", Toast.LENGTH_SHORT).show()
                        }
                        Log.d("MainActivity", "Connected to room: $room")
                        enableLocalVideo(room)
                    }
                    is RoomEvent.Disconnected -> {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "❌ Disconnected: ${event.error}", Toast.LENGTH_LONG).show()
                            cleanupVideoTracks()
                            showLoginScreen()
                        }
                    }
                    is RoomEvent.TrackSubscribed -> {
                        Log.d("MainActivity", "📢 TrackSubscribed event: $event")
                        val track = event.track
                        val participant = event.participant
                        if (track is VideoTrack) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "🎥 Video track received from ${participant.identity}", Toast.LENGTH_SHORT).show()
                                participant.identity?.let { identity ->
                                    attachRemoteVideoTrack(track, identity.value)
                                }
                            }
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        val track = event.track
                        val participant = event.participant
                        if (track is VideoTrack) {
                            runOnUiThread {
                                participant.identity?.let { identity ->
                                    removeRemoteVideoTrack(identity.value)
                                }
                            }
                        }
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        val participant = event.participant
                        runOnUiThread {
                            participant.identity?.let { identity ->
                                removeRemoteVideoTrack(identity.value)
                            }
                        }
                    }

                    is RoomEvent.TrackPublished -> {
                        Log.d("MainActivity", "📢 Event: Enter TrackPublished")
                        val publication = event.publication
                        val participant = event.participant.identity
                        Log.d("MainActivity", "📢 Event: ${participant}, ${room.localParticipant.identity}, ${publication.track}")
                        if (publication.track is VideoTrack && participant == room.localParticipant.identity) {
                            val videoTrack = publication.track as VideoTrack
                            localVideoTrack = videoTrack
                            runOnUiThread {
                                Log.d("MainActivity", "TrackPublished event: Local video track $videoTrack")
                                attachLocalVideoTrack(videoTrack)
                            }
                        } else {
                            Log.d("MainActivity", "TrackPublished skipped: track=${publication.track}, isVideo=${publication.track is VideoTrack}, isLocal=${participant == room.localParticipant.identity}")
                            if (participant == room.localParticipant.identity && publication.track is AudioTrack) {
                                val videoPublication = room.localParticipant.trackPublications.values
                                    .firstOrNull { it.track is VideoTrack && it.kind == io.livekit.android.room.track.Track.Kind.VIDEO }
                                if (videoPublication != null) {
                                    val videoTrack = videoPublication.track as VideoTrack
                                    localVideoTrack = videoTrack
                                    runOnUiThread {
                                        Log.d("MainActivity", "Fallback: Found existing video track $videoTrack after audio track event")
                                        attachLocalVideoTrack(videoTrack)
                                    }
                                }
                            }
                        }
                    }
                    else -> Log.d("MainActivity", "📢 Unhandled event: $event")
                }
            }
        }
    }

    private fun enableLocalVideo(room: Room) {
        lifecycleScope.launch {
            try {
                if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Camera permission is required to enable video", Toast.LENGTH_LONG).show()
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(android.Manifest.permission.CAMERA),
                            PERMISSION_REQUEST_CODE
                        )
                    }
                    return@launch
                }

                val cameraEnumerator = if (livekit.org.webrtc.Camera2Enumerator.isSupported(this@MainActivity)) {
                    Log.d("MainActivity", "Using Camera2Enumerator")
                    livekit.org.webrtc.Camera2Enumerator(this@MainActivity)
                } else {
                    Log.d("MainActivity", "Falling back to Camera1Enumerator")
                    livekit.org.webrtc.Camera1Enumerator(false)
                }
                val cameraDevices = cameraEnumerator.deviceNames
                Log.d("MainActivity", "Available cameras: ${cameraDevices.joinToString()}")
                if (cameraDevices.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "No camera devices available", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val frontCamera = cameraDevices.find { cameraEnumerator.isFrontFacing(it) } ?: cameraDevices[0]
                Log.d("MainActivity", "Selected camera: $frontCamera")

                Log.d("MainActivity", "Enabling camera...")
                room.localParticipant.setCameraEnabled(true)
                room.localParticipant.setMicrophoneEnabled(true)
                Log.d("MainActivity", "Camera and microphone enabled, waiting for TrackPublished event")
                repeat(5) { attempt ->
                    delay(500) // Wait 1 second per attempt
                    val videoPublication = room.localParticipant.trackPublications.values
                        .firstOrNull { it.track is VideoTrack && it.kind == io.livekit.android.room.track.Track.Kind.VIDEO }
                    if (videoPublication != null) {
                        val videoTrack = videoPublication.track as VideoTrack
                        Log.d("MainActivity", "Video track found after ${attempt + 1} attempts")
                        localVideoTrack = videoTrack
                        runOnUiThread {
                            attachLocalVideoTrack(videoTrack)
                        }
                        return@repeat
                    }
                    Log.w("MainActivity", "Video track not found on attempt ${attempt + 1}")
                    if (attempt < 4) {
                        Log.d("MainActivity", "Retrying camera enable...")
                        room.localParticipant.setCameraEnabled(false)
                        delay(200)
                        room.localParticipant.setCameraEnabled(true)
                    }
                }

                if (room.localParticipant.trackPublications.values.none { it.track is VideoTrack }) {
                    Log.e("MainActivity", "Failed to publish video track after retries")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to publish video track", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to enable camera: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to enable camera: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun attachLocalVideoTrack(track: VideoTrack) {
        runOnUiThread {
            try {
                binding.localVideoView.let { videoView ->
                    localVideoTrack?.removeRenderer(videoView)
                    localVideoTrack = track
                    track.addRenderer(videoView)
                    videoView.setMirror(true)
                    videoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    videoView.visibility = View.VISIBLE
                    videoView.requestLayout()
                    Log.d("MainActivity", "Local video track attached to SurfaceViewRenderer: $track")
                    Toast.makeText(this@MainActivity, "✅ Local video enabled", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        repeat(3) { attempt ->
                            delay(1000)
                            if (videoView.visibility == View.VISIBLE && localVideoTrack == track) {
                                Log.w(
                                    "MainActivity",
                                    "Retrying renderer initialization and track attachment (attempt ${attempt + 1})"
                                )
                                initTextureRenderer(videoView, eglBase.eglBaseContext)
                                track.addRenderer(videoView)
                                videoView.requestLayout()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to attach local video track: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Failed to attach video: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ✅ Remove the separate attachVideoTrackToView method since TextureView doesn't need it

    private val remoteRenderers: List<SurfaceViewRenderer>
        get() = listOf(
            binding.remoteVideoView1,
            binding.remoteVideoView2,
            binding.remoteVideoView3,
            binding.remoteVideoView4
        )

    private fun attachRemoteVideoTrack(track: VideoTrack, participantId: String) {
        // Store the track
        remoteVideoTracks[participantId] = track

        // Find an available remote video view
        val videoView = getAvailableRemoteVideoView(participantId)
        videoView?.let {
            // ✅ Don't mirror remote videos
            videoView.setMirror(false)
            track.addRenderer(videoView)
            videoView.visibility = View.VISIBLE
            videoView.tag = participantId
            Log.d("MainActivity", "Remote video track attached for participant: $participantId")
        }

        // Update participants list
        updateParticipantsList()
    }

    private fun removeRemoteVideoTrack(participantId: String) {
        remoteVideoTracks[participantId]?.let { track ->
            // Find the video view for this participant
            val videoView = findVideoViewForParticipant(participantId)
            videoView?.let {
                track.removeRenderer(videoView)
                videoView.visibility = View.GONE
                videoView.tag = null
            }
        }
        remoteVideoTracks.remove(participantId)
        updateParticipantsList()
    }

    private fun getAvailableRemoteVideoView(participantId: String): SurfaceViewRenderer? {
        // Try to find an unused remote video view
        val remoteViews = listOf(
            binding.remoteVideoView1,
            binding.remoteVideoView2,
            binding.remoteVideoView3,
            binding.remoteVideoView4
        )

        return remoteViews.find { it.tag == null || it.visibility == View.GONE }
    }

    private fun findVideoViewForParticipant(participantId: String): SurfaceViewRenderer? {
        val remoteViews = listOf(
            binding.remoteVideoView1,
            binding.remoteVideoView2,
            binding.remoteVideoView3,
            binding.remoteVideoView4
        )

        return remoteViews.find { it.tag == participantId }
    }

    private fun updateParticipantsList() {
        // Update UI to show current participants count
        val participantCount = remoteVideoTracks.size + 1 // +1 for local participant
        binding.participantCountText.text = "Participants: $participantCount"
    }

    private fun cleanupVideoTracks() {
        // Remove all video track renderers
        localVideoTrack?.let { track ->
            track.removeRenderer(binding.localVideoView)
            binding.localVideoView.visibility = View.GONE
        }

        remoteVideoTracks.forEach { (participantId, track) ->
            findVideoViewForParticipant(participantId)?.let { surfaceView ->
                track.removeRenderer(surfaceView)
                surfaceView.visibility = View.GONE
                surfaceView.tag = null
            }
        }

        localVideoTrack = null
        remoteVideoTracks.clear()
    }

    private fun toggleCamera() {
        room?.let { room ->
            lifecycleScope.launch {
                try {
                    val isEnabled = room.localParticipant.isCameraEnabled
                    room.localParticipant.setCameraEnabled(!isEnabled)
                    runOnUiThread {
                        binding.cameraToggleButton.text = if (!isEnabled) "📹" else "📹❌"
                        if (!isEnabled) {
                            binding.localVideoView.visibility = View.VISIBLE
                            localVideoTrack?.let { track ->
                                attachLocalVideoTrack(track)
                            }
                        } else {
                            binding.localVideoView.visibility = View.GONE
                            localVideoTrack?.removeRenderer(binding.localVideoView)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Camera toggle failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ✅ Add debugging method
    private fun debugVideoState() {
        room?.let { room ->
            Log.d("MainActivity", "=== DEBUG VIDEO STATE ===")
            Log.d("MainActivity", "Camera enabled: ${room.localParticipant.isCameraEnabled}")
            Log.d("MainActivity", "Local video track: $localVideoTrack")
            Log.d("MainActivity", "Track publications: ${room.localParticipant.trackPublications.size}")
            room.localParticipant.trackPublications.values.forEachIndexed { index, pub ->
                Log.d("MainActivity", "Publication $index: ${pub.kind}, track: ${pub.track}")
            }
            Log.d("MainActivity", "Local video view visibility: ${binding.localVideoView.visibility}")
            Log.d("MainActivity", "Local video view dimensions: ${binding.localVideoView.width}x${binding.localVideoView.height}")
            Log.d("MainActivity", "Camera devices: ${livekit.org.webrtc.Camera2Enumerator(this@MainActivity).deviceNames}")
            Log.d("MainActivity", "=== END DEBUG ===")
        }
    }

    private fun toggleMicrophone() {
        room?.let { room ->
            lifecycleScope.launch {
                try {
                    val isEnabled = room.localParticipant.isMicrophoneEnabled
                    room.localParticipant.setMicrophoneEnabled(!isEnabled)

                    runOnUiThread {
                        binding.micToggleButton.text = if (!isEnabled) "🎤" else "🎤❌"
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Microphone toggle failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun leaveRoom() {
        room?.disconnect()
        cleanupVideoTracks()

        // Go back to rooms list
        lifecycleScope.launch {
            try {
                val rooms = fetchRooms(accessToken!!)
                runOnUiThread { showRoomsList(rooms) }
            } catch (e: Exception) {
                runOnUiThread {
                    showLoginScreen()
                }
            }
        }
    }

    private fun showLoginScreen() {
        binding.loginContainer.visibility = View.VISIBLE
        binding.roomListContainer.visibility = View.GONE
        binding.videoCallContainer.visibility = View.GONE
        binding.loginButton.isEnabled = true
        binding.loginButton.text = "Login"
    }

    private fun showVideoCallScreen() {
        binding.loginContainer.visibility = View.GONE
        binding.roomListContainer.visibility = View.GONE
        binding.videoCallContainer.visibility = View.VISIBLE

        binding.localVideoView.post {
            Log.d("MainActivity", "Local video view dimensions: ${binding.localVideoView.width}x${binding.localVideoView.height}")
        }
        // Setup control buttons
        binding.cameraToggleButton.setOnClickListener { toggleCamera() }
        binding.micToggleButton.setOnClickListener { toggleMicrophone() }
        binding.leaveRoomButton.setOnClickListener { leaveRoom() }

        // ✅ Add debug button (temporary for testing)
        binding.participantCountText.setOnClickListener { debugVideoState() }

        // Initialize button states
        binding.cameraToggleButton.text = "📹"
        binding.micToggleButton.text = "🎤"
        binding.participantCountText.text = "Participants: 1"
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupVideoTracks()
        room?.disconnect()
        room = null
        binding.localVideoView.release()
        remoteRenderers.forEach { it.release() }
        eglBase.release()
        Log.d("MainActivity", "Cleaned up resources in onDestroy")
    }
}