package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.ServerDao
import com.example.data.dao.SubscriptionDao
import com.example.data.entity.ServerEntity
import com.example.data.entity.SubscriptionEntity

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SingRayDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        @Volatile
        private var INSTANCE: SingRayDatabase? = null

        fun getDatabase(context: Context): SingRayDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SingRayDatabase::class.java,
                    "singray_core.db"
                ).fallbackToDestructiveMigration()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
