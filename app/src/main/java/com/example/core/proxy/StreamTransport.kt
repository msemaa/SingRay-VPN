package com.example.core.proxy

import android.net.VpnService
import android.os.Build
import com.example.data.entity.ServerEntity
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds the raw stream to the proxy server: TCP -> (optional TLS) -> (optional WS / HTTPUpgrade).
 * The protocol layers (VLESS/VMess/Trojan/SS) are stacked on top of this.
 */
object StreamTransport {

    fun open(server: ServerEntity, vpnService: VpnService?, timeoutMs: Int): ProxyConnection {
        val socket = newProtectedSocket(vpnService, server.server, server.port, timeoutMs)

        var input: InputStream
        var output: OutputStream
        var activeSocket: Socket = socket

        val security = server.security.trim().lowercase()
        if (security == "tls" || security == "xtls") {
            val sni = server.sni.ifBlank { server.host.ifBlank { server.server } }
            val ssl = wrapTls(socket, sni, server.alpn, server.insecure, timeoutMs)
            activeSocket = ssl
            input = ssl.inputStream
            output = ssl.outputStream
        } else {
            input = socket.getInputStream()
            output = socket.getOutputStream()
        }

        when (server.network.trim().lowercase()) {
            "ws", "websocket" -> {
                val hostHeader = server.host.ifBlank { server.sni.ifBlank { server.server } }
                val path = server.path.ifBlank { "/" }
                val ws = WebSocketLayer.handshake(input, output, hostHeader, path, server.port, security == "tls")
                input = ws.first
                output = ws.second
            }
            "httpupgrade" -> {
                val hostHeader = server.host.ifBlank { server.sni.ifBlank { server.server } }
                val path = server.path.ifBlank { "/" }
                HttpUpgradeLayer.handshake(input, output, hostHeader, path)
            }
            "grpc" -> throw UnsupportedConfigException("gRPC transport needs the native core and is not supported by the built-in core.")
            "quic" -> throw UnsupportedConfigException("QUIC transport needs the native core.")
            "tcp", "", "http", "raw" -> { /* plain */ }
            else -> { /* unknown -> treat as plain TCP */ }
        }

        return ProxyConnection(activeSocket, input, output)
    }

    private fun wrapTls(
        socket: Socket,
        sni: String,
        alpn: String,
        insecure: Boolean,
        timeoutMs: Int
    ): SSLSocket {
        val factory: SSLSocketFactory = if (insecure) insecureContext().socketFactory
        else SSLSocketFactory.getDefault() as SSLSocketFactory

        val ssl = factory.createSocket(socket, sni, socket.port, true) as SSLSocket
        ssl.soTimeout = 0
        try {
            val params = ssl.sslParameters
            if (sni.isNotBlank() && !AddressCodec.isIpv4(sni)) {
                params.serverNames = listOf(SNIHostName(sni))
            }
            if (alpn.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                params.applicationProtocols = alpn.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toTypedArray()
            }
            if (!insecure && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                params.endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.sslParameters = params
        } catch (_: Exception) {}

        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        ssl.soTimeout = 0
        return ssl
    }

    private fun insecureContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustAll), SecureRandom())
        return ctx
    }
}

/** Minimal RFC 6455 client used as a transport carrier (binary frames only). */
object WebSocketLayer {

    fun handshake(
        input: InputStream,
        output: OutputStream,
        host: String,
        path: String,
        port: Int,
        tls: Boolean
    ): Pair<InputStream, OutputStream> {
        val keyBytes = ByteArray(16)
        SecureRandom().nextBytes(keyBytes)
        val key = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)

        val defaultPort = if (tls) 443 else 80
        val hostHeader = if (port == defaultPort) host else "$host:$port"

        val req = buildString {
            append("GET ").append(if (path.startsWith("/")) path else "/$path").append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36\r\n")
            append("\r\n")
        }
        output.writeAndFlush(req.toByteArray(Charsets.UTF_8))

        val statusLine = readHeaders(input)
        if (!statusLine.contains(" 101")) {
            throw java.io.IOException("WebSocket upgrade rejected: $statusLine")
        }
        return Pair(WsInputStream(input), WsOutputStream(output))
    }

    fun readHeaders(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("connection closed during upgrade")
            buffer.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && state == 1 -> 2
                b == '\n'.code && state == 3 -> 4
                else -> 0
            }
            if (state == 4) break
            if (buffer.size() > 16384) throw java.io.IOException("upgrade header too large")
        }
        return buffer.toString("UTF-8").lineSequence().firstOrNull() ?: ""
    }
}

private class WsInputStream(private val raw: InputStream) : InputStream() {
    private var remaining = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (remaining == 0L) {
            if (!nextFrame()) return -1
        }
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = raw.read(b, off, toRead)
        if (n <= 0) return -1
        remaining -= n
        return n
    }

    /** Returns false on close frame / EOF. */
    private fun nextFrame(): Boolean {
        val b0 = raw.read()
        if (b0 < 0) return false
        val opcode = b0 and 0x0F
        val b1 = raw.read()
        if (b1 < 0) return false
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            val ext = raw.readFully(2)
            length = ((ext[0].toLong() and 0xFF) shl 8) or (ext[1].toLong() and 0xFF)
        } else if (length == 127L) {
            val ext = raw.readFully(8)
            length = 0
            for (i in 0 until 8) length = (length shl 8) or (ext[i].toLong() and 0xFF)
        }
        if (masked) raw.readFully(4) // servers must not mask, tolerate anyway

        return when (opcode) {
            0x8 -> false // close
            0x9, 0xA -> { // ping/pong: skip payload
                if (length > 0) raw.readFully(length.toInt())
                true.also { remaining = 0 }
            }
            else -> {
                remaining = length
                if (remaining == 0L) nextFrame() else true
            }
        }
    }
}

private class WsOutputStream(private val raw: OutputStream) : OutputStream() {
    private val random = SecureRandom()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val header = ByteArrayOutputStream()
        header.write(0x82) // FIN + binary
        val maskBit = 0x80
        when {
            len < 126 -> header.write(maskBit or len)
            len <= 0xFFFF -> {
                header.write(maskBit or 126)
                header.write((len shr 8) and 0xFF)
                header.write(len and 0xFF)
            }
            else -> {
                header.write(maskBit or 127)
                for (i in 7 downTo 0) header.write(((len.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4)
        random.nextBytes(mask)
        header.write(mask)

        val payload = ByteArray(len)
        for (i in 0 until len) {
            payload[i] = (b[off + i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        synchronized(raw) {
            raw.write(header.toByteArray())
            raw.write(payload)
            raw.flush()
        }
    }

    override fun flush() = raw.flush()
}

/** Simple HTTP Upgrade transport (sing-box "httpupgrade"). */
object HttpUpgradeLayer {
    fun handshake(input: InputStream, output: OutputStream, host: String, path: String) {
        val req = buildString {
            append("GET ").append(if (path.startsWith("/")) path else "/$path").append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Connection: Upgrade\r\n")
            append("Upgrade: websocket\r\n")
            append("\r\n")
        }
        output.writeAndFlush(req.toByteArray(Charsets.UTF_8))
        val status = WebSocketLayer.readHeaders(input)
        if (!status.contains(" 101")) throw java.io.IOException("HTTPUpgrade rejected: $status")
    }
}
