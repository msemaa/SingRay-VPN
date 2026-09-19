package com.example.core.proxy

import android.net.VpnService
import com.example.data.entity.ServerEntity
import java.io.ByteArrayOutputStream

/**
 * Real VLESS client (TCP command) over TCP / TLS / WebSocket / HTTPUpgrade.
 *
 * Request:  ver(1) | uuid(16) | addonLen(1) | cmd(1) | port(2) | atyp(1) | addr
 * Response: ver(1) | addonLen(1) | addon(addonLen)
 */
class VlessOutbound(
    private val server: ServerEntity,
    private val vpnService: VpnService?
) : Outbound {

    override val label: String = "VLESS ${server.server}:${server.port}"

    override fun connect(destHost: String, destPort: Int, timeoutMs: Int): ProxyConnection {
        val flow = server.rawUri.substringAfter("flow=", "").substringBefore("&")
        if (flow.contains("xtls-rprx", ignoreCase = true)) {
            throw UnsupportedConfigException("XTLS flow ($flow) requires the native core.")
        }

        val conn = StreamTransport.open(server, vpnService, timeoutMs)
        try {
            val header = ByteArrayOutputStream()
            header.write(0x00) // version
            header.write(AddressCodec.parseUuid(server.uuid))
            header.write(0x00) // no addons
            header.write(0x01) // TCP
            header.write(AddressCodec.encodeVStyle(destHost, destPort))
            conn.output.writeAndFlush(header.toByteArray())

            // Server response header is read lazily on first byte so that we do
            // not block here if the target speaks first.
            val wrappedInput = VlessResponseStream(conn.input)
            return ProxyConnection(conn.socket, wrappedInput, conn.output)
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }
}

private class VlessResponseStream(private val raw: java.io.InputStream) : java.io.InputStream() {
    private var headerConsumed = false

    private fun consumeHeader() {
        if (headerConsumed) return
        headerConsumed = true
        raw.readFully(1) // version
        val addonLen = raw.read()
        if (addonLen < 0) throw java.io.EOFException("VLESS response truncated")
        if (addonLen > 0) raw.readFully(addonLen)
    }

    override fun read(): Int {
        consumeHeader()
        return raw.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        consumeHeader()
        return raw.read(b, off, len)
    }

    override fun available(): Int = if (headerConsumed) raw.available() else 0
    override fun close() = raw.close()
}
