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
 */
object XrayEngine : CoreEngine {

    override val type = CoreType.XRAY

    private var running = false

    override fun isAvailable(): Boolean = true

    override fun version(): String? = try {
        val req = JSONObject().apply {
            put("apiVersion", 3)
            put("method", "xrayVersion")
        }
        val respStr = LibXray.invoke(req.toString())
        val json = JSONObject(respStr)
        json.optJSONObject("data")?.optString("version")
            ?: json.optString("data", "Xray")
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
                val controller = object : DialerController {
                    override fun protectFd(fd: Long): Boolean {
                        return vpnService.protect(fd.toInt())
                    }
                }
                LibXray.registerDialerController(controller)
                LibXray.registerListenerController(controller)
            }

            val req = JSONObject().apply {
                put("apiVersion", 3)
                put("method", "runXray")
                put("payload", JSONObject().apply {
                    put("xrayJson", configJson)
                })
            }

            val respStr = LibXray.invoke(req.toString())
            val respJson = JSONObject(respStr)
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
            val req = JSONObject().apply {
                put("apiVersion", 3)
                put("method", "stopXray")
            }
            LibXray.invoke(req.toString())
        } catch (_: Throwable) {}
        running = false
        SingRayVpnService.log("INFO", "XRAY", "Stopped")
    }
}
