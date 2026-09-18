package com.example.model

enum class ProxyProtocol(val displayName: String, val badgeColorHex: Long) {
    VLESS("VLESS", 0xFF00E5FF),
    VMESS("VMess", 0xFF818CF8),
    TROJAN("Trojan", 0xFFA78BFA),
    SHADOWSOCKS("Shadowsocks", 0xFF38BDF8),
    HYSTERIA2("Hysteria 2", 0xFF34D399),
    SSH("SSH Tunnel", 0xFFFBBF24),
    WIREGUARD("WireGuard", 0xFFFB7185);

    companion object {
        fun fromString(value: String): ProxyProtocol {
            return when (value.trim().lowercase()) {
                "vless" -> VLESS
                "vmess" -> VMESS
                "trojan" -> TROJAN
                "shadowsocks", "ss" -> SHADOWSOCKS
                "hysteria", "hysteria2", "hy2" -> HYSTERIA2
                "ssh" -> SSH
                "wireguard", "wg" -> WIREGUARD
                else -> VLESS
            }
        }
    }
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    TESTING,
    DISCONNECTING
}

enum class RoutingMode(val title: String, val description: String) {
    RULE("Rule Based", "Smart routing: proxy blocked sites, bypass direct local traffic"),
    GLOBAL("Global Proxy", "Route all device traffic through selected proxy server"),
    DIRECT("Direct Bypass", "Bypass proxy for all traffic (diagnostics mode)")
}

data class TrafficStats(
    val uploadSpeedBytes: Long = 0L,
    val downloadSpeedBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val durationSeconds: Long = 0L,
    val currentLatencyMs: Long = -1L
)

data class CoreLog(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String = "INFO",
    val tag: String = "CORE",
    val message: String
)
