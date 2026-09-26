package com.otobusreklam.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ItemEntity::class, ContentEntity::class, ChunkEntity::class, PlayLogEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun contents(): ContentDao
    abstract fun chunks(): ChunkDao
    abstract fun playLog(): PlayLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "reklam.db"
            )
                // Ani guc kesintisi bu cihazin normal hayati.
                // WAL + FULL senkron: yazilan satir gercekten diske insin.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
