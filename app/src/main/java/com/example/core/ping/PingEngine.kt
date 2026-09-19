package com.example.core.ping

import com.example.data.dao.ServerDao
import com.example.data.entity.ServerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class PingDiagnosticResult(
    val host: String,
    val port: Int,
    val packetsSent: Int,
    val packetsReceived: Int,
    val minLatencyMs: Long,
    val avgLatencyMs: Long,
    val maxLatencyMs: Long,
    val jitterMs: Long,
    val packetLossPercent: Int
)

object PingEngine {

    private const val DEFAULT_TIMEOUT_MS = 2500

    /**
     * Performs a real TCP socket handshake to host:port and measures round-trip time.
     * Returns latency in milliseconds, or -2 for timeout/error.
     */
    suspend fun testSocketPing(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Long = withContext(Dispatchers.IO) {
        if (host.isBlank() || port <= 0 || port > 65535) return@withContext -2L

        var socket: Socket? = null
        val startTime = System.currentTimeMillis()
        try {
            socket = Socket()
            socket.soTimeout = timeoutMs
            val address = InetSocketAddress(host, port)
            socket.connect(address, timeoutMs)
            val elapsed = System.currentTimeMillis() - startTime
            elapsed.coerceAtLeast(1L)
        } catch (e: Exception) {
            -2L
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Tests all servers in parallel with a bounded concurrency semaphore to prevent battery drain.
     */
    suspend fun testAllServers(
        servers: List<ServerEntity>,
        serverDao: ServerDao,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ServerEntity> = withContext(Dispatchers.IO) {
        val total = servers.size
        if (total == 0) return@withContext emptyList()

        val semaphore = Semaphore(5) // Max 5 parallel socket handshakes to save battery & RAM
        var completedCount = 0

        val deferreds = servers.map { server ->
            async {
                val ping = semaphore.withPermit {
                    testSocketPing(server.server, server.port)
                }
                val now = System.currentTimeMillis()
                serverDao.updatePing(server.id, ping, now, isRealDelay = false)
                synchronized(this@PingEngine) {
                    completedCount++
                    onProgress(completedCount, total)
                }
                server.copy(lastPingMs = ping, lastTestTimestamp = now, isRealDelay = false)
            }
        }

        deferreds.awaitAll()
    }

    /**
     * Diagnostic tool: runs multiple ping packets to evaluate jitter and loss.
     */
    suspend fun testDetailedDiagnostics(host: String, port: Int, packetCount: Int = 4): PingDiagnosticResult = withContext(Dispatchers.IO) {
        val latencies = mutableListOf<Long>()
        var received = 0

        for (i in 0 until packetCount) {
            val ping = testSocketPing(host, port, timeoutMs = 2000)
            if (ping > 0) {
                received++
                latencies.add(ping)
            }
            kotlinx.coroutines.delay(80)
        }

        val lossPercent = if (packetCount > 0) ((packetCount - received) * 100) / packetCount else 100
        val min = latencies.minOrNull() ?: -1L
        val max = latencies.maxOrNull() ?: -1L
        val avg = if (latencies.isNotEmpty()) latencies.average().toLong() else -1L

        val jitter = if (latencies.size > 1) {
            var diffSum = 0L
            for (i in 1 until latencies.size) {
                diffSum += kotlin.math.abs(latencies[i] - latencies[i - 1])
            }
            diffSum / (latencies.size - 1)
        } else 0L

        PingDiagnosticResult(
            host = host,
            port = port,
            packetsSent = packetCount,
            packetsReceived = received,
            minLatencyMs = min,
            avgLatencyMs = avg,
            maxLatencyMs = max,
            jitterMs = jitter,
            packetLossPercent = lossPercent
        )
    }
}
