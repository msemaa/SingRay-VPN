package com.example.core.engine

import android.content.Context
import android.net.VpnService
import com.example.data.entity.ServerEntity
import com.example.service.SingRayVpnService
import java.io.File

/**
 * Native sing-box engine (libbox.aar, produced by `make lib_android` in the
 * sing-box repository, package `io.nekohasekai.libbox`).
 *
 * Everything is called through reflection so the app keeps compiling and
 * running when the .aar is not bundled yet.
 *
 * Expected native surface (sing-box 1.10+/1.11+ libbox):
 *   Libbox.setup(basePath, workingPath, tempPath, isTVOS)
 *   Libbox.newService(configJson, platformInterface) -> BoxService
 *   BoxService.start() / BoxService.close()
 *   Libbox.version()
 */
object SingBoxEngine : CoreEngine {

    override val type = CoreType.SING_BOX

    private const val LIBBOX_CLASS = "io.nekohasekai.libbox.Libbox"

    private var boxService: Any? = null
    private var initialized = false

    private fun libboxClass(): Class<*>? = try {
        Class.forName(LIBBOX_CLASS)
    } catch (_: Throwable) {
        null
    }

    override fun isAvailable(): Boolean = libboxClass() != null

    override fun version(): String? = try {
        libboxClass()?.getMethod("version")?.invoke(null) as? String
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
        val cls = libboxClass() ?: return
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
            cls.getMethod(
                "setup",
                String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType
            ).invoke(null, base, working, temp, false)
        } catch (_: NoSuchMethodException) {
            // Older libbox signature without the isTVOS flag.
            cls.getMethod("setup", String::class.java, String::class.java, String::class.java)
                .invoke(null, base, working, temp)
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
        val cls = libboxClass()
            ?: return CoreStartResult(false, type, "libbox.aar is not bundled (see app/libs/README.md)")
        return try {
            setup(context)
            val platform = SingBoxPlatform(vpnService, tunFd)
            val newService = cls.methods.firstOrNull { it.name == "newService" && it.parameterTypes.size == 2 }
                ?: return CoreStartResult(false, type, "libbox.newService() not found in this .aar")
            val service = newService.invoke(null, configJson, platform.proxy())
            service.javaClass.getMethod("start").invoke(service)
            boxService = service
            SingRayVpnService.log("INFO", "SING-BOX", "Started (${version() ?: "unknown version"})")
            CoreStartResult(true, type, "sing-box started")
        } catch (e: Throwable) {
            val cause = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            CoreStartResult(false, type, "sing-box failed: $cause")
        }
    }

    override fun stop() {
        val service = boxService ?: return
        try {
            service.javaClass.getMethod("close").invoke(service)
        } catch (_: Throwable) {
            try { service.javaClass.getMethod("stop").invoke(service) } catch (_: Throwable) {}
        }
        boxService = null
        SingRayVpnService.log("INFO", "SING-BOX", "Stopped")
    }
}
