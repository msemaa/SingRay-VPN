package com.example.core.proxy

import android.net.VpnService
import com.example.data.entity.ServerEntity
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Real Trojan client.
 * Request: hex(SHA224(password))(56) | CRLF | cmd(1) | atyp+addr+port | CRLF | payload
 */
class TrojanOutbound(
    private val server: ServerEntity,
    private val vpnService: VpnService?
) : Outbound {

    override val label: String = "Trojan ${server.server}:${server.port}"

    override fun connect(destHost: String, destPort: Int, timeoutMs: Int): ProxyConnection {
        // Trojan is always TLS on the wire.
        val tlsServer = if (server.security.equals("tls", true)) server else server.copy(security = "tls")
        val conn = StreamTransport.open(tlsServer, vpnService, timeoutMs)
        try {
            val digest = MessageDigest.getInstance("SHA-224").digest(server.uuid.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }

            val header = ByteArrayOutputStream()
            header.write(hex.toByteArray(Charsets.US_ASCII))
            header.write(byteArrayOf(0x0D, 0x0A))
            header.write(0x01) // CONNECT
            header.write(AddressCodec.encodeSocksStyle(destHost, destPort))
            header.write(byteArrayOf(0x0D, 0x0A))
            conn.output.writeAndFlush(header.toByteArray())
            return conn
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }
}
