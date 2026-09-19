package com.example.core.engine

import android.content.Context
import android.net.VpnService
import com.example.core.config.SingBoxConfigGenerator
import com.example.core.config.XrayConfigGenerator
import com.example.data.entity.ServerEntity
import com.example.model.RoutingMode
import com.example.service.SingRayVpnService

/**
 * Decides which core carries the traffic and starts it.
 *
 * Auto-switch rules (evaluated top to bottom):
 *  1. Hysteria / Hysteria2 / TUIC / AnyTLS / WireGuard / SSH / ShadowTLS -> sing-box
 *  2. REALITY or XTLS flow (xtls-rprx-vision)                            -> Xray
 *  3. gRPC / xhttp / httpupgrade transports                              -> Xray
 *  4. Everything else (VLESS/VMess/Trojan/SS over tcp, ws, tls)          -> sing-box, then Xray
 *  5. If no native core is bundled or the chosen core fails -> built-in Kotlin core
 */
object CoreManager {

    private var activeEngine: CoreEngine? = null

    val active: CoreType?
        get() = activeEngine?.type

    fun availableCores(): List<CoreType> = buildList {
        if (SingBoxEngine.isAvailable()) add(CoreType.SING_BOX)
        if (XrayEngine.isAvailable()) add(CoreType.XRAY)
        add(CoreType.BUILT_IN)
    }

    fun versions(): Map<CoreType, String> = buildMap {
        SingBoxEngine.version()?.let { put(CoreType.SING_BOX, it) }
        XrayEngine.version()?.let { put(CoreType.XRAY, it) }
        put(CoreType.BUILT_IN, "kotlin-core 2.0")
    }

    /** Why a native core is missing, for the diagnostics screen and error banners. */
    fun unavailableReasons(): Map<CoreType, String> = buildMap {
        SingBoxEngine.unavailableReason()?.let { put(CoreType.SING_BOX, it) }
        XrayEngine.unavailableReason()?.let { put(CoreType.XRAY, it) }
    }

    /** Protocols that no core other than sing-box can carry. */
    private val SING_BOX_ONLY = setOf(
        "hysteria", "hy", "hysteria2", "hy2", "tuic",
        "wireguard", "wg", "ssh", "shadowtls", "anytls"
    )

    /** Pure decision function: which core *should* handle this config, with the rationale. */
    fun pickCoreWithReason(server: ServerEntity, preference: CoreType = CoreType.AUTO): Pair<CoreType, String> {
        val singBoxReady = SingBoxEngine.isAvailable()
        val xrayReady = XrayEngine.isAvailable()

        if (preference == CoreType.SING_BOX && singBoxReady && SingBoxEngine.supports(server)) {
            return CoreType.SING_BOX to "User selected sing-box"
        }
        if (preference == CoreType.XRAY && xrayReady && XrayEngine.supports(server)) {
            return CoreType.XRAY to "User selected Xray"
        }
        if (preference == CoreType.BUILT_IN) {
            return CoreType.BUILT_IN to "User selected Built-in core"
        }

        val protocol = server.protocol.lowercase()
        val security = server.security.lowercase()
        val network = server.network.lowercase()

        val needsSingBox = protocol in SING_BOX_ONLY
        val prefersXray = security == "reality" ||
            server.publicKey.isNotBlank() ||
            network in setOf("grpc", "xhttp", "splithttp", "httpupgrade", "h2", "http")

        return when {
            needsSingBox && singBoxReady -> CoreType.SING_BOX to "${protocol.uppercase()} protocol requires sing-box"
            needsSingBox -> CoreType.BUILT_IN to "${protocol.uppercase()} requires the native sing-box core"
            prefersXray && xrayReady -> CoreType.XRAY to (if (security == "reality") "REALITY requires Xray" else "$network transport prefers Xray")
            prefersXray && singBoxReady -> CoreType.SING_BOX to "sing-box handling ${if (security == "reality") "REALITY" else network}"
            singBoxReady -> CoreType.SING_BOX to "Primary engine (sing-box)"
            xrayReady -> CoreType.XRAY to "Primary engine (Xray)"
            else -> CoreType.BUILT_IN to "Fallback Kotlin core"
        }
    }

    fun pickCore(server: ServerEntity, preference: CoreType = CoreType.AUTO): CoreType =
        pickCoreWithReason(server, preference).first

    private fun engineFor(core: CoreType): CoreEngine? = when (core) {
        CoreType.SING_BOX -> SingBoxEngine
        CoreType.XRAY -> XrayEngine
        else -> null
    }

    /**
     * Starts the best core for [server].
     *
     * Returns a result whose `core` is [CoreType.BUILT_IN] when no native core
     * could take the job. The message then explains exactly what was tried and
     * why each attempt failed, instead of a generic "no native core" string.
     */
    fun start(
        context: Context,
        vpnService: VpnService?,
        server: ServerEntity,
        preference: CoreType,
        routingMode: RoutingMode,
        bypassLan: Boolean,
        bypassDomestic: Boolean,
        dnsServer: String,
        socksPort: Int,
        httpPort: Int,
        tunFd: Int?
    ): CoreStartResult {
        stop()

        val (first, initialReason) = pickCoreWithReason(server, preference)
        val order = listOfNotNull(
            first,
            listOf(CoreType.SING_BOX, CoreType.XRAY).firstOrNull { it != first && engineFor(it)?.isAvailable() == true }
        ).distinct()

        val failures = mutableListOf<String>()

        for (core in order) {
            val engine = engineFor(core) ?: continue
            if (!engine.isAvailable()) {
                val why = when (core) {
                    CoreType.SING_BOX -> SingBoxEngine.unavailableReason()
                    CoreType.XRAY -> XrayEngine.unavailableReason()
                    else -> null
                } ?: "not bundled in this build"
                failures += "${core.title}: $why"
                continue
            }
            if (!engine.supports(server)) {
                failures += "${core.title}: does not support ${server.protocol.uppercase()}"
                continue
            }

            val configJson = try {
                when (core) {
                    CoreType.XRAY -> XrayConfigGenerator.generate(
                        server = server,
                        routingMode = routingMode,
                        bypassLan = bypassLan,
                        bypassDomestic = bypassDomestic,
                        dnsServer = dnsServer,
                        socksPort = socksPort,
                        httpPort = httpPort,
                        tunFd = tunFd
                    )
                    else -> SingBoxConfigGenerator.generateRuntimeJson(
                        server = server,
                        routingMode = routingMode,
                        bypassLan = bypassLan,
                        bypassIran = bypassDomestic,
                        dnsServer = dnsServer,
                        socksPort = socksPort,
                        httpPort = httpPort,
                        useTun = tunFd != null
                    )
                }
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                failures += "${core.title}: config could not be generated ($msg)"
                SingRayVpnService.log("WARN", "CORE", "${core.title} config generation failed: $msg")
                continue
            }

            val ver = engine.version() ?: "native"
            SingRayVpnService.log(
                "INFO", "CORE",
                "Connecting attempt with ${core.title} ($ver) for ${server.protocol.uppercase()}" +
                    (if (server.security.isNotBlank()) "/${server.security}" else "") +
                    (if (server.network.isNotBlank()) "/${server.network}" else "") +
                    " [Reason: $initialReason]"
            )

            val result = engine.start(context, vpnService, server, configJson, tunFd)
            if (result.success) {
                activeEngine = engine
                return result
            }
            failures += "${core.title}: ${result.message}"
            SingRayVpnService.log("WARN", "CORE", "${core.title} ($ver) failed: ${result.message} - trying next core")
        }

        val detail = if (failures.isEmpty()) {
            "no native core is bundled in this build"
        } else {
            failures.joinToString(" | ")
        }

        return CoreStartResult(
            false,
            CoreType.BUILT_IN,
            "No native core could start ($detail)"
        )
    }

    fun stop() {
        activeEngine?.let {
            try { it.stop() } catch (_: Throwable) {}
        }
        // Both engines are singletons: make sure a previously started core is
        // really gone before a new profile is dialled, otherwise switching
        // config silently keeps the old tunnel alive.
        try { SingBoxEngine.stop() } catch (_: Throwable) {}
        try { XrayEngine.stop() } catch (_: Throwable) {}
        activeEngine = null
    }
}
