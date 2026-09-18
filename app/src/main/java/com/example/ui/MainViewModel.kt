package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.config.SingBoxConfigGenerator
import com.example.core.ping.PingDiagnosticResult
import com.example.core.ping.PingEngine
import com.example.data.entity.ServerEntity
import com.example.data.entity.SubscriptionEntity
import com.example.data.repository.ServerRepository
import com.example.model.ConnectionStatus
import com.example.model.CoreLog
import com.example.model.ProxyProtocol
import com.example.model.RoutingMode
import com.example.model.TrafficStats
import com.example.service.SingRayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BatchPingState(
    val isTesting: Boolean = false,
    val current: Int = 0,
    val total: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServerRepository(application)

    val allServers: StateFlow<List<ServerEntity>> = repository.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedServer: StateFlow<ServerEntity?> = repository.selectedServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allSubscriptions: StateFlow<List<SubscriptionEntity>> = repository.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectionStatus: StateFlow<ConnectionStatus> = SingRayVpnService.connectionStatus
    val trafficStats: StateFlow<TrafficStats> = SingRayVpnService.trafficStats
    val activeServerInfo: StateFlow<String?> = SingRayVpnService.activeServerInfo
    val coreLogs: StateFlow<List<CoreLog>> = SingRayVpnService.coreLogs

    // Smart routing / Auto-best-ping toggle
    private val _smartPingEnabled = MutableStateFlow(false)
    val smartPingEnabled: StateFlow<Boolean> = _smartPingEnabled.asStateFlow()

    // Batch ping state
    private val _batchPingState = MutableStateFlow(BatchPingState())
    val batchPingState: StateFlow<BatchPingState> = _batchPingState.asStateFlow()

    // Routing and engine options
    private val _routingMode = MutableStateFlow(RoutingMode.RULE)
    val routingMode: StateFlow<RoutingMode> = _routingMode.asStateFlow()

    private val _bypassLan = MutableStateFlow(true)
    val bypassLan: StateFlow<Boolean> = _bypassLan.asStateFlow()

    private val _bypassDomestic = MutableStateFlow(true)
    val bypassDomestic: StateFlow<Boolean> = _bypassDomestic.asStateFlow()

    private val _dnsServer = MutableStateFlow("1.1.1.1")
    val dnsServer: StateFlow<String> = _dnsServer.asStateFlow()

    private val _coreType = MutableStateFlow("Sing-box")
    val coreType: StateFlow<String> = _coreType.asStateFlow()

    // Diagnostic tool state
    private val _diagnosticResult = MutableStateFlow<PingDiagnosticResult?>(null)
    val diagnosticResult: StateFlow<PingDiagnosticResult?> = _diagnosticResult.asStateFlow()

    private val _isTestingDiagnostic = MutableStateFlow(false)
    val isTestingDiagnostic: StateFlow<Boolean> = _isTestingDiagnostic.asStateFlow()

    // User notice / snackbar message
    private val _uiNotice = MutableStateFlow<String?>(null)
    val uiNotice: StateFlow<String?> = _uiNotice.asStateFlow()

    // Theme Mode: Dark vs Light
    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    // Battery & Memory Saver Profile (reduces background wake-ups and socket sampling)
    private val _batterySaverEnabled = MutableStateFlow(true)
    val batterySaverEnabled: StateFlow<Boolean> = _batterySaverEnabled.asStateFlow()

    // Auto-selection Strategy (Minimum Ping, Maximum Speed, Lowest Loss)
    private val _autoSelectStrategy = MutableStateFlow(com.example.model.AutoSelectStrategy.LOWEST_PING)
    val autoSelectStrategy: StateFlow<com.example.model.AutoSelectStrategy> = _autoSelectStrategy.asStateFlow()

    // Startup Telegram Channel Dialog
    private val _showTelegramDialog = MutableStateFlow(false)
    val showTelegramDialog: StateFlow<Boolean> = _showTelegramDialog.asStateFlow()

    init {
        val prefs = application.getSharedPreferences("singray_prefs", android.content.Context.MODE_PRIVATE)
        val dontShowTelegram = prefs.getBoolean("dont_show_telegram", false)
        if (!dontShowTelegram) {
            _showTelegramDialog.value = true
        }

        viewModelScope.launch {
            repository.seedDefaultNodesIfEmpty()
        }
    }

    fun dismissNotice() {
        _uiNotice.value = null
    }

    fun toggleDarkTheme() {
        _isDarkTheme.value = !_isDarkTheme.value
    }

    fun setDarkTheme(dark: Boolean) {
        _isDarkTheme.value = dark
    }

    fun toggleBatterySaver(enabled: Boolean) {
        _batterySaverEnabled.value = enabled
        SingRayVpnService.log("INFO", "POWER", "Battery Saver Mode: ${if (enabled) "ENABLED (Eco Background)" else "DISABLED (Performance)"}")
    }

    fun setAutoSelectStrategy(strategy: com.example.model.AutoSelectStrategy) {
        _autoSelectStrategy.value = strategy
        applyAutoSelectStrategy(strategy)
    }

    fun applyAutoSelectStrategy(strategy: com.example.model.AutoSelectStrategy = _autoSelectStrategy.value) {
        viewModelScope.launch {
            val best = repository.autoSelectByStrategy(strategy)
            if (best != null) {
                _uiNotice.value = "Selected (${strategy.persianTitle}): ${best.name}"
                SingRayVpnService.log(
                    "INFO",
                    "SMART_ROUTING",
                    "Auto-selected node based on ${strategy.title}: ${best.name} [${best.protocol.uppercase()}]"
                )
            } else {
                _uiNotice.value = "Run ping test first to analyze nodes"
            }
        }
    }

    fun openTelegramDialog() {
        _showTelegramDialog.value = true
    }

    fun dismissTelegramDialog(dontShowAgain: Boolean) {
        _showTelegramDialog.value = false
        if (dontShowAgain) {
            val prefs = getApplication<Application>().getSharedPreferences("singray_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("dont_show_telegram", true).apply()
        }
    }

    fun toggleSmartPing(enabled: Boolean) {
        _smartPingEnabled.value = enabled
        if (enabled) {
            SingRayVpnService.log("INFO", "SMART_ROUTING", "Smart Ping Auto-Select active. Sorting lowest latency.")
            autoSelectLowestPing()
        }
    }

    fun setRoutingMode(mode: RoutingMode) {
        _routingMode.value = mode
        SingRayVpnService.log("INFO", "ROUTING", "Routing mode changed to: ${mode.title}")
    }

    fun setBypassLan(bypass: Boolean) {
        _bypassLan.value = bypass
    }

    fun setBypassDomestic(bypass: Boolean) {
        _bypassDomestic.value = bypass
    }

    fun setDnsServer(dns: String) {
        _dnsServer.value = dns
    }

    fun setCoreType(type: String) {
        _coreType.value = type
    }

    fun selectServer(server: ServerEntity) {
        viewModelScope.launch {
            repository.selectServer(server.id)
            SingRayVpnService.log("INFO", "CONFIG", "Selected server: ${server.name} [${server.protocol.uppercase()}]")
        }
    }

    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            repository.deleteServer(server)
            _uiNotice.value = "Node removed: ${server.name}"
        }
    }

    fun importConfigText(text: String) {
        viewModelScope.launch {
            val count = repository.importFromText(text)
            if (count > 0) {
                _uiNotice.value = "Successfully imported $count config(s)"
                SingRayVpnService.log("INFO", "IMPORT", "Imported $count proxy profiles.")
            } else {
                _uiNotice.value = "No valid proxy links found (VLESS, VMess, Trojan, SS, Hy2, SSH)"
            }
        }
    }

    fun addSubscription(name: String, url: String) {
        viewModelScope.launch {
            _uiNotice.value = "Fetching subscription..."
            val id = repository.addSubscription(name, url)
            if (id > 0) {
                _uiNotice.value = "Subscription added and updated"
            } else {
                _uiNotice.value = "Failed to fetch subscription URL"
            }
        }
    }

    fun updateSubscription(subscription: SubscriptionEntity) {
        viewModelScope.launch {
            _uiNotice.value = "Updating ${subscription.name}..."
            val count = repository.updateSubscription(subscription.id, subscription.url)
            _uiNotice.value = "Updated ${subscription.name}: $count node(s) synced"
        }
    }

    fun deleteSubscription(subscription: SubscriptionEntity) {
        viewModelScope.launch {
            repository.deleteSubscription(subscription)
            _uiNotice.value = "Subscription removed"
        }
    }

    fun pingSingleServer(server: ServerEntity) {
        viewModelScope.launch {
            val ping = repository.testServerPing(server)
            SingRayVpnService.log("INFO", "PING", "${server.name} latency: ${if (ping > 0) "$ping ms" else "Timeout"}")
        }
    }

    fun testAllServersPing() {
        val servers = allServers.value
        if (servers.isEmpty() || _batchPingState.value.isTesting) return

        viewModelScope.launch {
            _batchPingState.value = BatchPingState(isTesting = true, current = 0, total = servers.size)
            SingRayVpnService.log("INFO", "PING", "Batch latency test started for ${servers.size} nodes...")

            repository.testAllPings(servers) { current, total ->
                _batchPingState.value = BatchPingState(isTesting = true, current = current, total = total)
            }

            _batchPingState.value = BatchPingState(isTesting = false, current = servers.size, total = servers.size)
            SingRayVpnService.log("INFO", "PING", "Batch latency test complete.")

            if (_smartPingEnabled.value) {
                autoSelectLowestPing()
            }
        }
    }

    fun autoSelectLowestPing() {
        viewModelScope.launch {
            val best = repository.autoSelectBestPingServer()
            if (best != null) {
                _uiNotice.value = "Selected lowest ping: ${best.name} (${best.lastPingMs}ms)"
                SingRayVpnService.log("INFO", "SMART_ROUTING", "Auto-switched to fastest node: ${best.name} (${best.lastPingMs}ms)")
            } else {
                _uiNotice.value = "Run ping test first to discover fastest server"
            }
        }
    }

    fun runDetailedDiagnostic(host: String, port: Int) {
        viewModelScope.launch {
            _isTestingDiagnostic.value = true
            _diagnosticResult.value = null
            val result = PingEngine.testDetailedDiagnostics(host, port, packetCount = 5)
            _diagnosticResult.value = result
            _isTestingDiagnostic.value = false
        }
    }

    fun generateCurrentSingBoxJson(): String {
        val server = selectedServer.value ?: return "{ \"error\": \"No server selected\" }"
        return SingBoxConfigGenerator.generateSingBoxJson(
            server = server,
            routingMode = _routingMode.value,
            bypassLan = _bypassLan.value,
            bypassIran = _bypassDomestic.value,
            dnsServer = _dnsServer.value
        )
    }

    fun disconnect() {
        val context = getApplication<Application>()
        val intent = Intent(context, SingRayVpnService::class.java).apply {
            action = SingRayVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun startConnect() {
        val context = getApplication<Application>()
        val server = selectedServer.value
        if (server == null) {
            _uiNotice.value = "Please select a server first"
            return
        }

        val intent = Intent(context, SingRayVpnService::class.java).apply {
            action = SingRayVpnService.ACTION_CONNECT
            putExtra(SingRayVpnService.EXTRA_SERVER_NAME, server.name)
            putExtra(SingRayVpnService.EXTRA_SERVER_HOST, server.server)
            putExtra(SingRayVpnService.EXTRA_SERVER_PORT, server.port)
            putExtra(SingRayVpnService.EXTRA_PROTOCOL, server.protocol.uppercase())
            putExtra(SingRayVpnService.EXTRA_ROUTING_MODE, _routingMode.value.title)
            putExtra(SingRayVpnService.EXTRA_BATTERY_SAVER, _batterySaverEnabled.value)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun toggleConnection(onRequestPermission: (() -> Unit) -> Unit) {
        val currentStatus = connectionStatus.value
        if (currentStatus == ConnectionStatus.CONNECTED || currentStatus == ConnectionStatus.CONNECTING) {
            disconnect()
        } else {
            if (selectedServer.value == null) {
                _uiNotice.value = "Please select a server first"
                return
            }
            onRequestPermission {
                startConnect()
            }
        }
    }

    fun connectOrDisconnect() {
        val currentStatus = connectionStatus.value
        if (currentStatus == ConnectionStatus.CONNECTED || currentStatus == ConnectionStatus.CONNECTING) {
            disconnect()
        } else {
            startConnect()
        }
    }
}
