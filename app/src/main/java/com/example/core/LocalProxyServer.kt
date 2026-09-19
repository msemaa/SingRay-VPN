package com.example.core

import android.net.VpnService
import android.util.Log
import com.example.core.proxy.Outbound
import com.example.core.proxy.ProxyConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Local inbound server.
 *
 * Accepts SOCKS5 (and plain HTTP / HTTPS CONNECT on the same port) from local
 * apps and forwards every stream through the configured proxy outbound
 * (VLESS / VMess / Trojan / Shadowsocks).
 *
 * This is the part that was broken before: the old implementation opened a
 * direct socket to the destination, so traffic never touched the proxy server.
 */
class LocalProxyServer(
    private val vpnService: VpnService?,
    private val port: Int = 10808,
    private val outboundProvider: () -> Outbound,
    private val onTraffic: (rx: Long, tx: Long) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start() {
        stop()
        serverJob = scope.launch {
            try {
                val ss = ServerSocket(port, 128, InetAddress.getByName("127.0.0.1"))
                ss.reuseAddress = true
                serverSocket = ss
                Log.i(TAG, "Local inbound (SOCKS5 + HTTP) listening on 127.0.0.1:$port")

                while (isActive && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isActive || ss.isClosed) break
                    }
                }
            } catch (e: Exception) {
                onError("Local inbound failed on port $port: ${e.message}")
                Log.e(TAG, "inbound start failed", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstream: ProxyConnection? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 0
            val input = client.getInputStream().buffered()
            val output = client.getOutputStream()

            input.mark(1)
            val first = input.read()
            if (first < 0) { client.close(); return }
            input.reset()

            upstream = if (first == 0x05) {
                handleSocks5(input, output)
            } else {
                handleHttp(input, output)
            }

            if (upstream == null) {
                try { client.close() } catch (_: Exception) {}
                return
            }
            relay(client, input, output, upstream)
        } catch (e: Exception) {
            try { upstream?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ---------------- SOCKS5 ----------------

    private fun handleSocks5(input: InputStream, output: OutputStream): ProxyConnection? {
        if (input.read() != 0x05) return null
        val nMethods = input.read()
        if (nMethods < 0) return null
        repeat(nMethods) { input.read() }
        output.write(byteArrayOf(5, 0)); output.flush()

        input.read() // ver
        val cmd = input.read()
        input.read() // rsv
        val atyp = input.read()

        if (cmd != 0x01) { // only CONNECT; UDP ASSOCIATE is not supported by this core
            output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
            return null
        }

        val host: String = when (atyp) {
            0x01 -> {
                val ip = ByteArray(4); readFull(input, ip)
                InetAddress.getByAddress(ip).hostAddress ?: return null
            }
            0x03 -> {
                val len = input.read()
                if (len <= 0) return null
                val d = ByteArray(len); readFull(input, d)
                String(d, Charsets.UTF_8)
            }
            0x04 -> {
                val ip = ByteArray(16); readFull(input, ip)
                InetAddress.getByAddress(ip).hostAddress ?: return null
            }
            else -> return null
        }
        val p = ByteArray(2); readFull(input, p)
        val destPort = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)

        return try {
            val conn = outboundProvider().connect(host, destPort)
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
            conn
        } catch (e: Exception) {
            onError("Tunnel to $host:$destPort failed: ${e.message}")
            // 0x05 = connection refused by upstream
            try { output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush() } catch (_: Exception) {}
            null
        }
    }

    // ---------------- HTTP / HTTPS ----------------

    private fun handleHttp(input: InputStream, output: OutputStream): ProxyConnection? {
        val requestLine = StringBuilder()
        val headerBlock = StringBuilder()
        var line = readLine(input) ?: return null
        requestLine.append(line)
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            headerBlock.append(h).append(CRLF)
        }

        val parts = requestLine.toString().split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]

        return if (method == "CONNECT") {
            val host = target.substringBeforeLast(":")
            val destPort = target.substringAfterLast(":").toIntOrNull() ?: 443
            try {
                val conn = outboundProvider().connect(host, destPort)
                output.write(("HTTP/1.1 200 Connection Established" + CRLF + CRLF).toByteArray())
                output.flush()
                conn
            } catch (e: Exception) {
                onError("Tunnel to $host:$destPort failed: ${e.message}")
                try {
                    output.write(("HTTP/1.1 502 Bad Gateway" + CRLF + CRLF).toByteArray()); output.flush()
                } catch (_: Exception) {}
                null
            }
        } else {
            // Plain HTTP proxy request: rewrite absolute URI to origin form.
            val uri = try { java.net.URI(target) } catch (_: Exception) { null } ?: return null
            val host = uri.host ?: return null
            val destPort = if (uri.port > 0) uri.port else 80
            val pathPart = (uri.rawPath ?: "/").ifBlank { "/" } +
                (uri.rawQuery?.let { "?$it" } ?: "")
            try {
                val conn = outboundProvider().connect(host, destPort)
                val rebuilt = StringBuilder()
                    .append(method).append(" ").append(pathPart).append(" HTTP/1.1").append(CRLF)
                    .append(headerBlock)
                    .append(CRLF)
                conn.output.write(rebuilt.toString().toByteArray(Charsets.ISO_8859_1))
                conn.output.flush()
                conn
            } catch (e: Exception) {
                onError("Tunnel to $host:$destPort failed: ${e.message}")
                null
            }
        }
    }

    // ---------------- relay ----------------

    private fun relay(client: Socket, cIn: InputStream, cOut: OutputStream, upstream: ProxyConnection) {
        val up = scope.launch {
            pipe(cIn, upstream.output) { onTraffic(0, it) }
            try { upstream.socket.shutdownOutput() } catch (_: Exception) {}
        }
        val down = scope.launch {
            pipe(upstream.input, cOut) { onTraffic(it, 0) }
            try { client.shutdownOutput() } catch (_: Exception) {}
        }
        scope.launch {
            up.join(); down.join()
            try { client.close() } catch (_: Exception) {}
            try { upstream.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(32768)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                output.flush()
                onBytes(read.toLong())
            }
        } catch (_: Exception) {
        }
    }

    private fun readFull(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r <= 0) throw java.io.EOFException()
            off += r
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 0x0A) return sb.toString().removeSuffix("\u000D")
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        try { serverJob?.cancel() } catch (_: Exception) {}
        serverSocket = null
        serverJob = null
    }

    companion object {
        private const val TAG = "LocalProxyServer"
        private const val CRLF = "\u000D\u000A"
    }
}
