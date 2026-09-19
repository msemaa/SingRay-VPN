package com.example.core.engine

import android.content.Context
import android.net.VpnService
import com.example.data.entity.ServerEntity
import com.example.service.SingRayVpnService
import java.io.File

/**
 * Native Xray-core engine.
 *
 * Works with either of the two common Go-mobile bindings:
 *  - libXray.aar  (package `go.Seq` + `libXray.LibXray`, XTLS/libXray project)
 *  - libv2ray.aar (package `libv2ray.Libv2ray`, AndroidLibXrayLite / v2rayNG)
 *
 * Loaded through reflection so the project compiles without the .aar.
 */
object XrayEngine : CoreEngine {

    override val type = CoreType.XRAY

    private val candidateClasses = listOf(
        "libXray.LibXray",
        "libv2ray.Libv2ray",
        "go.libXray.LibXray"
    )

    private var running = false
    private var coreClass: Class<*>? = null

    private fun core(): Class<*>? {
        coreClass?.let { return it }
        for (name in candidateClasses) {
            try {
                val c = Class.forName(name)
                coreClass = c
                return c
            } catch (_: Throwable) {
            }
        }
        return null
    }

    override fun isAvailable(): Boolean = core() != null

    override fun version(): String? = try {
        val c = core()
        val m = c?.methods?.firstOrNull {
            it.name.equals("xrayVersion", true) ||
                it.name.equals("checkVersionX", true) ||
                it.name.equals("getXrayVersion", true) ||
                it.name.equals("version", true)
        }
        m?.invoke(null)?.toString()
    } catch (_: Throwable) {
        null
    }

    /**
     * Xray is the reference implementation for VLESS + REALITY + XTLS-Vision
     * and for the classic V2Ray transports (ws, grpc, httpupgrade, xhttp).
     * It does NOT speak Hysteria2 / TUIC / WireGuard.
     */
    override fun supports(server: ServerEntity): Boolean = when (server.protocol.lowercase()) {
        "vless", "vmess", "trojan", "shadowsocks", "ss", "socks", "http" -> true
        else -> false
    }

    override fun start(
        context: Context,
        vpnService: VpnService?,
        server: ServerEntity,
        configJson: String,
        tunFd: Int?
    ): CoreStartResult {
        val c = core()
            ?: return CoreStartResult(false, type, "libXray.aar / libv2ray.aar is not bundled (see app/libs/README.md)")
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
            val configFile = File(assetsDir, "config.json").apply { writeText(configJson) }

            // libXray style: initXray(datDir) / runXray(datDir, configPath, maxMemory)
            val runXray = c.methods.firstOrNull { it.name == "runXray" }
            if (runXray != null) {
                c.methods.firstOrNull { it.name == "initXray" }?.invoke(null, assetsDir.absolutePath)
                val result = when (runXray.parameterTypes.size) {
                    3 -> runXray.invoke(null, assetsDir.absolutePath, configFile.absolutePath, 268435456L)
                    2 -> runXray.invoke(null, assetsDir.absolutePath, configFile.absolutePath)
                    else -> runXray.invoke(null, configFile.absolutePath)
                }
                val error = result?.toString().orEmpty()
                if (error.isNotBlank() && !error.contains("\"success\":true") && error != "null") {
                    return CoreStartResult(false, type, "Xray refused the config: $error")
                }
                running = true
                SingRayVpnService.log("INFO", "XRAY", "Started (${version() ?: "unknown version"})")
                return CoreStartResult(true, type, "Xray started")
            }

            // libv2ray style: Libv2ray.startV2Ray / V2RayPoint
            val startV2Ray = c.methods.firstOrNull { it.name.startsWith("startV2Ray", true) }
            if (startV2Ray != null) {
                startV2Ray.invoke(null, configJson)
                running = true
                SingRayVpnService.log("INFO", "XRAY", "Started via libv2ray binding")
                return CoreStartResult(true, type, "Xray started")
            }

            CoreStartResult(false, type, "No known start method found in the bundled Xray binding")
        } catch (e: Throwable) {
            val cause = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            CoreStartResult(false, type, "Xray failed: $cause")
        }
    }

    override fun stop() {
        if (!running) return
        try {
            val c = core()
            val stop = c?.methods?.firstOrNull {
                it.name == "stopXray" || it.name.startsWith("stopV2Ray", true)
            }
            stop?.invoke(null)
        } catch (_: Throwable) {
        }
        running = false
        SingRayVpnService.log("INFO", "XRAY", "Stopped")
    }
}
