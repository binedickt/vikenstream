package com.example.vikenstream

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RoomsAdapter(
    private val rooms: List<RoomData>,
    private val onRoomClicked: (RoomData) -> Unit
) : RecyclerView.Adapter<RoomsAdapter.RoomViewHolder>() {

    class RoomViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val roomName: TextView = itemView.findViewById(R.id.roomName)
        val roomParticipants: TextView = itemView.findViewById(R.id.roomParticipants)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room, parent, false)
        return RoomViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        val room = rooms[position]

        holder.roomName.text = room.name

        holder.itemView.setOnClickListener {
            onRoomClicked(room)
        }
    }

    override fun getItemCount(): Int = rooms.size
}