package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "volume_logs")
data class VolumeLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val streamType: Int,
    val streamName: String,
    val droppedVolume: Int,
    val restoredVolume: Int,
    val maxVolume: Int,
    val reason: String
)
