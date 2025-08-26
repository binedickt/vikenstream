package com.example.vikenstream

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RoomsAdapter(
    private val rooms: List<String>,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<RoomsAdapter.RoomViewHolder>() {
    class RoomViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val roomName: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RoomViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
        return RoomViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: RoomViewHolder,
        position: Int,
    ) {
        val room = rooms[position]
        holder.roomName.text = room
        holder.itemView.setOnClickListener { onClick(room) }
    }

    override fun getItemCount(): Int = rooms.size
}
