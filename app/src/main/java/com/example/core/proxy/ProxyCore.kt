package com.example.core.proxy

import android.net.VpnService
import com.example.data.entity.ServerEntity
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A live connection to a remote destination that is already tunnelled
 * through the selected proxy server.
 */
class ProxyConnection(
    val socket: Socket,
    val input: InputStream,
    val output: OutputStream
) : Closeable {
    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

/** Thrown when a config uses a feature this core cannot honestly support. */
class UnsupportedConfigException(message: String) : Exception(message)

/**
 * An outbound knows how to open a tunnelled connection to (host, port)
 * through one configured server.
 *
 * Outbounds are [Closeable] so the service can drop every resource of the
 * previous profile when the user switches config. The default implementation
 * is a no-op for stateless outbounds; stateful ones (session caches, QUIC or
 * multiplexed links) should override it.
 */
interface Outbound : Closeable {
    val label: String
    fun connect(destHost: String, destPort: Int, timeoutMs: Int = 15000): ProxyConnection

    override fun close() {
        // Stateless by default.
    }
}

object OutboundFactory {

    /**
     * Builds a real protocol outbound for the given server entity.
     * Never returns a "fake" outbound: if the protocol/transport cannot be
     * implemented, it throws so the UI can show a real error instead of a
     * green "connected" state that does nothing.
     */
    fun create(server: ServerEntity, vpnService: VpnService?): Outbound {
        val protocol = server.protocol.trim().lowercase()
        val security = server.security.trim().lowercase()

        if (security == "reality") {
            throw UnsupportedConfigException(
                "REALITY (pbk/sid) needs the native Xray or sing-box core, which is not usable in this build. Rebuild the app with the core libraries, or use a TLS config."
            )
        }

        return when (protocol) {
            "vless" -> VlessOutbound(server, vpnService)
            "vmess" -> VmessOutbound(server, vpnService)
            "trojan" -> TrojanOutbound(server, vpnService)
            "shadowsocks", "ss" -> ShadowsocksOutbound(server, vpnService)
            "hysteria2", "hy2", "hysteria" ->
                throw UnsupportedConfigException("Hysteria runs over QUIC and needs the native sing-box core.")
            "tuic" ->
                throw UnsupportedConfigException("TUIC runs over QUIC and needs the native sing-box core.")
            "anytls", "shadowtls" ->
                throw UnsupportedConfigException("${protocol.uppercase()} needs the native sing-box core.")
            "wireguard", "wg" ->
                throw UnsupportedConfigException("WireGuard needs the native sing-box core.")
            "ssh" ->
                throw UnsupportedConfigException("SSH tunnelling needs the native sing-box core.")
            else -> throw UnsupportedConfigException("Unknown protocol: ${server.protocol}")
        }
    }
}

/** SOCKS5-style address encoding shared by VLESS / Trojan / Shadowsocks. */
object AddressCodec {

    fun isIpv4(host: String): Boolean =
        Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)

    fun isIpv6(host: String): Boolean =
        host.contains(":") && !host.contains("/")

    fun ipv4Bytes(host: String): ByteArray =
        host.split(".").map { it.toInt().toByte() }.toByteArray()

    fun ipv6Bytes(host: String): ByteArray =
        java.net.InetAddress.getByName(host.removeSurrounding("[", "]")).address

    /** atyp values: 0x01 IPv4, 0x03 domain, 0x04 IPv6 (SOCKS5 / Trojan / SS). */
    fun encodeSocksStyle(host: String, port: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        when {
            isIpv4(host) -> {
                out.write(0x01)
                out.write(ipv4Bytes(host))
            }
            isIpv6(host) -> {
                out.write(0x04)
                out.write(ipv6Bytes(host))
            }
            else -> {
                val d = host.toByteArray(Charsets.UTF_8)
                out.write(0x03)
                out.write(d.size)
                out.write(d)
            }
        }
        out.write((port shr 8) and 0xFF)
        out.write(port and 0xFF)
        return out.toByteArray()
    }

    /** VLESS / VMess atyp values: 0x01 IPv4, 0x02 domain, 0x03 IPv6. */
    fun encodeVStyle(host: String, port: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write((port shr 8) and 0xFF)
        out.write(port and 0xFF)
        when {
            isIpv4(host) -> {
                out.write(0x01)
                out.write(ipv4Bytes(host))
            }
            isIpv6(host) -> {
                out.write(0x03)
                out.write(ipv6Bytes(host))
            }
            else -> {
                val d = host.toByteArray(Charsets.UTF_8)
                out.write(0x02)
                out.write(d.size)
                out.write(d)
            }
        }
        return out.toByteArray()
    }

    fun parseUuid(raw: String): ByteArray {
        val hex = raw.trim().replace("-", "")
        if (hex.length != 32) {
            // Non standard id: v2ray derives a UUID from an arbitrary string with MD5.
            val md5 = java.security.MessageDigest.getInstance("MD5")
            return md5.digest(raw.toByteArray(Charsets.UTF_8))
        }
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return bytes
    }
}

/** Utility to fully read n bytes or throw. */
fun InputStream.readFully(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r <= 0) throw java.io.EOFException("stream closed after $off/$n bytes")
        off += r
    }
    return buf
}

fun newProtectedSocket(vpnService: VpnService?, host: String, port: Int, timeoutMs: Int): Socket {
    val socket = Socket()
    // Must be protected BEFORE connect so the proxy link itself is not routed
    // back into our own tunnel (otherwise you get an instant routing loop:
    // "connected" but zero throughput).
    try { vpnService?.protect(socket) } catch (_: Exception) {}
    socket.tcpNoDelay = true
    socket.keepAlive = true
    socket.soTimeout = 0
    socket.connect(InetSocketAddress(host, port), timeoutMs)
    return socket
}

fun OutputStream.writeAndFlush(data: ByteArray) {
    write(data)
    flush()
}
