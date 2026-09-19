package com.example.core.engine

import android.content.Context
import android.net.VpnService
import com.example.data.entity.ServerEntity
import com.example.service.SingRayVpnService
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Native Xray-core engine (libXray.aar, package libXray).
 *
 * The whole bridge is bound through reflection on purpose:
 *
 *  1. libXray.aar is an OPTIONAL core. sing-box already speaks every protocol
 *     this app offers, and bundling both AARs is fragile because each gomobile
 *     build ships its own copy of the gomobile runtime (`go/Seq`) and of
 *     libgojni.so. With reflection the app still compiles and runs when
 *     app/libs/libXray.aar is absent - Xray is simply reported as unavailable.
 *  2. Even when the Java classes are on the classpath, the matching shared
 *     object can be missing or built for another ABI, and the first call dies
 *     with:
 *
 *       java.lang.UnsatisfiedLinkError: No implementation found for
 *       void libXray.LibXray._init() (tried Java_libXray_LibXray__1init ...)
 *
 *     We probe the bridge exactly once, cache the verdict and let CoreManager
 *     fall back to sing-box instead of routing traffic to a core that can
 *     never start.
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

    @Volatile
    private var libXrayClass: Class<*>? = null

    private fun libXray(): Class<*> =
        libXrayClass ?: Class.forName("libXray.LibXray").also { libXrayClass = it }

    /** Reflection hides the real cause inside InvocationTargetException. */
    private fun unwrap(e: Throwable): Throwable =
        (e as? InvocationTargetException)?.targetException ?: e

    /** Raw invoke that never throws checked wrappers; used by the probe too. */
    private fun invoke(payload: JSONObject): String = try {
        libXray()
            .getMethod("invoke", String::class.java)
            .invoke(null, payload.toString()) as String
    } catch (e: Throwable) {
        throw unwrap(e)
    }

    private fun describe(e: Throwable): String = when (e) {
        is ClassNotFoundException ->
            "libXray.aar is not bundled in this build"
        is UnsatisfiedLinkError ->
            "libXray native library missing for this ABI (${e.message ?: "no detail"})"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
    }

    private fun probeBridge(): Boolean {
        if (probed) return bridgeReady
        synchronized(this) {
            if (probed) return bridgeReady
            bridgeReady = try {
                // 1) Java side present?
                libXray()
                // 2) Native side actually linked? Any gomobile call triggers _init().
                val resp = invoke(JSONObject().apply {
                    put("apiVersion", 3)
                    put("method", "xrayVersion")
                })
                resp.isNotBlank()
            } catch (e: Throwable) {
                unavailableReason = describe(e)
                SingRayVpnService.log(
                    "INFO", "XRAY",
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

    /**
     * Without socket protection every packet Xray sends would be routed back
     * into our own TUN device, so a failure here has to abort the start.
     */
    private fun registerSocketProtection(vpnService: VpnService) {
        val controllerClass = Class.forName("libXray.DialerController")
        val controller = Proxy.newProxyInstance(
            controllerClass.classLoader,
            arrayOf(controllerClass)
        ) { proxy, method, args ->
            when (method.name) {
                "protectFd" -> vpnService.protect((args?.get(0) as Number).toInt())
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SingRayDialerController"
                else -> null
            }
        }
        libXray().getMethod("registerDialerController", controllerClass)
            .invoke(null, controller)
        // Newer libXray builds also protect inbound listeners; older ones do not
        // expose the helper at all, which is not fatal.
        try {
            libXray().getMethod("registerListenerController", controllerClass)
                .invoke(null, controller)
        } catch (_: NoSuchMethodException) {
        }
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
                "Xray core unavailable (${unavailableReason ?: "libXray.aar not bundled"})"
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
                    registerSocketProtection(vpnService)
                } catch (e: Throwable) {
                    val cause = unwrap(e)
                    return CoreStartResult(
                        false,
                        type,
                        "Xray socket protection unavailable (${cause.javaClass.simpleName}) - " +
                            "refusing to start to avoid a routing loop"
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
            val cause = unwrap(e)
            CoreStartResult(false, type, "Xray failed: ${cause.message ?: cause.javaClass.simpleName}")
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
