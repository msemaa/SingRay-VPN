package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.ProxyProtocol

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: String = "vless",
    val server: String,
    val port: Int,
    val uuid: String = "",
    val alterId: Int = 0,
    val security: String = "none", // none, tls, reality
    val sni: String = "",
    val publicKey: String = "", // for Reality
    val shortId: String = "",
    val fingerprint: String = "chrome",
    val network: String = "tcp", // tcp, ws, grpc, quic
    val path: String = "",
    val host: String = "",
    val alpn: String = "",
    val insecure: Boolean = false,
    val subscriptionId: Long? = null,
    val lastPingMs: Long = -1L, // -1 untested, -2 timeout, >0 ms
    val lastTestTimestamp: Long = 0L,
    val isRealDelay: Boolean = false,
    val rawUri: String = "",
    val isFavorite: Boolean = false,
    val isSelected: Boolean = false
) {
    fun getProtocolEnum(): ProxyProtocol = ProxyProtocol.fromString(protocol)
}

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
    val autoUpdate: Boolean = true,
    val totalNodes: Int = 0
)
