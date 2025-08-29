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

        try {
            Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE)
            Log.d("MainActivity", "WebRTC verbose logging enabled")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("MainActivity", "Failed to enable WebRTC logging: ${e.message}")
            Toast.makeText(this, "WebRTC logging unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }

        setupUI()
        checkPermissions()
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
                accessToken = fetchTokenFromServer(username, password)
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
                room?.disconnect()
                room = null

                val liveKitToken = fetchLiveKitToken(roomName, accessToken!!)
                room = LiveKit.create(this@MainActivity)
                room?.connect(
                    url = "wss://viken.stream:8443",
                    token = liveKitToken
                )

                setupRoomEvents(room!!)
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
                        val token = json.getString("token")
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
                        val track = event.track
                        val participant = event.participant
                        if (track is VideoTrack) {
                            runOnUiThread {
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
                        val publication = event.publication
                        val participant = event.participant.identity
                        if (publication.track is VideoTrack && participant == room.localParticipant.identity) {
                            val videoTrack = publication.track as VideoTrack
                            localVideoTrack = videoTrack
                            runOnUiThread {
                                attachLocalVideoTrack(videoTrack)
                            }
                        }
                    }
                    else -> Log.d("MainActivity", "Unhandled event: $event")
                }
            }
        }
    }

    private fun enableLocalVideo(room: Room) {
        lifecycleScope.launch {
            try {
                if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Camera permission is required", Toast.LENGTH_LONG).show()
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(android.Manifest.permission.CAMERA),
                            PERMISSION_REQUEST_CODE
                        )
                    }
                    return@launch
                }

                room.localParticipant.setCameraEnabled(true)
                room.localParticipant.setMicrophoneEnabled(true)

                repeat(5) { attempt ->
                    delay(500)
                    val videoPublication = room.localParticipant.trackPublications.values
                        .firstOrNull { it.track is VideoTrack }
                    if (videoPublication != null) {
                        val videoTrack = videoPublication.track as VideoTrack
                        localVideoTrack = videoTrack
                        runOnUiThread { attachLocalVideoTrack(videoTrack) }
                        return@repeat
                    }
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to enable camera: ${e.message}", e)
            }
        }
    }

    private fun attachLocalVideoTrack(track: VideoTrack) {
        runOnUiThread {
            binding.localVideoView.let { videoView ->
                localVideoTrack?.removeRenderer(videoView)
                localVideoTrack = track
                room?.initVideoRenderer(videoView) // ✅ use LiveKit init
                track.addRenderer(videoView)
                videoView.setMirror(true)
                videoView.visibility = View.VISIBLE
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
        remoteVideoTracks[participantId] = track
        val videoView = getAvailableRemoteVideoView(participantId)
        videoView?.let {
            room?.initVideoRenderer(it) // ✅ use LiveKit init
            it.setMirror(false)
            track.addRenderer(it)
            it.visibility = View.VISIBLE
            it.tag = participantId
        }
        updateParticipantsList()
    }

    private fun removeRemoteVideoTrack(participantId: String) {
        remoteVideoTracks[participantId]?.let { track ->
            val videoView = findVideoViewForParticipant(participantId)
            videoView?.let {
                track.removeRenderer(it)
                it.visibility = View.GONE
                it.tag = null
            }
        }
        remoteVideoTracks.remove(participantId)
        updateParticipantsList()
    }

    private fun getAvailableRemoteVideoView(participantId: String): SurfaceViewRenderer? {
        return listOf(
            binding.remoteVideoView1,
            binding.remoteVideoView2,
            binding.remoteVideoView3,
            binding.remoteVideoView4
        ).find { it.tag == null || it.visibility == View.GONE }
    }

    private fun findVideoViewForParticipant(participantId: String): SurfaceViewRenderer? {
        return listOf(
            binding.remoteVideoView1,
            binding.remoteVideoView2,
            binding.remoteVideoView3,
            binding.remoteVideoView4
        ).find { it.tag == participantId }
    }

    private fun updateParticipantsList() {
        val participantCount = remoteVideoTracks.size + 1
        binding.participantCountText.text = "Participants: $participantCount"
    }

    private fun cleanupVideoTracks() {
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
        lifecycleScope.launch {
            try {
                val rooms = fetchRooms(accessToken!!)
                runOnUiThread { showRoomsList(rooms) }
            } catch (e: Exception) {
                runOnUiThread { showLoginScreen() }
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

        binding.cameraToggleButton.setOnClickListener { toggleCamera() }
        binding.micToggleButton.setOnClickListener { toggleMicrophone() }
        binding.leaveRoomButton.setOnClickListener { leaveRoom() }

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
        Log.d("MainActivity", "Cleaned up resources in onDestroy")
    }
}
