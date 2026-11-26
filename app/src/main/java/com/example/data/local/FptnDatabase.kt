package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TokenProfileEntity::class, ServerEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FptnDatabase : RoomDatabase() {
    abstract fun tokenDao(): TokenDao

    companion object {
        @Volatile
        private var instance: FptnDatabase? = null

        fun getInstance(context: Context): FptnDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FptnDatabase::class.java,
                    "fptn_client.db",
                ).build().also { instance = it }
            }
        }
    }
}
