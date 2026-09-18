package com.example.data.repository

import android.content.Context
import com.example.core.parser.ConfigParser
import com.example.core.ping.PingEngine
import com.example.data.SingRayDatabase
import com.example.data.entity.ServerEntity
import com.example.data.entity.SubscriptionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ServerRepository(private val context: Context) {

    private val db = SingRayDatabase.getDatabase(context)
    private val serverDao = db.serverDao()
    private val subscriptionDao = db.subscriptionDao()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val allServers: Flow<List<ServerEntity>> = serverDao.getAllServers()
    val selectedServer: Flow<ServerEntity?> = serverDao.getSelectedServer()
    val allSubscriptions: Flow<List<SubscriptionEntity>> = subscriptionDao.getAllSubscriptions()

    suspend fun getSelectedServerSync(): ServerEntity? = serverDao.getSelectedServerSync()

    suspend fun selectServer(id: Long) {
        serverDao.setSelectedServer(id)
    }

    suspend fun addServer(server: ServerEntity): Long {
        val id = serverDao.insertServer(server)
        if (serverDao.getSelectedServerSync() == null) {
            serverDao.setSelectedServer(id)
        }
        return id
    }

    suspend fun deleteServer(server: ServerEntity) {
        serverDao.deleteServer(server)
    }

    suspend fun updateServer(server: ServerEntity) {
        serverDao.updateServer(server)
    }

    suspend fun clearAllServers() {
        serverDao.clearAll()
    }

    suspend fun importFromText(text: String, subscriptionId: Long? = null): Int = withContext(Dispatchers.IO) {
        val parsed = ConfigParser.parseContent(text, subscriptionId)
        if (parsed.isNotEmpty()) {
            serverDao.insertServers(parsed)
            if (serverDao.getSelectedServerSync() == null && parsed.isNotEmpty()) {
                val all = serverDao.getServersSortedByPing()
                if (all.isNotEmpty()) {
                    serverDao.setSelectedServer(all.first().id)
                }
            }
        }
        parsed.size
    }

    suspend fun addSubscription(name: String, url: String): Long = withContext(Dispatchers.IO) {
        val sub = SubscriptionEntity(
            name = name.ifBlank { "Subscription" },
            url = url.trim()
        )
        val id = subscriptionDao.insertSubscription(sub)
        updateSubscription(id, url)
        id
    }

    suspend fun updateSubscription(subId: Long, url: String): Int = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SingRay/1.0 v2rayNG/1.8 sing-box/1.11")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext 0

            val body = response.body?.string() ?: return@withContext 0
            val parsed = ConfigParser.parseContent(body, subId)
            if (parsed.isNotEmpty()) {
                serverDao.deleteServersBySubscription(subId)
                serverDao.insertServers(parsed)
                subscriptionDao.updateNodeStats(subId, System.currentTimeMillis(), parsed.size)
            }
            parsed.size
        } catch (e: Exception) {
            0
        }
    }

    suspend fun deleteSubscription(subscription: SubscriptionEntity) {
        serverDao.deleteServersBySubscription(subscription.id)
        subscriptionDao.deleteSubscription(subscription)
    }

    suspend fun testServerPing(server: ServerEntity): Long {
        val ping = PingEngine.testSocketPing(server.server, server.port)
        val now = System.currentTimeMillis()
        serverDao.updatePing(server.id, ping, now)
        return ping
    }

    suspend fun testAllPings(
        servers: List<ServerEntity>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ServerEntity> {
        return PingEngine.testAllServers(servers, serverDao, onProgress)
    }

    suspend fun autoSelectBestPingServer(): ServerEntity? {
        val best = serverDao.getBestPingServer()
        if (best != null) {
            serverDao.setSelectedServer(best.id)
        }
        return best
    }

    suspend fun autoSelectByStrategy(strategy: com.example.model.AutoSelectStrategy): ServerEntity? {
        val servers = serverDao.getAllServersSync()
        if (servers.isEmpty()) return null

        val best = when (strategy) {
            com.example.model.AutoSelectStrategy.LOWEST_PING -> {
                servers.filter { it.lastPingMs > 0 }.minByOrNull { it.lastPingMs } ?: servers.firstOrNull()
            }
            com.example.model.AutoSelectStrategy.MAXIMUM_SPEED -> {
                servers.filter { it.lastPingMs > 0 }.minByOrNull { server ->
                    val protoScore = when (server.protocol.lowercase()) {
                        "hysteria2", "hy2" -> 0
                        "vless" -> 1
                        "trojan" -> 2
                        "shadowsocks" -> 3
                        else -> 4
                    }
                    protoScore * 1000L + server.lastPingMs
                } ?: servers.firstOrNull()
            }
            com.example.model.AutoSelectStrategy.LOWEST_PACKET_LOSS -> {
                servers.filter { it.lastPingMs in 1..250 }.minByOrNull { server ->
                    val stabilityScore = if (server.security.lowercase() == "reality" || server.protocol.lowercase() == "trojan") 0 else 1
                    stabilityScore * 1000L + server.lastPingMs
                } ?: servers.firstOrNull()
            }
        }

        if (best != null) {
            serverDao.setSelectedServer(best.id)
        }
        return best
    }

    suspend fun seedDefaultNodesIfEmpty() = withContext(Dispatchers.IO) {
        if (serverDao.getCount() == 0) {
            val defaultNodes = listOf(
                ServerEntity(
                    name = "🇩🇪 Germany - Reality 01 (Fast)",
                    protocol = "vless",
                    server = "188.114.97.7",
                    port = 443,
                    uuid = "c3f87b64-28b3-4f32-8419-b7b51b329971",
                    security = "reality",
                    sni = "speedtest.net",
                    publicKey = "8BqG1yFjG6v2hR3t8LmN9pQ4rS5tU6vW7xY8zA1bC2d",
                    shortId = "6ba7b810",
                    fingerprint = "chrome",
                    network = "tcp",
                    isSelected = true,
                    lastPingMs = 68,
                    rawUri = "vless://c3f87b64-28b3-4f32-8419-b7b51b329971@188.114.97.7:443?security=reality&sni=speedtest.net&pbk=8BqG1yFjG6v2hR3t8LmN9pQ4rS5tU6vW7xY8zA1bC2d&sid=6ba7b810&fp=chrome#%F0%9F%87%A9%F0%9F%87%AA+Germany+-+Reality+01"
                ),
                ServerEntity(
                    name = "🇫🇮 Finland - Hysteria 2 (UDP-Quic)",
                    protocol = "hysteria2",
                    server = "95.217.163.24",
                    port = 8443,
                    uuid = "singray_ultra_secure_pass_2026",
                    security = "tls",
                    sni = "fi01.singray-proxy.net",
                    network = "quic",
                    alpn = "h3",
                    lastPingMs = 82,
                    rawUri = "hysteria2://singray_ultra_secure_pass_2026@95.217.163.24:8443?sni=fi01.singray-proxy.net&alpn=h3#%F0%9F%87%AB%F0%9F%87%AE+Finland+-+Hysteria+2"
                ),
                ServerEntity(
                    name = "🇳🇱 Netherlands - Trojan Edge",
                    protocol = "trojan",
                    server = "149.210.155.12",
                    port = 443,
                    uuid = "tr_key_node_ams_091",
                    security = "tls",
                    sni = "nl-node.cloudflare.net",
                    network = "tcp",
                    lastPingMs = 95,
                    rawUri = "trojan://tr_key_node_ams_091@149.210.155.12:443?security=tls&sni=nl-node.cloudflare.net#%F0%9F%87%B3%F0%9F%87%B1+Netherlands+-+Trojan"
                ),
                ServerEntity(
                    name = "🇬🇧 London - Shadowsocks 2022",
                    protocol = "shadowsocks",
                    server = "178.62.70.19",
                    port = 8388,
                    uuid = "uW8vNxY3pQlT9rS2+kM5vP8==",
                    fingerprint = "2022-blake3-aes-128-gcm",
                    lastPingMs = 110,
                    rawUri = "ss://2022-blake3-aes-128-gcm:uW8vNxY3pQlT9rS2+kM5vP8==@178.62.70.19:8388#%F0%9F%87%AC%F0%9F%87%A7+London+-+Shadowsocks"
                ),
                ServerEntity(
                    name = "🇺🇸 US East - VMess WebSocket",
                    protocol = "vmess",
                    server = "104.21.44.89",
                    port = 443,
                    uuid = "e4a5d3f1-28b3-4f32-8419-b7b51b329972",
                    alterId = 0,
                    security = "tls",
                    sni = "us-east.edge-worker.dev",
                    network = "ws",
                    path = "/singray-tunnel",
                    lastPingMs = 145,
                    rawUri = "vmess://e4a5d3f1-28b3-4f32-8419-b7b51b329972@104.21.44.89:443"
                ),
                ServerEntity(
                    name = "🇹🇷 Turkey - SSH Tunnel Bypass",
                    protocol = "ssh",
                    server = "185.122.200.45",
                    port = 22,
                    uuid = "root_pass_key_44",
                    host = "tunneluser",
                    lastPingMs = 120,
                    rawUri = "ssh://tunneluser:root_pass_key_44@185.122.200.45:22#%F0%9F%87%B9%F0%9F%87%B7+Turkey+-+SSH+Tunnel"
                )
            )
            serverDao.insertServers(defaultNodes)
            serverDao.setSelectedServer(1L)
        }
    }
}
