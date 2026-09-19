package com.example.core.engine

import android.content.Context
import android.net.VpnService
import com.example.data.entity.ServerEntity

/**
 * Which core actually carries the traffic.
 *
 * AUTO      -> pick the best core for the selected config (see [CoreManager])
 * SING_BOX  -> native sing-box (libbox.aar)
 * XRAY      -> native Xray-core (libXray.aar / libv2ray.aar)
 * BUILT_IN  -> the pure-Kotlin fallback core shipped inside this app
 */
enum class CoreType(val title: String, val persianTitle: String) {
    AUTO("Auto", "خودکار"),
    SING_BOX("Sing-box", "سینگ‌باکس"),
    XRAY("Xray", "ایکس‌ری"),
    BUILT_IN("Built-in", "هسته داخلی")
}

data class CoreStartResult(
    val success: Boolean,
    val core: CoreType,
    val message: String
)

/**
 * Common contract every core implementation must satisfy.
 *
 * The native engines are loaded through reflection so the project still
 * compiles (and the built-in core still works) when the .aar files are not
 * present in `app/libs/`. See `app/libs/README.md`.
 */
interface CoreEngine {

    val type: CoreType

    /** True when the native library for this core is actually bundled. */
    fun isAvailable(): Boolean

    /** Version string reported by the native core, or null when unavailable. */
    fun version(): String?

    /** Can this core handle the given config at all? */
    fun supports(server: ServerEntity): Boolean

    /**
     * Starts the core.
     *
     * @param tunFd file descriptor of an already-established VpnService TUN
     *              interface, or null to run in local-proxy mode only
     *              (SOCKS/HTTP inbound, no system-wide capture).
     */
    fun start(
        context: Context,
        vpnService: VpnService?,
        server: ServerEntity,
        configJson: String,
        tunFd: Int?
    ): CoreStartResult

    fun stop()

    /** Bytes since start: first = uplink, second = downlink. -1 when unknown. */
    fun trafficStats(): Pair<Long, Long> = Pair(-1L, -1L)
}
