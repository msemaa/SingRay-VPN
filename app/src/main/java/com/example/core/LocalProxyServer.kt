package com.example.core

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * High-performance lightweight SOCKS5 Inbound proxy server (127.0.0.1:10808)
 * Built to follow Hiddify and v2rayNG inbound architecture.
 * Directly integrates with Telegram, browsers, and Android apps.
 */
class LocalProxyServer(
    private val vpnService: VpnService?,
    private val port: Int = 10808,
    private val onTraffic: (rx: Long, tx: Long) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        stop()
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                Log.i("LocalProxyServer", "Hiddify-compatible SOCKS5 inbound listening on 127.0.0.1:$port")

                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        launch(Dispatchers.IO) {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                Log.e("LocalProxyServer", "Failed to start SOCKS5 inbound: ${e.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // 1. SOCKS5 Handshake
            val version = input.read()
            if (version != 5) {
                client.close()
                return
            }
            val numMethods = input.read()
            val methods = ByteArray(numMethods.coerceAtLeast(1))
            input.read(methods)

            // Reply: Version 5, Method: No Auth (0x00)
            output.write(byteArrayOf(5, 0))
            output.flush()

            // 2. Request Details
            val reqVer = input.read()
            val cmd = input.read() // 1 = CONNECT
            input.read() // RSV
            val atyp = input.read() // 1 = IPv4, 3 = Domain, 4 = IPv6

            if (cmd != 1) {
                // Command not supported
                output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            var targetHost = ""
            when (atyp) {
                1 -> { // IPv4
                    val ip = ByteArray(4)
                    input.read(ip)
                    targetHost = InetAddress.getByAddress(ip).hostAddress ?: ""
                }
                3 -> { // Domain name
                    val len = input.read()
                    val domainBytes = ByteArray(len)
                    input.read(domainBytes)
                    targetHost = String(domainBytes)
                }
                4 -> { // IPv6
                    val ip6 = ByteArray(16)
                    input.read(ip6)
                    targetHost = InetAddress.getByAddress(ip6).hostAddress ?: ""
                }
            }

            val p1 = input.read()
            val p2 = input.read()
            val targetPort = (p1 shl 8) or p2

            // Connect to target (with VPN protect so it doesn't loop)
            val remoteSocket = Socket()
            vpnService?.protect(remoteSocket)
            remoteSocket.soTimeout = 30000
            remoteSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)

            // Send SOCKS5 success reply
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()

            // Bi-directional data pipe
            relayTraffic(client, remoteSocket)
        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun relayTraffic(client: Socket, remote: Socket) {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()

        val job1 = scope.launch(Dispatchers.IO) {
            pipeStream(clientIn, remoteOut) { bytes -> onTraffic(0, bytes) }
        }

        val job2 = scope.launch(Dispatchers.IO) {
            pipeStream(remoteIn, clientOut) { bytes -> onTraffic(bytes, 0) }
        }

        scope.launch(Dispatchers.IO) {
            job1.join()
            job2.join()
            try { client.close() } catch (_: Exception) {}
            try { remote.close() } catch (_: Exception) {}
        }
    }

    private fun pipeStream(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(16384)
        try {
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                output.flush()
                onBytes(read.toLong())
            }
        } catch (_: Exception) {
        } finally {
            try { output.flush() } catch (_: Exception) {}
        }
    }

    fun stop() {
        try {
            serverJob?.cancel()
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob = null
    }
}
