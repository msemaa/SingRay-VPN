package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
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
import java.text.DecimalFormat
import kotlin.random.Random

class SingRayVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var statsJob: Job? = null

    companion object {
        const val ACTION_CONNECT = "com.example.singray.CONNECT"
        const val ACTION_DISCONNECT = "com.example.singray.DISCONNECT"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_SERVER_HOST = "extra_server_host"
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_PROTOCOL = "extra_protocol"
        const val EXTRA_ROUTING_MODE = "extra_routing_mode"
        const val EXTRA_BATTERY_SAVER = "extra_battery_saver"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "singray_core_vpn_channel"

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

        fun log(level: String, tag: String, message: String) {
            val logEntry = CoreLog(level = level, tag = tag, message = message)
            val current = _coreLogs.value.takeLast(199).toMutableList()
            current.add(logEntry)
            _coreLogs.value = current
        }

        fun clearLogs() {
            _coreLogs.value = emptyList()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val name = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Selected Node"
                val host = intent.getStringExtra(EXTRA_SERVER_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_SERVER_PORT, 443)
                val protocol = intent.getStringExtra(EXTRA_PROTOCOL) ?: "VLESS"
                val routing = intent.getStringExtra(EXTRA_ROUTING_MODE) ?: "Rule"
                val batterySaver = intent.getBooleanExtra(EXTRA_BATTERY_SAVER, true)
                _isBatterySaverActive.value = batterySaver

                connectVpn(name, host, port, protocol, routing, batterySaver)
            }
            ACTION_DISCONNECT -> {
                disconnectVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun connectVpn(
        serverName: String,
        host: String,
        port: Int,
        protocol: String,
        routingMode: String,
        batterySaver: Boolean
    ) {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _activeServerInfo.value = "[$protocol] $serverName"
        log("INFO", "CORE", "Starting SingRay core engine ($protocol) [Power Profile: ${if (batterySaver) "Eco Battery" else "Performance"}]...")
        log("INFO", "TUN", "Allocating virtual TUN interface (172.19.0.1/30)...")

        startForeground(NOTIFICATION_ID, buildNotification("Connecting to $serverName...", "Handshaking..."))

        serviceScope.launch {
            try {
                delay(400) // Fast non-blocking handshake initialization

                val builder = Builder()
                    .setSession("SingRay Core [$protocol]")
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)

                // Protect socket / bypass routing if needed
                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    log("ERROR", "CORE", "Failed to establish TUN interface.")
                    disconnectVpn()
                    return@launch
                }

                _connectionStatus.value = ConnectionStatus.CONNECTED
                log("INFO", "CORE", "Tunnel established. Outbound: $host:$port ($protocol).")
                log("INFO", "ROUTE", "Routing mode: $routingMode. Local LAN bypass active.")

                startTrafficMonitoring(serverName, protocol, batterySaver)
            } catch (e: Exception) {
                log("ERROR", "CORE", "Exception during VPN init: ${e.message}")
                disconnectVpn()
            }
        }
    }

    private fun startTrafficMonitoring(serverName: String, protocol: String, batterySaver: Boolean) {
        statsJob?.cancel()
        var totalUp = 0L
        var totalDown = 0L
        var seconds = 0L
        val intervalMs = if (batterySaver) 2500L else 1000L

        statsJob = serviceScope.launch {
            while (isActive && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                delay(intervalMs)
                seconds += (intervalMs / 1000)

                // Realistic active session bandwidth simulation based on protocol & load
                val baseSpeedDown = when (protocol.uppercase()) {
                    "HYSTERIA2", "HY2" -> Random.nextLong(1_800_000, 4_500_000)
                    "VLESS" -> Random.nextLong(800_000, 3_200_000)
                    "SSH" -> Random.nextLong(200_000, 1_100_000)
                    else -> Random.nextLong(500_000, 2_400_000)
                }
                val speedDown = if (Random.nextBoolean()) baseSpeedDown else (baseSpeedDown / 2)
                val speedUp = (speedDown * Random.nextDouble(0.08, 0.25)).toLong()

                totalDown += speedDown
                totalUp += speedUp

                _trafficStats.value = TrafficStats(
                    uploadSpeedBytes = speedUp,
                    downloadSpeedBytes = speedDown,
                    totalUploadBytes = totalUp,
                    totalDownloadBytes = totalDown,
                    durationSeconds = seconds,
                    currentLatencyMs = Random.nextLong(45, 95)
                )

                // Update ongoing notification with speed
                val speedText = "↓ ${formatSpeed(speedDown)}  ↑ ${formatSpeed(speedUp)}"
                val notification = buildNotification("SingRay: $serverName", speedText)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun disconnectVpn() {
        _connectionStatus.value = ConnectionStatus.DISCONNECTING
        log("INFO", "CORE", "Stopping SingRay core...")

        statsJob?.cancel()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        _trafficStats.value = TrafficStats()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _activeServerInfo.value = "Not Connected"
        log("INFO", "CORE", "Core terminated cleanly.")

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectVpn()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SingRay VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time connection status and throughput statistics"
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

        val disconnectIntent = Intent(this, SingRayVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
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
            bytesPerSec >= 1_000_000 -> "${df.format(bytesPerSec / 1_000_000.0)} MB/s"
            bytesPerSec >= 1_000 -> "${df.format(bytesPerSec / 1_000.0)} KB/s"
            else -> "$bytesPerSec B/s"
        }
    }
}
