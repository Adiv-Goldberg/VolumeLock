package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VolumeLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: VolumeLogEntity): Long

    @Query("SELECT * FROM volume_logs ORDER BY timestamp DESC LIMIT 100")
    fun getAllLogs(): Flow<List<VolumeLogEntity>>

    @Query("SELECT COUNT(*) FROM volume_logs")
    fun getLogCount(): Flow<Int>

    @Query("DELETE FROM volume_logs")
    suspend fun clearAllLogs()
}
