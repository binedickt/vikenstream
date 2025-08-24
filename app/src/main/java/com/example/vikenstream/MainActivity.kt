package com.example.vikenstream
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {

    private lateinit var room: Room

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        room = LiveKit.create(applicationContext)

        CoroutineScope(Dispatchers.Main).launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        println("✅ Connected to room!")
                    }
                    is RoomEvent.Disconnected -> {
                        println("❌ Disconnected: ${event.error}")
                    }
                    is RoomEvent.TrackSubscribed -> {
                        val track: Track = event.track
                        if (track is VideoTrack) {
                            println("🎥 Subscribed to video track")
                        }
                    }
                    else -> {
                        println("📢 Event: $event")
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                room.connect(
                    url = "wss://viken.stream:7880",
                    token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZGVudGl0eSI6IiIsIm5hbWUiOiJ1c2VyMSIsInZpZGVvIjp7InJvb21DcmVhdGUiOmZhbHNlLCJyb29tTGlzdCI6ZmFsc2UsInJvb21SZWNvcmQiOmZhbHNlLCJyb29tQWRtaW4iOmZhbHNlLCJyb29tSm9pbiI6dHJ1ZSwicm9vbSI6InB1YmxpYyIsImNhblB1Ymxpc2giOnRydWUsImNhblN1YnNjcmliZSI6dHJ1ZSwiY2FuUHVibGlzaERhdGEiOnRydWUsImNhblB1Ymxpc2hTb3VyY2VzIjpbXSwiY2FuVXBkYXRlT3duTWV0YWRhdGEiOmZhbHNlLCJpbmdyZXNzQWRtaW4iOmZhbHNlLCJoaWRkZW4iOmZhbHNlLCJyZWNvcmRlciI6ZmFsc2UsImFnZW50IjpmYWxzZX0sIm1ldGFkYXRhIjoiIiwic2hhMjU2IjoiIiwic3ViIjoidXNlcjEiLCJpc3MiOiJBUElEVkxpdWZKNW52eW8iLCJuYmYiOjE3NTYwMzI3MTcsImV4cCI6MTc1NjAzNjMxN30.Fzpu3l51ecWlRHEZhShOyOpH7y-XzOziI9WDqb3GUYQ"
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        room.disconnect()
    }
}
