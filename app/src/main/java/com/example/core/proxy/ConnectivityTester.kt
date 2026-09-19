package com.example.core.proxy

import android.net.VpnService
import com.example.data.entity.ServerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class RealDelayResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String
)

/**
 * Performs a real end-to-end check: opens the proxy tunnel and issues a real
 * HTTP request through it. This is what makes the UI honest -- the app only
 * reports CONNECTED when actual data came back through the proxy.
 */
object ConnectivityTester {

    private const val CRLF = "\r\n"
    private const val TEST_HOST = "cp.cloudflare.com"
    private const val TEST_PATH = "/generate_204"

    /**
     * Verifies a running native core (sing-box / Xray) by performing a real
     * request through its own local SOCKS5 inbound.
     */
    suspend fun socksProbe(
        host: String = "127.0.0.1",
        port: Int = 10808,
        timeoutMs: Long = 12000
    ): RealDelayResult = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            var socket: java.net.Socket? = null
            try {
                val start = System.currentTimeMillis()
                val s = java.net.Socket()
                s.tcpNoDelay = true
                s.connect(java.net.InetSocketAddress(host, port), timeoutMs.toInt())
                s.soTimeout = timeoutMs.toInt()
                socket = s
                val out = s.getOutputStream()
                val input = s.getInputStream()

                // SOCKS5 greeting
                out.writeAndFlush(byteArrayOf(0x05, 0x01, 0x00))
                val greeting = ByteArray(2)
                input.readFully(greeting.size).copyInto(greeting)
                if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                    return@withTimeoutOrNull RealDelayResult(false, -1, "Local SOCKS inbound refused the handshake")
                }

                // CONNECT cp.cloudflare.com:80
                val hostBytes = TEST_HOST.toByteArray(Charsets.US_ASCII)
                val request = ByteArray(7 + hostBytes.size)
                request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x03
                request[4] = hostBytes.size.toByte()
                hostBytes.copyInto(request, 5)
                request[5 + hostBytes.size] = 0x00
                request[6 + hostBytes.size] = 80.toByte()
                out.writeAndFlush(request)

                val reply = input.readFully(4)
                if (reply[1] != 0x00.toByte()) {
                    return@withTimeoutOrNull RealDelayResult(false, -1, "Core could not reach the destination (SOCKS code ${reply[1].toInt()})")
                }
                when (reply[3].toInt()) {
                    0x01 -> input.readFully(4)
                    0x03 -> input.readFully(input.read())
                    0x04 -> input.readFully(16)
                }
                input.readFully(2)

                out.writeAndFlush(
                    ("GET " + TEST_PATH + " HTTP/1.1" + CRLF +
                        "Host: " + TEST_HOST + CRLF +
                        "Connection: close" + CRLF + CRLF).toByteArray(Charsets.UTF_8)
                )
                val buffer = ByteArray(256)
                val read = input.read(buffer)
                val elapsed = System.currentTimeMillis() - start
                if (read <= 0) {
                    RealDelayResult(false, -1, "No data returned through the core")
                } else {
                    val head = String(buffer, 0, read, Charsets.ISO_8859_1)
                    if (head.startsWith("HTTP/")) {
                        RealDelayResult(true, elapsed.coerceAtLeast(1), head.lineSequence().first().trim())
                    } else {
                        RealDelayResult(false, -1, "Unexpected response through the core")
                    }
                }
            } catch (e: Exception) {
                RealDelayResult(false, -1, e.message ?: e.javaClass.simpleName)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        } ?: RealDelayResult(false, -1, "Timed out after " + timeoutMs + "ms")
    }

    suspend fun realDelay(
        server: ServerEntity,
        vpnService: VpnService?,
        timeoutMs: Long = 12000
    ): RealDelayResult = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            var conn: ProxyConnection? = null
            try {
                val outbound = OutboundFactory.create(server, vpnService)
                val start = System.currentTimeMillis()
                conn = outbound.connect(TEST_HOST, 80, timeoutMs.toInt())

                val request = "GET " + TEST_PATH + " HTTP/1.1" + CRLF +
                    "Host: " + TEST_HOST + CRLF +
                    "User-Agent: SingRay/2.0" + CRLF +
                    "Connection: close" + CRLF + CRLF
                conn.output.writeAndFlush(request.toByteArray(Charsets.UTF_8))

                conn.socket.soTimeout = timeoutMs.toInt()
                val buffer = ByteArray(256)
                val read = conn.input.read(buffer)
                val elapsed = System.currentTimeMillis() - start

                if (read <= 0) {
                    RealDelayResult(false, -1, "Tunnel opened but the server returned no data (wrong UUID/password or dead node).")
                } else {
                    val head = String(buffer, 0, read, Charsets.ISO_8859_1)
                    if (head.startsWith("HTTP/")) {
                        RealDelayResult(true, elapsed.coerceAtLeast(1), "OK (" + head.lineSequence().first().trim() + ")")
                    } else {
                        RealDelayResult(false, -1, "Unexpected response through tunnel (handshake mismatch).")
                    }
                }
            } catch (e: UnsupportedConfigException) {
                RealDelayResult(false, -1, e.message ?: "Unsupported config")
            } catch (e: Exception) {
                RealDelayResult(false, -1, e.message ?: e.javaClass.simpleName)
            } finally {
                try { conn?.close() } catch (_: Exception) {}
            }
        } ?: RealDelayResult(false, -1, "Timed out after " + timeoutMs + "ms")
    }
}
