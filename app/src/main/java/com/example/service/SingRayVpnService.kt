package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import android.os.ParcelFileDescriptor
import com.example.core.LocalProxyServer
import com.example.core.engine.CoreManager
import com.example.core.engine.CoreType
import com.example.core.engine.SingBoxEngine
import com.example.core.proxy.ConnectivityTester
import com.example.core.proxy.Outbound
import com.example.core.proxy.OutboundFactory
import com.example.core.proxy.UnsupportedConfigException
import com.example.data.SingRayDatabase
import com.example.data.entity.ServerEntity
import com.example.model.ConnectionStatus
import com.example.model.CoreLog
import com.example.model.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground proxy service.
 *
 * It runs a real protocol core (native sing-box / Xray, or the built-in Kotlin
 * core) behind a local SOCKS5 + HTTP inbound on 127.0.0.1:10808 / :10809 and
 * only reports CONNECTED after real data has travelled through the tunnel.
 *
 * Switching profiles is a full restart: every connect() attempt first tears the
 * previous session down completely (core, TUN, inbounds, stats) and waits for
 * the local ports to be released. Without that, the second connection inherits
 * the first one's outbound and dies with "stream closed after 0/1 bytes".
 */
class SingRayVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var statsJob: Job? = null
    private var connectJob: Job? = null
    private var socksServer: LocalProxyServer? = null
    private var httpServer: LocalProxyServer? = null
    private val proxyRx = AtomicLong(0)
    private val proxyTx = AtomicLong(0)

    @Volatile
    private var activeOutbound: Outbound? = null

    private var tunInterface: ParcelFileDescriptor? = null
    private var corePreference: CoreType = CoreType.AUTO

    companion object {
        const val ACTION_CONNECT = "com.example.singray.CONNECT"
        const val ACTION_DISCONNECT = "com.example.singray.DISCONNECT"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_SERVER_HOST = "extra_server_host"
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_ROUTING_MODE = "extra_routing_mode"
        const val EXTRA_BATTERY_SAVER = "extra_battery_saver"
        const val EXTRA_CORE_TYPE = "extra_core_type"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "singray_core_vpn_channel"

        const val SOCKS_PORT = 10808
        const val HTTP_PORT = 10809

        private val _isBatterySaverActive = MutableStateFlow(true)
        val isBatterySaverActive: StateFlow<Boolean> = _isBatterySaverActive.asStateFlow()

        private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

        private val _activeServerInfo = MutableStateFlow<String?>("Not Connected")
        val activeServerInfo: StateFlow<String?> = _activeServerInfo.asStateFlow()

        private val _trafficStats = MutableStateFlow(TrafficStats())
        val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

        private val _coreLogs = MutableStateFlow<List<CoreLog>>(emptyList())
        val coreLogs: StateFlow<List<CoreLog>> = _coreLogs.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        /** Which core is actually carrying traffic right now. */
        private val _activeCore = MutableStateFlow(CoreType.BUILT_IN)
        val activeCore: StateFlow<CoreType> = _activeCore.asStateFlow()

        fun log(level: String, tag: String, message: String) {
            val current = _coreLogs.value.takeLast(299).toMutableList()
            current.add(CoreLog(level = level, tag = tag, message = message))
            _coreLogs.value = current
        }

        fun clearLogs() {
            _coreLogs.value = emptyList()
        }

        fun clearError() {
            _lastError.value = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                val batterySaver = intent.getBooleanExtra(EXTRA_BATTERY_SAVER, true)
                val routing = intent.getStringExtra(EXTRA_ROUTING_MODE) ?: "Rule"
                val preference = runCatching {
                    CoreType.valueOf(intent.getStringExtra(EXTRA_CORE_TYPE) ?: CoreType.AUTO.name)
                }.getOrDefault(CoreType.AUTO)
                _isBatterySaverActive.value = batterySaver
                corePreference = preference
                connect(serverId, routing, batterySaver)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun connect(serverId: Long, routingMode: String, batterySaver: Boolean) {
        // ---------------------------------------------------------------
        // Hard reset of any previous session. This is what makes profile
        // switching work: without it the old core keeps the TUN and the old
        // inbounds keep 10808/10809, so the new profile never gets used.
        // ---------------------------------------------------------------
        connectJob?.cancel()
        val hadPreviousSession = _connectionStatus.value != ConnectionStatus.DISCONNECTED ||
            socksServer != null || httpServer != null || CoreManager.active != null
        if (hadPreviousSession) {
            log("INFO", "CORE", "Switching profile: stopping the previous session first")
        }
        stopEverything()

        _lastError.value = null
        _activeCore.value = CoreType.BUILT_IN
        _trafficStats.value = TrafficStats()
        _connectionStatus.value = ConnectionStatus.CONNECTING

        val initial = buildNotification("Connecting...", "Starting core")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }

        connectJob = serviceScope.launch {
            if (hadPreviousSession) {
                // Give the OS a moment to release the listening sockets.
                awaitPortsFree(SOCKS_PORT, HTTP_PORT)
            }

            val dao = SingRayDatabase.getDatabase(applicationContext).serverDao()
            val server: ServerEntity? =
                if (serverId > 0) dao.getServerById(serverId) else dao.getSelectedServerSync()

            if (server == null) {
                fail("No server selected. Add or select a config first.")
                return@launch
            }

            _activeServerInfo.value = "[${server.protocol.uppercase()}] ${server.name}"
            log("INFO", "CORE", "Starting core for ${server.name} (${server.protocol}/${server.network}/${server.security})")

            // ---------------------------------------------------------------
            // 0. Native cores first: sing-box / Xray, auto-selected per config.
            // ---------------------------------------------------------------
            val (chosen, pickReason) = CoreManager.pickCoreWithReason(server, corePreference)
            log("INFO", "CORE", "Target core: ${chosen.title} ($pickReason)")
            CoreManager.unavailableReasons().forEach { (core, why) ->
                log("WARN", "CORE", "${core.title} is not usable in this build: $why")
            }
            var nativeFailureReason: String? = null
            if (chosen == CoreType.SING_BOX || chosen == CoreType.XRAY) {
                // sing-box can own the TUN itself; Xray works behind the local proxy.
                val fd: Int? = if (chosen == CoreType.SING_BOX && SingBoxEngine.isAvailable()) {
                    establishTun()
                } else null

                val started = CoreManager.start(
                    context = applicationContext,
                    vpnService = this@SingRayVpnService,
                    server = server,
                    preference = corePreference,
                    routingMode = when (routingMode.lowercase()) {
                        "global proxy", "global" -> com.example.model.RoutingMode.GLOBAL
                        "direct bypass", "direct" -> com.example.model.RoutingMode.DIRECT
                        else -> com.example.model.RoutingMode.RULE
                    },
                    bypassLan = true,
                    bypassDomestic = true,
                    dnsServer = "1.1.1.1",
                    socksPort = SOCKS_PORT,
                    httpPort = HTTP_PORT,
                    tunFd = fd
                )

                if (started.success) {
                    _activeCore.value = started.core
                    log("INFO", "CORE", "${started.core.title} is now handling traffic")

                    // Verify through the core's own SOCKS inbound.
                    val ok = ConnectivityTester.socksProbe("127.0.0.1", SOCKS_PORT)
                    if (!ok.success) {
                        fail("${started.core.title} started, but no traffic passed: ${ok.message} [Core: $pickReason]")
                        return@launch
                    }
                    log("INFO", "TEST", "Tunnel verified through ${started.core.title} in ${ok.latencyMs} ms")
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    _trafficStats.value = TrafficStats(currentLatencyMs = ok.latencyMs)
                    startTrafficMonitoring(server.name, batterySaver, ok.latencyMs)
                    return@launch
                }

                closeTun()
                try { CoreManager.stop() } catch (_: Exception) {}
                nativeFailureReason = started.message
                log("WARN", "CORE", started.message)
            }

            _activeCore.value = CoreType.BUILT_IN
            log("INFO", "CORE", "Falling back to the built-in Kotlin core")

            // 1. Build the real outbound. Unsupported configs fail loudly here.
            val outbound = try {
                OutboundFactory.create(server, this@SingRayVpnService)
            } catch (e: UnsupportedConfigException) {
                val prefix = if (nativeFailureReason != null) "$nativeFailureReason. " else ""
                fail("${prefix}Built-in core does not support ${server.protocol.uppercase()}${if (server.security.isNotBlank()) "/${server.security}" else ""}: ${e.message} [Core: $pickReason]")
                return@launch
            } catch (e: Exception) {
                fail("Failed to build outbound: ${e.message} [Core: $pickReason]")
                return@launch
            }
            activeOutbound = outbound

            // 2. Verify the tunnel really works before claiming CONNECTED.
            log("INFO", "TEST", "Verifying tunnel with a real HTTP request...")
            val probe = ConnectivityTester.realDelay(server, this@SingRayVpnService)
            if (!probe.success) {
                val prefix = if (nativeFailureReason != null) "$nativeFailureReason. " else ""
                fail("${prefix}Tunnel handshake failed: ${probe.message} [Core: $pickReason]")
                return@launch
            }
            log("INFO", "TEST", "Tunnel verified in ${probe.latencyMs} ms -> ${probe.message}")

            // 3. Start local inbounds that actually forward through the outbound.
            try {
                socksServer?.stop()
                httpServer?.stop()
                socksServer = LocalProxyServer(
                    vpnService = this@SingRayVpnService,
                    port = SOCKS_PORT,
                    outboundProvider = { activeOutbound ?: outbound },
                    onTraffic = { rx, tx -> proxyRx.addAndGet(rx); proxyTx.addAndGet(tx) },
                    onError = { msg -> log("WARN", "INBOUND", msg) }
                ).also { it.start() }

                httpServer = LocalProxyServer(
                    vpnService = this@SingRayVpnService,
                    port = HTTP_PORT,
                    outboundProvider = { activeOutbound ?: outbound },
                    onTraffic = { rx, tx -> proxyRx.addAndGet(rx); proxyTx.addAndGet(tx) },
                    onError = { msg -> log("WARN", "INBOUND", msg) }
                ).also { it.start() }
            } catch (e: Exception) {
                fail("Could not start local inbound: ${e.message}")
                return@launch
            }

            log("INFO", "INBOUND", "SOCKS5 ready on 127.0.0.1:$SOCKS_PORT")
            log("INFO", "INBOUND", "HTTP proxy ready on 127.0.0.1:$HTTP_PORT")
            log("INFO", "ROUTE", "Routing mode: $routingMode")

            _connectionStatus.value = ConnectionStatus.CONNECTED
            _trafficStats.value = TrafficStats(currentLatencyMs = probe.latencyMs)
            startTrafficMonitoring(server.name, batterySaver, probe.latencyMs)
        }
    }

    /** Waits (max ~1.5 s) until the local inbound ports can be bound again. */
    private suspend fun awaitPortsFree(vararg ports: Int) {
        repeat(15) {
            if (ports.all { port -> isPortFree(port) }) return
            delay(100)
        }
        log("WARN", "INBOUND", "Local ports were still busy after the previous session; continuing anyway")
    }

    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun fail(message: String) {
        log("ERROR", "CORE", message)
        _lastError.value = message
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _activeServerInfo.value = "Not Connected"
        stopEverything()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTrafficMonitoring(serverName: String, batterySaver: Boolean, latency: Long) {
        statsJob?.cancel()
        var totalRx = 0L
        var totalTx = 0L
        var seconds = 0L
        val intervalMs = if (batterySaver) 2000L else 1000L

        statsJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                delay(intervalMs)
                seconds += intervalMs / 1000

                // Only count bytes that really passed through our tunnel.
                val deltaRx = proxyRx.getAndSet(0L)
                val deltaTx = proxyTx.getAndSet(0L)
                totalRx += deltaRx
                totalTx += deltaTx

                _trafficStats.value = TrafficStats(
                    uploadSpeedBytes = (deltaTx * 1000) / intervalMs,
                    downloadSpeedBytes = (deltaRx * 1000) / intervalMs,
                    totalUploadBytes = totalTx,
                    totalDownloadBytes = totalRx,
                    durationSeconds = seconds,
                    currentLatencyMs = latency
                )

                val text = "D " + formatSpeed((deltaRx * 1000) / intervalMs) +
                    "  U " + formatSpeed((deltaTx * 1000) / intervalMs) + " - " + serverName
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification("SingRay: $serverName", text))
            }
        }
    }

    /** Creates the TUN interface handed to sing-box (which owns routing). */
    private fun establishTun(): Int? = try {
        closeTun()
        val builder = Builder()
            .setSession("SingRay")
            .setMtu(9000)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setBlocking(false)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }
        val pfd = builder.establish()
        tunInterface = pfd
        pfd?.fd
    } catch (e: Exception) {
        log("WARN", "TUN", "Could not establish TUN: ${e.message}")
        null
    }

    private fun closeTun() {
        try { tunInterface?.close() } catch (_: Exception) {}
        tunInterface = null
    }

    private fun stopEverything() {
        statsJob?.cancel()
        statsJob = null
        try { CoreManager.stop() } catch (_: Exception) {}
        closeTun()
        try { socksServer?.stop() } catch (_: Exception) {}
        try { httpServer?.stop() } catch (_: Exception) {}
        socksServer = null
        httpServer = null
        try { activeOutbound?.close() } catch (_: Throwable) {}
        activeOutbound = null
        proxyRx.set(0)
        proxyTx.set(0)
    }

    private fun disconnect() {
        _connectionStatus.value = ConnectionStatus.DISCONNECTING
        log("INFO", "CORE", "Stopping core...")
        connectJob?.cancel()
        connectJob = null
        stopEverything()
        _trafficStats.value = TrafficStats()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _activeServerInfo.value = "Not Connected"
        log("INFO", "CORE", "Core stopped.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        connectJob?.cancel()
        stopEverything()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SingRay Core Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Connection status and throughput"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectIntent = Intent(this, SingRayVpnService::class.java).apply { action = ACTION_DISCONNECT }
        val pendingDisconnect = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingLaunch)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", pendingDisconnect)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val df = DecimalFormat("#.#")
        return when {
            bytesPerSec >= 1_000_000 -> df.format(bytesPerSec / 1_000_000.0) + " MB/s"
            bytesPerSec >= 1_000 -> df.format(bytesPerSec / 1_000.0) + " KB/s"
            else -> "$bytesPerSec B/s"
        }
    }
}
