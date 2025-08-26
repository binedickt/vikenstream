package com.example.vikenstream

import android.content.pm.PackageManager
import android.os.Bundle
//import android.view.SurfaceView
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
//import io.livekit.android.room.track.Track
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.Logging
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
//import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
//import org.json.JSONArray
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
//        eglBase = EglBase.create()

        try {
            Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE)
            Log.d("MainActivity", "WebRTC verbose logging enabled")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("MainActivity", "Failed to enable WebRTC logging: ${e.message}")
            Toast.makeText(this, "WebRTC logging unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // ✅ Initialize LiveKit SurfaceViewRenderer
        initRenderer(binding.remoteVideoView1)
        initRenderer(binding.remoteVideoView2)
        initRenderer(binding.remoteVideoView3)
        initRenderer(binding.remoteVideoView4)

        initRenderer(binding.localVideoView)

        setupUI()
        checkPermissions()
    }

    private fun initRenderer(renderer: Any) {
        try {
            when (renderer) {
                is SurfaceViewRenderer -> {
                    renderer.init(null, null)
                    renderer.setEnableHardwareScaler(true)
                    renderer.setMirror(true)
                    renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    Log.d("MainActivity", "SurfaceViewRenderer initialized: ${renderer.id}")
                }
                is TextureViewRenderer -> {
                    renderer.init(null, null)
                    renderer.setMirror(true)
                    Log.d("MainActivity", "TextureViewRenderer initialized")
                }
                else -> {
                    Log.w("MainActivity", "Unsupported renderer type: ${renderer::class.java}")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Renderer initialization failed: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Video renderer failed: ${e.message}", Toast.LENGTH_LONG).show()
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
                when (event) {
                    is RoomEvent.Connected -> {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "✅ Connected to room!", Toast.LENGTH_SHORT).show()
                            // Enable local camera after connecting
                            enableLocalVideo(room)
                        }
                    }
                    is RoomEvent.Disconnected -> {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "❌ Disconnected: ${event.error}", Toast.LENGTH_LONG).show()
                            cleanupVideoTracks()
                            showLoginScreen()
                        }
                    }
                    is RoomEvent.TrackSubscribed -> {
                        val track = event.track
                        val participant = event.participant
                        if (track is VideoTrack) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "🎥 Video track received from ${participant.identity}", Toast.LENGTH_SHORT).show()
                                // Attach remote video track to UI
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
                    else -> println("📢 Event: $event")
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
                    }
                    return@launch
                }

                // Enable camera and create local video track
                room.localParticipant.setCameraEnabled(true)

                delay(1500)

                // Get the local video track
                val videoTrack = room.localParticipant
                    .trackPublications
                    .values
                    .firstOrNull { it.track is VideoTrack }
                    ?.track as? VideoTrack

//                localVideoTrack = room.localParticipant.videoTracks.firstOrNull()?.track as? VideoTrack

                if (videoTrack == null) {
                    Log.e("MainActivity", "No local video track found")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "No local video track available", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                localVideoTrack = videoTrack

                runOnUiThread {
                    // Attach local video to the local video view
                    attachLocalVideoTrack(videoTrack)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to enable camera: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun attachLocalVideoTrack(track: VideoTrack) {
        runOnUiThread {
            binding.localVideoView.let { videoView ->
                try {
                    track.addRenderer(videoView)
                    videoView.visibility = View.VISIBLE
                    Log.d("MainActivity", "Local video track attached to renderer")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to attach local video track: ${e.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to attach video: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

            }
        }
    }

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

        // Find an available remote video view or create one
        val videoView = getAvailableRemoteVideoView( participantId)
        videoView?.let {
            track.addRenderer(videoView)   // ✅ VideoView implements VideoSink
            videoView.visibility = View.VISIBLE
            videoView.tag = participantId  // Store participant ID for reference
        }

        // Update participants list
        updateParticipantsList()
    }


    private fun removeRemoteVideoTrack(participantId: String) {
        remoteVideoTracks[participantId]?.let { track ->
            // Find the video view for this participant
            val videoView = findVideoViewForParticipant(participantId)
            videoView?.let {
                track.removeRenderer(videoView) // ✅ Safe removal
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

    // Add video control methods
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
                        } else {
                            binding.localVideoView.visibility = View.GONE
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

        // Setup control buttons
        binding.cameraToggleButton.setOnClickListener { toggleCamera() }
        binding.micToggleButton.setOnClickListener { toggleMicrophone() }
        binding.leaveRoomButton.setOnClickListener { leaveRoom() }

        // Initialize button states
        binding.cameraToggleButton.text = "📹"
        binding.micToggleButton.text = "🎤"
        binding.participantCountText.text = "Participants: 1"
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupVideoTracks()
        room?.disconnect()
    }
}