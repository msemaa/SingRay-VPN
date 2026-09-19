package com.example.core.proxy

import android.net.VpnService
import com.example.data.entity.ServerEntity
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real Shadowsocks AEAD client (aes-128-gcm, aes-192-gcm, aes-256-gcm,
 * chacha20-ietf-poly1305 when the platform provides it).
 */
class ShadowsocksOutbound(
    private val server: ServerEntity,
    private val vpnService: VpnService?
) : Outbound {

    override val label: String = "Shadowsocks ${server.server}:${server.port}"

    override fun connect(destHost: String, destPort: Int, timeoutMs: Int): ProxyConnection {
        val method = server.fingerprint.trim().lowercase() // cipher stored here by the parser
        val spec = CipherSpec.of(method)

        val plainServer = server.copy(security = "none", network = "tcp")
        val conn = StreamTransport.open(plainServer, vpnService, timeoutMs)
        try {
            val masterKey = evpBytesToKey(server.uuid, spec.keySize)

            val salt = ByteArray(spec.keySize)
            SecureRandom().nextBytes(salt)
            val subKey = hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(), spec.keySize)

            conn.output.writeAndFlush(salt)

            val encryptor = AeadWriter(conn.output, subKey, spec)
            encryptor.write(AddressCodec.encodeSocksStyle(destHost, destPort))
            encryptor.flush()

            val decryptor = LazyAeadReader(conn.input, masterKey, spec)
            return ProxyConnection(conn.socket, decryptor, encryptor)
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }

    companion object {
        fun evpBytesToKey(password: String, keyLen: Int): ByteArray {
            val pw = password.toByteArray(Charsets.UTF_8)
            val md5 = MessageDigest.getInstance("MD5")
            val out = ByteArray(keyLen)
            var generated = 0
            var prev = ByteArray(0)
            while (generated < keyLen) {
                md5.reset()
                md5.update(prev)
                md5.update(pw)
                prev = md5.digest()
                val copy = minOf(prev.size, keyLen - generated)
                System.arraycopy(prev, 0, out, generated, copy)
                generated += copy
            }
            return out
        }

        fun hkdfSha1(key: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(salt, "HmacSHA1"))
            val prk = mac.doFinal(key)

            val result = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var counter = 1
            while (pos < length) {
                mac.reset()
                mac.init(SecretKeySpec(prk, "HmacSHA1"))
                mac.update(t)
                mac.update(info)
                mac.update(counter.toByte())
                t = mac.doFinal()
                val copy = minOf(t.size, length - pos)
                System.arraycopy(t, 0, result, pos, copy)
                pos += copy
                counter++
            }
            return result
        }
    }
}

data class CipherSpec(val keySize: Int, val jcaName: String, val isChaCha: Boolean) {
    companion object {
        fun of(method: String): CipherSpec = when (method) {
            "aes-128-gcm" -> CipherSpec(16, "AES/GCM/NoPadding", false)
            "aes-192-gcm" -> CipherSpec(24, "AES/GCM/NoPadding", false)
            "aes-256-gcm", "" -> CipherSpec(32, "AES/GCM/NoPadding", false)
            "chacha20-ietf-poly1305", "chacha20-poly1305" -> CipherSpec(32, "ChaCha20-Poly1305", true)
            else -> throw UnsupportedConfigException(
                "Shadowsocks cipher '$method' is not supported by the built-in core (AEAD GCM / chacha20-ietf-poly1305 only)."
            )
        }
    }
}

/** Shared AEAD chunk helpers (2-byte length chunk + payload chunk, LE nonce counter). */
internal object Aead {
    const val TAG_SIZE = 16

    fun seal(spec: CipherSpec, key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(spec.jcaName)
        val keySpec = SecretKeySpec(key, if (spec.isChaCha) "ChaCha20" else "AES")
        if (spec.isChaCha) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, javax.crypto.spec.IvParameterSpec(nonce))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE * 8, nonce))
        }
        return cipher.doFinal(plain)
    }

    fun open(spec: CipherSpec, key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(spec.jcaName)
        val keySpec = SecretKeySpec(key, if (spec.isChaCha) "ChaCha20" else "AES")
        if (spec.isChaCha) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.IvParameterSpec(nonce))
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE * 8, nonce))
        }
        return cipher.doFinal(sealed)
    }

    fun increment(nonce: ByteArray) {
        for (i in nonce.indices) {
            val v = (nonce[i].toInt() and 0xFF) + 1
            nonce[i] = v.toByte()
            if (v <= 0xFF) break
        }
    }
}

private class AeadWriter(
    private val raw: OutputStream,
    private val key: ByteArray,
    private val spec: CipherSpec
) : OutputStream() {
    private val nonce = ByteArray(12)
    private val maxChunk = 0x3FFF

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var pos = off
        var remaining = len
        synchronized(raw) {
            while (remaining > 0) {
                val chunk = minOf(remaining, maxChunk)
                val lenBytes = byteArrayOf(((chunk shr 8) and 0xFF).toByte(), (chunk and 0xFF).toByte())
                raw.write(Aead.seal(spec, key, nonce, lenBytes))
                Aead.increment(nonce)
                raw.write(Aead.seal(spec, key, nonce, b.copyOfRange(pos, pos + chunk)))
                Aead.increment(nonce)
                pos += chunk
                remaining -= chunk
            }
            raw.flush()
        }
    }

    override fun flush() = raw.flush()
}

/** Reads the server salt lazily, then decrypts AEAD chunks. */
private class LazyAeadReader(
    private val raw: InputStream,
    private val masterKey: ByteArray,
    private val spec: CipherSpec
) : InputStream() {
    private var key: ByteArray? = null
    private val nonce = ByteArray(12)
    private var buffer: ByteArray = ByteArray(0)
    private var bufferPos = 0

    private fun ensureKey() {
        if (key != null) return
        val salt = raw.readFully(spec.keySize)
        key = ShadowsocksOutbound.hkdfSha1(masterKey, salt, "ss-subkey".toByteArray(), spec.keySize)
    }

    private fun fill(): Boolean {
        ensureKey()
        if (bufferPos < buffer.size) return true
        val k = key ?: return false
        val lenSealed = try { raw.readFully(2 + Aead.TAG_SIZE) } catch (_: Exception) { return false }
        val lenPlain = Aead.open(spec, k, nonce, lenSealed)
        Aead.increment(nonce)
        val length = ((lenPlain[0].toInt() and 0xFF) shl 8) or (lenPlain[1].toInt() and 0xFF)
        if (length == 0) return false
        val payloadSealed = raw.readFully(length + Aead.TAG_SIZE)
        buffer = Aead.open(spec, k, nonce, payloadSealed)
        Aead.increment(nonce)
        bufferPos = 0
        return buffer.isNotEmpty()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!fill()) return -1
        val n = minOf(len, buffer.size - bufferPos)
        System.arraycopy(buffer, bufferPos, b, off, n)
        bufferPos += n
        return n
    }

    override fun close() = raw.close()
}
