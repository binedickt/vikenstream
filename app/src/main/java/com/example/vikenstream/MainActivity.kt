package com.example.livekitvideochat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import io.livekit.android.Room
import io.livekit.android.RoomListener
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var room: Room
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer

    // 🔹 Replace these with your LiveKit server and a valid token
    private val serverUrl = "wss://viken.stream:7880"  // e.g. wss://yourdomain.com:7880
    private val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZGVudGl0eSI6IiIsIm5hbWUiOiJBZG1pbiBVc2VyIiwidmlkZW8iOnsicm9vbUNyZWF0ZSI6ZmFsc2UsInJvb21MaXN0IjpmYWxzZSwicm9vbVJlY29yZCI6ZmFsc2UsInJvb21BZG1pbiI6dHJ1ZSwicm9vbUpvaW4iOnRydWUsInJvb20iOiJwdWJsaWMtcm9vbSIsImNhblB1Ymxpc2giOnRydWUsImNhblN1YnNjcmliZSI6dHJ1ZSwiY2FuUHVibGlzaERhdGEiOnRydWUsImNhblB1Ymxpc2hTb3VyY2VzIjpbXSwiY2FuVXBkYXRlT3duTWV0YWRhdGEiOnRydWUsImluZ3Jlc3NBZG1pbiI6ZmFsc2UsImhpZGRlbiI6ZmFsc2UsInJlY29yZGVyIjpmYWxzZSwiYWdlbnQiOmZhbHNlfSwibWV0YWRhdGEiOiIiLCJzaGEyNTYiOiIiLCJzdWIiOiJhZG1pbiIsImlzcyI6IkFQSURWTGl1Zko1bnZ5byIsIm5iZiI6MTc1NTQzNzQzNCwiZXhwIjoxNzU1NDQxMDM0fQ.6vsovbcbSt8P1440D6Zv5wIdDslXtB5Uyyd_9pVW9co"          // generated from backend

    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localView = findViewById(R.id.local_video)
        remoteView = findViewById(R.id.remote_video)

        // Request camera & mic permissions
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted = permissions[Manifest.permission.CAMERA] == true &&
                    permissions[Manifest.permission.RECORD_AUDIO] == true
            if (granted) {
                connectToRoom()
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        } else {
            connectToRoom()
        }
    }

    private fun connectToRoom() {
        uiScope.launch {
            room = Room(this@MainActivity)

            room.listener = object : RoomListener {
                override fun onRoomEvent(event: RoomEvent) {
                    when (event) {
                        is RoomEvent.TrackSubscribed -> {
                            val track = event.publication.track
                            if (track is VideoTrack) {
                                track.addRenderer(remoteView)
                            }
                        }
                        else -> { /* ignore other events for now */ }
                    }
                }
            }

            // Connect to LiveKit server
            room.connect(serverUrl, token)

            // Publish local camera video
            val localTrack = room.localParticipant.createVideoTrack(this@MainActivity)
            localTrack?.addRenderer(localView)
            if (localTrack != null) {
                room.localParticipant.publishTrack(localTrack)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::room.isInitialized) {
            room.disconnect()
        }
    }
}
