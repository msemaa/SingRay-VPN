package com.example.core.engine

import android.content.Context
import android.net.VpnService
import com.example.data.entity.ServerEntity
import com.example.service.SingRayVpnService
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.io.File

/**
 * Native Xray-core engine (libXray.aar, package libXray).
 *
 * IMPORTANT (see docs/AGENT_HANDOFF.md):
 * The Java classes of libXray can be present on the classpath while the matching
 * gomobile shared object (libgojni.so) is missing or built for another ABI. In
 * that case the very first call crashes with:
 *
 *   java.lang.UnsatisfiedLinkError: No implementation found for
 *   void libXray.LibXray._init() (tried Java_libXray_LibXray__1init ...)
 *
 * Previously isAvailable() returned a hardcoded `true`, so CoreManager kept
 * routing every profile to a core that could never start, and the connection
 * ended up on the limited Kotlin fallback. We now probe the bridge exactly once
 * and cache the verdict, so an unusable Xray is transparently skipped and
 * sing-box takes over.
 */
object XrayEngine : CoreEngine {

    override val type = CoreType.XRAY

    private var running = false

    /** Cached result of the one-time JNI probe. */
    @Volatile
    private var probed = false

    @Volatile
    private var bridgeReady = false

    @Volatile
    private var unavailableReason: String? = null

    /** Raw invoke that never throws checked wrappers; used by the probe too. */
    private fun invoke(payload: JSONObject): String = LibXray.invoke(payload.toString())

    private fun probeBridge(): Boolean {
        if (probed) return bridgeReady
        synchronized(this) {
            if (probed) return bridgeReady
            bridgeReady = try {
                // 1) Java side present?
                Class.forName("libXray.LibXray")
                // 2) Native side actually linked? Any gomobile call triggers _init().
                val resp = invoke(JSONObject().apply {
                    put("apiVersion", 3)
                    put("method", "xrayVersion")
                })
                resp.isNotBlank()
            } catch (e: Throwable) {
                unavailableReason = "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
                SingRayVpnService.log(
                    "WARN", "XRAY",
                    "Native Xray bridge is not usable ($unavailableReason). " +
                        "Xray will be skipped automatically and sing-box will carry the traffic."
                )
                false
            }
            probed = true
            return bridgeReady
        }
    }

    override fun isAvailable(): Boolean = probeBridge()

    /** Human readable explanation shown in the diagnostics screen. */
    fun unavailableReason(): String? = if (isAvailable()) null else unavailableReason

    override fun version(): String? = try {
        if (!probeBridge()) null else {
            val json = JSONObject(invoke(JSONObject().apply {
                put("apiVersion", 3)
                put("method", "xrayVersion")
            }))
            json.optJSONObject("data")?.optString("version")
                ?: json.optString("data", "Xray")
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Xray is the reference implementation for VLESS + REALITY + XTLS-Vision
     * and for the classic V2Ray transports (ws, grpc, httpupgrade, xhttp).
     * It does NOT speak Hysteria / Hysteria2 / TUIC / AnyTLS / ShadowTLS /
     * WireGuard / SSH - those are routed to sing-box by CoreManager.
     */
    override fun supports(server: ServerEntity): Boolean = when (server.protocol.lowercase()) {
        "vless", "vmess", "trojan", "shadowsocks", "ss", "socks", "socks5", "http", "https" -> true
        else -> false
    }

    override fun start(
        context: Context,
        vpnService: VpnService?,
        server: ServerEntity,
        configJson: String,
        tunFd: Int?
    ): CoreStartResult {
        if (!probeBridge()) {
            return CoreStartResult(
                false,
                type,
                "Xray native library is not bundled in this build (${unavailableReason ?: "missing libgojni.so"})"
            )
        }
        return try {
            val assetsDir = File(context.filesDir, "xray").apply { mkdirs() }
            listOf("geoip.dat", "geosite.dat").forEach { assetName ->
                val targetFile = File(assetsDir, assetName)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    try {
                        context.assets.open(assetName).use { input ->
                            targetFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (_: Throwable) {}
                }
            }

            stop()

            if (vpnService != null) {
                try {
                    val controller = object : DialerController {
                        override fun protectFd(fd: Long): Boolean = vpnService.protect(fd.toInt())
                    }
                    LibXray.registerDialerController(controller)
                    LibXray.registerListenerController(controller)
                } catch (e: Throwable) {
                    // Older/newer libXray builds renamed these helpers. Losing socket
                    // protection would loop traffic back into the tunnel, so bail out
                    // instead of starting a broken tunnel.
                    return CoreStartResult(
                        false,
                        type,
                        "Xray socket protection unavailable (${e.javaClass.simpleName}) - refusing to start to avoid a routing loop"
                    )
                }
            }

            val req = JSONObject().apply {
                put("apiVersion", 3)
                put("method", "runXray")
                put("payload", JSONObject().apply {
                    put("datDir", assetsDir.absolutePath)
                    put("xrayJson", configJson)
                })
            }

            val respJson = JSONObject(invoke(req))
            if (!respJson.optBoolean("success", false)) {
                val err = respJson.optString("error", "Unknown error")
                return CoreStartResult(false, type, "Xray refused the config: $err")
            }

            running = true
            val ver = version() ?: "26.x"
            SingRayVpnService.log("INFO", "XRAY", "Started ($ver)")
            CoreStartResult(true, type, "Xray started ($ver)")
        } catch (e: Throwable) {
            val cause = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            CoreStartResult(false, type, "Xray failed: $cause")
        }
    }

    override fun stop() {
        if (!running) return
        try {
            invoke(JSONObject().apply {
                put("apiVersion", 3)
                put("method", "stopXray")
            })
        } catch (_: Throwable) {}
        running = false
        SingRayVpnService.log("INFO", "XRAY", "Stopped")
    }
}
