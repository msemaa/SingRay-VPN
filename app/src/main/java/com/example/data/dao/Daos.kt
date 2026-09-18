package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.ServerEntity
import com.example.data.entity.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY isFavorite DESC, id DESC")
    fun getAllServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY isFavorite DESC, id DESC")
    suspend fun getAllServersSync(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    fun getSelectedServer(): Flow<ServerEntity?>

    @Query("SELECT * FROM servers WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedServerSync(): ServerEntity?

    @Query("SELECT * FROM servers WHERE lastPingMs > 0 ORDER BY lastPingMs ASC LIMIT 1")
    suspend fun getBestPingServer(): ServerEntity?

    @Query("SELECT * FROM servers WHERE lastPingMs > 0 ORDER BY lastPingMs ASC")
    suspend fun getServersSortedByPing(): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<ServerEntity>): List<Long>

    @Update
    suspend fun updateServer(server: ServerEntity)

    @Query("UPDATE servers SET lastPingMs = :pingMs, lastTestTimestamp = :timestamp WHERE id = :id")
    suspend fun updatePing(id: Long, pingMs: Long, timestamp: Long)

    @Query("UPDATE servers SET isSelected = CASE WHEN id = :selectedId THEN 1 ELSE 0 END")
    suspend fun setSelectedServer(selectedId: Long)

    @Delete
    suspend fun deleteServer(server: ServerEntity)

    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteServersBySubscription(subId: Long)

    @Query("DELETE FROM servers")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun getCount(): Int
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY id DESC")
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    @Delete
    suspend fun deleteSubscription(subscription: SubscriptionEntity)

    @Query("UPDATE subscriptions SET lastUpdated = :timestamp, totalNodes = :nodeCount WHERE id = :id")
    suspend fun updateNodeStats(id: Long, timestamp: Long, nodeCount: Int)
}
