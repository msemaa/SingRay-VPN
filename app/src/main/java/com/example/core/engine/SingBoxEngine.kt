package com.example.core.engine

import android.content.Context
import android.net.VpnService
import com.example.data.entity.ServerEntity
import com.example.service.SingRayVpnService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import java.io.File

/**
 * Native sing-box engine (libbox.aar, io.nekohasekai.libbox).
 *
 * Implements concrete bindings to Libbox and CommandServer.
 */
object SingBoxEngine : CoreEngine {

    override val type = CoreType.SING_BOX

    private var commandServer: CommandServer? = null
    private var initialized = false

    override fun isAvailable(): Boolean = true

    override fun version(): String? = try {
        Libbox.version()
    } catch (_: Throwable) {
        null
    }

    /**
     * sing-box is the most complete core: it is the only one here that speaks
     * Hysteria2, TUIC, WireGuard, ShadowTLS and SSH.
     */
    override fun supports(server: ServerEntity): Boolean = when (server.protocol.lowercase()) {
        "vless", "vmess", "trojan", "shadowsocks", "ss",
        "hysteria", "hysteria2", "hy2", "tuic",
        "wireguard", "wg", "ssh", "socks", "http" -> true
        else -> false
    }

    private fun setup(context: Context) {
        if (initialized) return
        val base = context.filesDir.absolutePath
        val workingDir = File(context.filesDir, "singbox").apply { mkdirs() }
        val working = workingDir.absolutePath
        listOf("geoip.db", "geosite.db", "geoip-cn.db", "geosite-cn.db").forEach { assetName ->
            val target = File(workingDir, assetName)
            if (!target.exists() || target.length() == 0L) {
                try {
                    context.assets.open(assetName).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Throwable) {}
            }
        }
        val temp = context.cacheDir.absolutePath

        try {
            val opts = SetupOptions()
            opts.basePath = base
            opts.workingPath = working
            opts.tempPath = temp
            Libbox.setup(opts)
        } catch (e: Throwable) {
            SingRayVpnService.log("WARN", "SING-BOX", "Setup notice: ${e.message}")
        }
        initialized = true
    }

    override fun start(
        context: Context,
        vpnService: VpnService?,
        server: ServerEntity,
        configJson: String,
        tunFd: Int?
    ): CoreStartResult {
        return try {
            setup(context)
            val platform = SingBoxPlatform(vpnService, tunFd)
            val handler = SingBoxCommandHandler()

            stop()

            val serverInstance = CommandServer(handler, platform)
            serverInstance.start()
            serverInstance.startOrReloadService(configJson, OverrideOptions())
            commandServer = serverInstance

            val ver = version() ?: "1.14.1"
            SingRayVpnService.log("INFO", "SING-BOX", "Started ($ver)")
            CoreStartResult(true, type, "sing-box started ($ver)")
        } catch (e: Throwable) {
            val cause = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            CoreStartResult(false, type, "sing-box failed: $cause")
        }
    }

    override fun stop() {
        val s = commandServer ?: return
        try {
            s.closeService()
        } catch (_: Throwable) {}
        try {
            s.close()
        } catch (_: Throwable) {}
        commandServer = null
        SingRayVpnService.log("INFO", "SING-BOX", "Stopped")
    }
}
