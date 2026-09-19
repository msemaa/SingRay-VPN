package com.example.core.engine

import android.net.VpnService
import com.example.service.SingRayVpnService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Dynamic implementation of libbox's `PlatformInterface`.
 *
 * The interface type only exists when libbox.aar is bundled, so it is
 * implemented with a [Proxy] created at runtime. Unknown methods get safe
 * default answers, which keeps the app working across libbox revisions.
 */
class SingBoxPlatform(
    private val vpnService: VpnService?,
    private val tunFd: Int?
) {

    fun proxy(): Any? {
        val iface = try {
            Class.forName("io.nekohasekai.libbox.PlatformInterface")
        } catch (_: Throwable) {
            return null
        }

        val handler = InvocationHandler { _, method: Method, args: Array<out Any?>? ->
            handle(method, args)
        }
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
    }

    private fun handle(method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        // sing-box asks the platform for the already-established TUN fd.
        "openTun" -> tunFd?.toLong() ?: 0L

        // Keep sockets outside of the tunnel to avoid routing loops.
        "autoDetectInterfaceControl" -> {
            val fd = (args?.getOrNull(0) as? Number)?.toInt()
            if (fd != null) vpnService?.protect(fd)
            null
        }

        "usePlatformAutoDetectInterfaceControl" -> true
        "useProcFS", "usePlatformDefaultInterfaceMonitor", "includeAllNetworks" -> false
        "underNetworkExtension", "isExpensive", "isConstrained" -> false

        "writeLog" -> {
            val line = args?.getOrNull(0)?.toString().orEmpty()
            if (line.isNotBlank()) SingRayVpnService.log("INFO", "SING-BOX", line)
            null
        }

        "findConnectionOwner" -> -1
        "packageNameByUid", "uidByPackageName" -> defaultFor(method)
        "readWIFIState", "getInterfaces", "startDefaultInterfaceMonitor",
        "closeDefaultInterfaceMonitor", "clearDNSCache", "sendNotification",
        "updateRouteOptions", "resetNetwork" -> defaultFor(method)

        else -> defaultFor(method)
    }

    private fun defaultFor(method: Method): Any? = when (method.returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0f
        String::class.java -> ""
        else -> null
    }
}
