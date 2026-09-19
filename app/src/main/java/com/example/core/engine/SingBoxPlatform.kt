package com.example.core.engine

import android.net.VpnService
import com.example.service.SingRayVpnService
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState

/**
 * Concrete implementation of libbox's [PlatformInterface].
 *
 * Provides Android VPN integration, interface protection to avoid routing loops,
 * and TUN file descriptor management.
 */
class SingBoxPlatform(
    private val vpnService: VpnService?,
    private val tunFd: Int?
) : PlatformInterface {

    override fun autoDetectInterfaceControl(fd: Int) {
        vpnService?.protect(fd)
    }

    override fun openTun(options: TunOptions?): Int {
        if (tunFd != null && tunFd > 0) {
            return tunFd
        }
        val vpn = vpnService ?: return 0
        val builder = vpn.Builder()
        val mtu = options?.mtu ?: 1500
        builder.setMtu(mtu)
        builder.addAddress("172.19.0.1", 30)
        builder.addRoute("0.0.0.0", 0)
        builder.setSession("SingRay")
        return builder.establish()?.detachFd() ?: 0
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun useProcFS(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun usePlatformBridge(): Boolean = false
    override fun usePlatformShell(): Boolean = false

    override fun clearDNSCache() {}
    override fun cancelNotification(tag: String?, id: Int) {}
    override fun checkPlatformShell() {}
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun createBridge(options: BridgeOptions?): BridgeSession? = null
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): ConnectionOwner? = null

    override fun getInterfaces(): NetworkInterfaceIterator? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun lookupSFTPServer(): String = ""
    override fun lookupUser(name: String?): PlatformUser? = null
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        args: StringIterator?,
        dir: String?,
        uid: Int,
        gid: Int
    ): ShellSession? = null

    override fun readSystemSSHHostKey(): String = ""
    override fun readWIFIState(): WIFIState? = null
    override fun registerMyInterface(name: String?) {}
    override fun sendNotification(notification: Notification?) {}
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun tailscaleHostname(): String = ""
}

class SingBoxCommandHandler : CommandServerHandler {
    override fun connectSSHAgent(): Int = -1
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun serviceReload() {
        SingRayVpnService.log("INFO", "SING-BOX", "Reload requested")
    }
    override fun serviceStop() {
        SingRayVpnService.log("INFO", "SING-BOX", "Stop requested")
    }
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) {
            SingRayVpnService.log("DEBUG", "SING-BOX", message)
        }
    }
}

