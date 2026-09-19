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
 *  1. Hysteria2 / TUIC / WireGuard / SSH / ShadowTLS  -> sing-box (only core that speaks them)
 *  2. REALITY or XTLS flow (xtls-rprx-vision)         -> Xray (reference implementation)
 *  3. gRPC / xhttp / httpupgrade transports           -> Xray
 *  4. Everything else (VLESS/VMess/Trojan/SS over tcp, ws, tls) -> sing-box, then Xray
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

    /** Pure decision function: which core *should* handle this config. */
    fun pickCore(server: ServerEntity, preference: CoreType = CoreType.AUTO): CoreType {
        val singBoxReady = SingBoxEngine.isAvailable()
        val xrayReady = XrayEngine.isAvailable()

        if (preference == CoreType.SING_BOX && singBoxReady && SingBoxEngine.supports(server)) return CoreType.SING_BOX
        if (preference == CoreType.XRAY && xrayReady && XrayEngine.supports(server)) return CoreType.XRAY
        if (preference == CoreType.BUILT_IN) return CoreType.BUILT_IN

        val protocol = server.protocol.lowercase()
        val security = server.security.lowercase()
        val network = server.network.lowercase()

        val needsSingBox = protocol in setOf(
            "hysteria", "hysteria2", "hy2", "tuic", "wireguard", "wg", "ssh", "shadowtls"
        )
        val prefersXray = security == "reality" ||
            server.publicKey.isNotBlank() ||
            network in setOf("grpc", "xhttp", "splithttp", "httpupgrade", "h2", "http")

        return when {
            needsSingBox && singBoxReady -> CoreType.SING_BOX
            needsSingBox -> CoreType.BUILT_IN // will report an honest error
            prefersXray && xrayReady -> CoreType.XRAY
            prefersXray && singBoxReady -> CoreType.SING_BOX
            singBoxReady -> CoreType.SING_BOX
            xrayReady -> CoreType.XRAY
            else -> CoreType.BUILT_IN
        }
    }

    private fun engineFor(core: CoreType): CoreEngine? = when (core) {
        CoreType.SING_BOX -> SingBoxEngine
        CoreType.XRAY -> XrayEngine
        else -> null
    }

    /**
     * Starts the best core for [server].
     *
     * Returns a result whose `core` is [CoreType.BUILT_IN] when no native core
     * could take the job; the caller then runs the Kotlin fallback core.
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

        val first = pickCore(server, preference)
        val order = listOfNotNull(
            first,
            listOf(CoreType.SING_BOX, CoreType.XRAY).firstOrNull { it != first && engineFor(it)?.isAvailable() == true }
        ).distinct()

        for (core in order) {
            val engine = engineFor(core) ?: continue
            if (!engine.isAvailable() || !engine.supports(server)) continue

            val configJson = when (core) {
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

            SingRayVpnService.log(
                "INFO", "CORE",
                "Auto-selected ${core.title} for ${server.protocol.uppercase()}" +
                    (if (server.security.isNotBlank()) "/${server.security}" else "") +
                    (if (server.network.isNotBlank()) "/${server.network}" else "")
            )

            val result = engine.start(context, vpnService, server, configJson, tunFd)
            if (result.success) {
                activeEngine = engine
                return result
            }
            SingRayVpnService.log("WARN", "CORE", result.message + " - trying next core")
        }

        return CoreStartResult(
            false,
            CoreType.BUILT_IN,
            "No native core available, falling back to the built-in Kotlin core"
        )
    }

    fun stop() {
        activeEngine?.let {
            try { it.stop() } catch (_: Throwable) {}
        }
        activeEngine = null
    }
}
