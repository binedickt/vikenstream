package com.example.vikenstream

data class RoomData(
    val name: String,
    val participantCount: Int = 0,
    val isPrivate: Boolean = false
)