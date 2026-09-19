package com.example.core.proxy

import android.net.VpnService
import com.example.data.entity.ServerEntity
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real VMess (AEAD header, AES-128-GCM body, ChunkStream without masking/padding)
 * over TCP / TLS / WebSocket / HTTPUpgrade.
 */
class VmessOutbound(
    private val server: ServerEntity,
    private val vpnService: VpnService?
) : Outbound {

    override val label: String = "VMess ${server.server}:${server.port}"

    override fun connect(destHost: String, destPort: Int, timeoutMs: Int): ProxyConnection {
        val conn = StreamTransport.open(server, vpnService, timeoutMs)
        try {
            val uuid = AddressCodec.parseUuid(server.uuid)
            val cmdKey = MessageDigest.getInstance("MD5").digest(
                uuid + "c48619fe-8f02-49e0-b9e9-edf763e17e21".toByteArray(Charsets.UTF_8)
            )

            val random = SecureRandom()
            val bodyKey = ByteArray(16).also { random.nextBytes(it) }
            val bodyIv = ByteArray(16).also { random.nextBytes(it) }
            val responseAuth = ByteArray(1).also { random.nextBytes(it) }

            val header = ByteArrayOutputStream()
            header.write(0x01) // version
            header.write(bodyIv)
            header.write(bodyKey)
            header.write(responseAuth[0].toInt())
            header.write(0x01) // option: ChunkStream only (no masking / no padding)
            header.write(0x03) // padding=0, security=3 (AES-128-GCM)
            header.write(0x00) // reserved
            header.write(0x01) // command TCP
            header.write(AddressCodec.encodeVStyle(destHost, destPort))

            val headerBytes = header.toByteArray()
            val fnv = fnv1a32(headerBytes)
            val fullHeader = headerBytes + byteArrayOf(
                ((fnv shr 24) and 0xFF).toByte(),
                ((fnv shr 16) and 0xFF).toByte(),
                ((fnv shr 8) and 0xFF).toByte(),
                (fnv and 0xFF).toByte()
            )

            conn.output.writeAndFlush(sealAeadHeader(cmdKey, fullHeader))

            val respKey = MessageDigest.getInstance("SHA-256").digest(bodyKey).copyOf(16)
            val respIv = MessageDigest.getInstance("SHA-256").digest(bodyIv).copyOf(16)

            val out = VmessBodyWriter(conn.output, bodyKey, bodyIv)
            val inp = VmessBodyReader(conn.input, respKey, respIv, responseAuth[0])
            return ProxyConnection(conn.socket, inp, out)
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }

    private fun sealAeadHeader(cmdKey: ByteArray, header: ByteArray): ByteArray {
        val random = SecureRandom()
        val authId = generateAuthId(cmdKey, random)
        val nonce = ByteArray(8).also { random.nextBytes(it) }

        val lengthKey = kdf16(cmdKey, "VMess Header AEAD Key_Length", authId, nonce)
        val lengthNonce = kdf(cmdKey, "VMess Header AEAD Nonce_Length", authId, nonce).copyOf(12)
        val lengthPlain = byteArrayOf(((header.size shr 8) and 0xFF).toByte(), (header.size and 0xFF).toByte())
        val sealedLength = aesGcmSeal(lengthKey, lengthNonce, lengthPlain, authId)

        val payloadKey = kdf16(cmdKey, "VMess Header AEAD Key", authId, nonce)
        val payloadNonce = kdf(cmdKey, "VMess Header AEAD Nonce", authId, nonce).copyOf(12)
        val sealedPayload = aesGcmSeal(payloadKey, payloadNonce, header, authId)

        val out = ByteArrayOutputStream()
        out.write(authId)
        out.write(sealedLength)
        out.write(nonce)
        out.write(sealedPayload)
        return out.toByteArray()
    }

    private fun generateAuthId(cmdKey: ByteArray, random: SecureRandom): ByteArray {
        val time = System.currentTimeMillis() / 1000
        val buf = ByteArrayOutputStream()
        for (i in 7 downTo 0) buf.write(((time shr (8 * i)) and 0xFF).toInt())
        val rand = ByteArray(4).also { random.nextBytes(it) }
        buf.write(rand)
        val partial = buf.toByteArray()
        val crc = CRC32().apply { update(partial) }.value
        val full = partial + byteArrayOf(
            ((crc shr 24) and 0xFF).toByte(),
            ((crc shr 16) and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte()
        )
        val key = kdf16(cmdKey, "AES Auth ID Encryption")
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(full)
    }

    companion object {
        fun fnv1a32(data: ByteArray): Int {
            var hash = -0x7ee3623b // 2166136261
            for (b in data) {
                hash = hash xor (b.toInt() and 0xFF)
                hash *= 16777619
            }
            return hash
        }

        /** VMess AEAD KDF: recursive HMAC-SHA256 chain seeded with "VMess AEAD KDF". */
        fun kdf(key: ByteArray, vararg path: Any): ByteArray {
            val paths = path.map {
                when (it) {
                    is String -> it.toByteArray(Charsets.UTF_8)
                    is ByteArray -> it
                    else -> it.toString().toByteArray(Charsets.UTF_8)
                }
            }
            var currentKey = "VMess AEAD KDF".toByteArray(Charsets.UTF_8)
            for (p in paths) {
                val m = Mac.getInstance("HmacSHA256")
                m.init(SecretKeySpec(currentKey, "HmacSHA256"))
                currentKey = m.doFinal(p)
            }
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(currentKey, "HmacSHA256"))
            return mac.doFinal(key)
        }

        fun kdf16(key: ByteArray, vararg path: Any): ByteArray = kdf(key, *path).copyOf(16)

        fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray?): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            if (aad != null) cipher.updateAAD(aad)
            return cipher.doFinal(plain)
        }

        fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray?): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            if (aad != null) cipher.updateAAD(aad)
            return cipher.doFinal(sealed)
        }
    }
}

private class VmessBodyWriter(
    private val raw: OutputStream,
    private val key: ByteArray,
    private val iv: ByteArray
) : OutputStream() {
    private var count = 0
    private val maxChunk = 16384 - 16

    private fun nonce(): ByteArray {
        val n = ByteArray(12)
        n[0] = ((count shr 8) and 0xFF).toByte()
        n[1] = (count and 0xFF).toByte()
        System.arraycopy(iv, 2, n, 2, 10)
        return n
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var pos = off
        var remaining = len
        synchronized(raw) {
            while (remaining > 0) {
                val chunk = minOf(remaining, maxChunk)
                val sealed = VmessOutbound.aesGcmSeal(key, nonce(), b.copyOfRange(pos, pos + chunk), null)
                count = (count + 1) and 0xFFFF
                raw.write(byteArrayOf(((sealed.size shr 8) and 0xFF).toByte(), (sealed.size and 0xFF).toByte()))
                raw.write(sealed)
                pos += chunk
                remaining -= chunk
            }
            raw.flush()
        }
    }

    override fun flush() = raw.flush()
}

private class VmessBodyReader(
    private val raw: InputStream,
    private val respKey: ByteArray,
    private val respIv: ByteArray,
    private val expectedAuth: Byte
) : InputStream() {
    private var headerRead = false
    private var count = 0
    private var buffer = ByteArray(0)
    private var pos = 0

    private fun nonce(): ByteArray {
        val n = ByteArray(12)
        n[0] = ((count shr 8) and 0xFF).toByte()
        n[1] = (count and 0xFF).toByte()
        System.arraycopy(respIv, 2, n, 2, 10)
        return n
    }

    private fun readResponseHeader() {
        if (headerRead) return
        headerRead = true
        val lenKey = VmessOutbound.kdf16(respKey, "AEAD Resp Header Len Key")
        val lenNonce = VmessOutbound.kdf(respIv, "AEAD Resp Header Len IV").copyOf(12)
        val sealedLen = raw.readFully(2 + 16)
        val lenPlain = VmessOutbound.aesGcmOpen(lenKey, lenNonce, sealedLen, null)
        val length = ((lenPlain[0].toInt() and 0xFF) shl 8) or (lenPlain[1].toInt() and 0xFF)

        val hKey = VmessOutbound.kdf16(respKey, "AEAD Resp Header Key")
        val hNonce = VmessOutbound.kdf(respIv, "AEAD Resp Header IV").copyOf(12)
        val sealedHeader = raw.readFully(length + 16)
        val headerPlain = VmessOutbound.aesGcmOpen(hKey, hNonce, sealedHeader, null)
        if (headerPlain.isEmpty() || headerPlain[0] != expectedAuth) {
            throw java.io.IOException("VMess response auth mismatch (wrong UUID or server rejected the request)")
        }
    }

    private fun fill(): Boolean {
        readResponseHeader()
        if (pos < buffer.size) return true
        val lenBytes = try { raw.readFully(2) } catch (_: Exception) { return false }
        val size = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
        if (size <= 16) return false
        val sealed = raw.readFully(size)
        buffer = VmessOutbound.aesGcmOpen(respKey, nonce(), sealed, null)
        count = (count + 1) and 0xFFFF
        pos = 0
        return buffer.isNotEmpty()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!fill()) return -1
        val n = minOf(len, buffer.size - pos)
        System.arraycopy(buffer, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() = raw.close()
}
