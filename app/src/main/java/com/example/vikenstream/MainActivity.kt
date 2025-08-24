package com.example.vikenstream

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vikenstream.databinding.ActivityMainBinding
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var room: Room? = null
    private var accessToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
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
                        }
                    }
                    is RoomEvent.Disconnected -> {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "❌ Disconnected: ${event.error}", Toast.LENGTH_LONG).show()
                            showLoginScreen()
                        }
                    }
                    is RoomEvent.TrackSubscribed -> {
                        val track = event.track
                        if (track is VideoTrack) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "🎥 Video track received", Toast.LENGTH_SHORT).show()
                                // TODO: attach the video track to your SurfaceView
                            }
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        // Handle track removal if needed
                    }
                    else -> println("📢 Event: $event")
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
    }

    override fun onDestroy() {
        super.onDestroy()
        room?.disconnect()
    }
}
