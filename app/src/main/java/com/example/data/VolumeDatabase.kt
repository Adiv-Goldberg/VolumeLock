package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VolumeLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class VolumeDatabase : RoomDatabase() {
    abstract fun volumeLogDao(): VolumeLogDao

    companion object {
        @Volatile
        private var INSTANCE: VolumeDatabase? = null

        fun getInstance(context: Context): VolumeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VolumeDatabase::class.java,
                    "volume_guard.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
