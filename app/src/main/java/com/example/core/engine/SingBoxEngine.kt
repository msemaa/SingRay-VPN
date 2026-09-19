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
 * IMPORTANT: isAvailable() used to return a hardcoded `true`. When libbox.aar
 * was missing (or its libbox.so was built for another ABI) CoreManager kept
 * routing every profile to a core that could never start, the start attempt
 * blew up with NoClassDefFoundError / UnsatisfiedLinkError, and the user was
 * dropped onto the limited Kotlin core with the misleading message
 * "No native core available". The bridge is now probed exactly once and the
 * verdict cached, so an unusable sing-box is skipped honestly.
 */
object SingBoxEngine : CoreEngine {

    override val type = CoreType.SING_BOX

    private var commandServer: CommandServer? = null
    private var initialized = false

    @Volatile
    private var probed = false

    @Volatile
    private var bridgeReady = false

    @Volatile
    private var cachedVersion: String? = null

    @Volatile
    private var unavailableReason: String? = null

    private fun probeBridge(): Boolean {
        if (probed) return bridgeReady
        synchronized(this) {
            if (probed) return bridgeReady
            bridgeReady = try {
                // 1) Java side present?
                Class.forName("io.nekohasekai.libbox.Libbox")
                // 2) Native side actually linked? Any gomobile call triggers the
                //    JNI init, so a missing libbox.so fails right here.
                val v = Libbox.version()
                cachedVersion = v
                !v.isNullOrBlank()
            } catch (e: Throwable) {
                unavailableReason = "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
                SingRayVpnService.log(
                    "WARN", "SING-BOX",
                    "Native sing-box bridge is not usable ($unavailableReason). " +
                        "This build has no working libbox.aar, so protocols that only " +
                        "sing-box speaks (SSH, TUIC, Hysteria2, WireGuard, ShadowTLS, AnyTLS) " +
                        "cannot be used."
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

    override fun version(): String? = if (probeBridge()) cachedVersion else null

    /**
     * sing-box is the most complete core: it is the only one here that speaks
     * Hysteria / Hysteria2, TUIC, AnyTLS, ShadowTLS, WireGuard and SSH.
     */
    override fun supports(server: ServerEntity): Boolean = when (server.protocol.lowercase()) {
        "vless", "vmess", "trojan", "shadowsocks", "ss",
        "hysteria", "hy", "hysteria2", "hy2", "tuic",
        "anytls", "shadowtls",
        "wireguard", "wg", "ssh",
        "socks", "socks5", "http", "https" -> true
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
        if (!probeBridge()) {
            return CoreStartResult(
                false,
                type,
                "sing-box native library is not bundled in this build (${unavailableReason ?: "missing libbox.so"})"
            )
        }
        return try {
            setup(context)
            val platform = SingBoxPlatform(vpnService, tunFd)
            val handler = SingBoxCommandHandler()

            stop()

            val serverInstance = CommandServer(handler, platform)
            serverInstance.start()
            serverInstance.startOrReloadService(configJson, OverrideOptions())
            commandServer = serverInstance

            val ver = version() ?: "native"
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
