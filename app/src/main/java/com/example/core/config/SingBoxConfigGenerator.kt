package com.example.core.config

import com.example.data.entity.ServerEntity
import com.example.model.RoutingMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds sing-box configuration using the **1.12+ / 1.14 schema**.
 *
 * The legacy schema that older clients still emit was removed in sing-box
 * 1.14 and produces errors such as:
 *   `decode config: dns.servers[0]: legacy DNS server formats are deprecated`
 *
 * Everything below therefore uses the modern shapes:
 *  - DNS servers are typed objects (`type` + `server`), never address strings
 *  - `block` outbound is gone, rejection happens through route actions
 *  - sniffing and DNS hijacking are route actions, not inbound booleans
 *  - the tun inbound uses the `address` array instead of `inet4_address`
 */
object SingBoxConfigGenerator {

    // ---------------------------------------------------------------- public

    /** Pretty config shown in the UI. */
    fun generateSingBoxJson(
        server: ServerEntity,
        routingMode: RoutingMode = RoutingMode.RULE,
        bypassLan: Boolean = true,
        bypassIran: Boolean = true,
        dnsServer: String = "1.1.1.1",
        mtu: Int = 9000
    ): String = buildRoot(
        server = server,
        routingMode = routingMode,
        bypassLan = bypassLan,
        bypassIran = bypassIran,
        dnsServer = dnsServer,
        socksPort = 10808,
        httpPort = 10809,
        useTun = true,
        mtu = mtu,
        logLevel = "info"
    ).toString(2)

    /** Config actually handed to the native core. */
    fun generateRuntimeJson(
        server: ServerEntity,
        routingMode: RoutingMode = RoutingMode.RULE,
        bypassLan: Boolean = true,
        bypassIran: Boolean = true,
        dnsServer: String = "1.1.1.1",
        socksPort: Int = 10808,
        httpPort: Int = 10809,
        useTun: Boolean = false,
        mtu: Int = 9000
    ): String = buildRoot(
        server = server,
        routingMode = routingMode,
        bypassLan = bypassLan,
        bypassIran = bypassIran,
        dnsServer = dnsServer,
        socksPort = socksPort,
        httpPort = httpPort,
        useTun = useTun,
        mtu = mtu,
        logLevel = "warn"
    ).toString()

    // ----------------------------------------------------------------- root

    private fun buildRoot(
        server: ServerEntity,
        routingMode: RoutingMode,
        bypassLan: Boolean,
        bypassIran: Boolean,
        dnsServer: String,
        socksPort: Int,
        httpPort: Int,
        useTun: Boolean,
        mtu: Int,
        logLevel: String
    ): JSONObject {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("disabled", false)
            put("level", logLevel)
            put("timestamp", true)
        })

        root.put("dns", buildDns(dnsServer, bypassIran))
        root.put("inbounds", buildInbounds(socksPort, httpPort, useTun, mtu))
        root.put("outbounds", buildOutbounds(server))
        root.put("route", buildRoute(routingMode, bypassLan, bypassIran))

        root.put("experimental", JSONObject().apply {
            put("cache_file", JSONObject().apply {
                put("enabled", true)
                put("store_fakeip", false)
            })
        })

        return root
    }

    // ------------------------------------------------------------------ dns

    private fun buildDns(dnsServer: String, bypassIran: Boolean): JSONObject {
        val servers = JSONArray()
        servers.put(JSONObject().apply {
            put("type", "https")
            put("tag", "dns-remote")
            put("server", dnsServer.ifBlank { "1.1.1.1" })
            put("detour", "proxy")
        })
        servers.put(JSONObject().apply {
            put("type", "udp")
            put("tag", "dns-direct")
            put("server", "8.8.8.8")
            put("detour", "direct")
        })

        val rules = JSONArray()
        // Never resolve the proxy host itself through the proxy.
        rules.put(JSONObject().apply {
            put("outbound", "any")
            put("server", "dns-direct")
        })
        if (bypassIran) {
            rules.put(JSONObject().apply {
                put("domain_suffix", JSONArray().put(".ir"))
                put("server", "dns-direct")
            })
        }

        return JSONObject().apply {
            put("servers", servers)
            put("rules", rules)
            put("final", "dns-remote")
            put("strategy", "prefer_ipv4")
            put("independent_cache", true)
        }
    }

    // ------------------------------------------------------------- inbounds

    private fun buildInbounds(
        socksPort: Int,
        httpPort: Int,
        useTun: Boolean,
        mtu: Int
    ): JSONArray {
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("type", "mixed")
            put("tag", "mixed-in")
            put("listen", "127.0.0.1")
            put("listen_port", socksPort)
        })
        inbounds.put(JSONObject().apply {
            put("type", "http")
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("listen_port", httpPort)
        })
        if (useTun) {
            inbounds.put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("interface_name", "singray-tun")
                put("address", JSONArray().put("172.19.0.1/30"))
                put("mtu", mtu)
                put("auto_route", true)
                put("strict_route", false)
                put("stack", "mixed")
            })
        }
        return inbounds
    }

    // ------------------------------------------------------------ outbounds

    private fun buildOutbounds(server: ServerEntity): JSONArray {
        val node = createOutboundForServer(server).apply { put("tag", "proxy") }
        return JSONArray()
            .put(node)
            .put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
    }

    // ---------------------------------------------------------------- route

    private fun buildRoute(
        routingMode: RoutingMode,
        bypassLan: Boolean,
        bypassIran: Boolean
    ): JSONObject {
        val rules = JSONArray()

        // Sniffing and DNS hijacking are actions in the modern schema.
        rules.put(JSONObject().apply {
            put("action", "sniff")
        })
        rules.put(JSONObject().apply {
            put("protocol", "dns")
            put("action", "hijack-dns")
        })

        when (routingMode) {
            RoutingMode.GLOBAL -> {
                // everything to the proxy, handled by `final`
            }
            RoutingMode.DIRECT -> {
                // everything direct, handled by `final`
            }
            RoutingMode.RULE -> {
                if (bypassLan) {
                    rules.put(JSONObject().apply {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    })
                }
                if (bypassIran) {
                    rules.put(JSONObject().apply {
                        put("domain_suffix", JSONArray().put(".ir"))
                        put("outbound", "direct")
                    })
                }
            }
        }

        return JSONObject().apply {
            put("rules", rules)
            put("final", if (routingMode == RoutingMode.DIRECT) "direct" else "proxy")
            put("auto_detect_interface", true)
            put("default_domain_resolver", JSONObject().apply {
                put("server", "dns-direct")
            })
        }
    }

    // ------------------------------------------------------------ outbound

    private fun ServerEntity.serverName(): String = sni.ifBlank { host.ifBlank { server } }

    private fun ServerEntity.alpnArray(): JSONArray? {
        if (alpn.isBlank()) return null
        val arr = JSONArray()
        alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
        return if (arr.length() == 0) null else arr
    }

    /** TLS block with sane, protocol independent defaults. */
    private fun buildTls(server: ServerEntity, forceEnabled: Boolean = false): JSONObject? {
        val sec = server.security.lowercase()
        val enabled = forceEnabled || sec == "tls" || sec == "reality" || sec == "xtls"
        if (!enabled) return null

        return JSONObject().apply {
            put("enabled", true)
            put("server_name", server.serverName())
            put("insecure", server.insecure)
            server.alpnArray()?.let { put("alpn", it) }

            if (sec == "reality") {
                put("reality", JSONObject().apply {
                    put("enabled", true)
                    put("public_key", server.publicKey)
                    if (server.shortId.isNotBlank()) put("short_id", server.shortId)
                })
                // REALITY requires a uTLS fingerprint.
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", server.fingerprint.ifBlank { "chrome" })
                })
            } else if (server.fingerprint.isNotBlank() && server.fingerprint != "none") {
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", server.fingerprint)
                })
            }
        }
    }

    /** v2ray transport block shared by vless / vmess / trojan. */
    private fun buildTransport(server: ServerEntity): JSONObject? {
        return when (server.network.lowercase()) {
            "ws", "websocket" -> JSONObject().apply {
                put("type", "ws")
                put("path", server.path.ifBlank { "/" })
                put("headers", JSONObject().apply {
                    if (server.host.isNotBlank()) put("Host", server.host)
                })
                // 0-RTT support used by most panels.
                put("max_early_data", 2048)
                put("early_data_header_name", "Sec-WebSocket-Protocol")
            }
            "grpc" -> JSONObject().apply {
                put("type", "grpc")
                put("service_name", server.path.trim('/').ifBlank { "GunService" })
                put("idle_timeout", "15s")
                put("ping_timeout", "15s")
                put("permit_without_stream", false)
            }
            "httpupgrade" -> JSONObject().apply {
                put("type", "httpupgrade")
                put("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) put("host", server.host)
            }
            "http", "h2", "h2c" -> JSONObject().apply {
                put("type", "http")
                put("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) {
                    put("host", JSONArray().put(server.host))
                }
            }
            "quic" -> JSONObject().apply { put("type", "quic") }
            else -> null // raw TCP needs no transport block
        }
    }

    private fun createOutboundForServer(server: ServerEntity): JSONObject {
        val out = JSONObject()
        out.put("tag", "node-out")
        val host = server.server
        val port = server.port

        when (server.protocol.lowercase()) {
            "vless" -> {
                out.put("type", "vless")
                out.put("server", host)
                out.put("server_port", port)
                out.put("uuid", server.uuid)
                // XTLS Vision only makes sense over raw TCP with REALITY/TLS.
                val raw = server.network.isBlank() ||
                    server.network.equals("tcp", true) ||
                    server.network.equals("raw", true)
                if (raw && server.security.equals("reality", true)) {
                    out.put("flow", "xtls-rprx-vision")
                }
                out.put("packet_encoding", "xudp")
                buildTls(server)?.let { out.put("tls", it) }
                buildTransport(server)?.let { out.put("transport", it) }
            }

            "vmess" -> {
                out.put("type", "vmess")
                out.put("server", host)
                out.put("server_port", port)
                out.put("uuid", server.uuid)
                out.put("security", "auto")
                out.put("alter_id", server.alterId)
                out.put("packet_encoding", "xudp")
                buildTls(server)?.let { out.put("tls", it) }
                buildTransport(server)?.let { out.put("transport", it) }
            }

            "trojan" -> {
                out.put("type", "trojan")
                out.put("server", host)
                out.put("server_port", port)
                out.put("password", server.uuid)
                // Trojan is TLS by definition.
                out.put("tls", buildTls(server, forceEnabled = true)!!)
                buildTransport(server)?.let { out.put("transport", it) }
            }

            "shadowsocks", "ss" -> {
                out.put("type", "shadowsocks")
                out.put("server", host)
                out.put("server_port", port)
                out.put("method", server.fingerprint.ifBlank { "chacha20-ietf-poly1305" })
                out.put("password", server.uuid)
                out.put("udp_over_tcp", false)
                out.put("multiplex", JSONObject().apply {
                    put("enabled", false)
                })
            }

            "hysteria2", "hy2" -> {
                out.put("type", "hysteria2")
                out.put("server", host)
                out.put("server_port", port)
                out.put("password", server.uuid)
                if (server.path.isNotBlank()) {
                    // salamander obfuscation, carried in the URI as obfs-password
                    out.put("obfs", JSONObject().apply {
                        put("type", "salamander")
                        put("password", server.path)
                    })
                }
                out.put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.serverName())
                    put("insecure", server.insecure)
                    put("alpn", server.alpnArray() ?: JSONArray().put("h3"))
                })
            }

            "hysteria", "hy" -> {
                out.put("type", "hysteria")
                out.put("server", host)
                out.put("server_port", port)
                out.put("auth_str", server.uuid)
                // Hysteria v1 refuses to start without bandwidth hints.
                out.put("up_mbps", 100)
                out.put("down_mbps", 100)
                out.put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.serverName())
                    put("insecure", server.insecure)
                    put("alpn", server.alpnArray() ?: JSONArray().put("hysteria"))
                })
            }

            "tuic" -> {
                out.put("type", "tuic")
                out.put("server", host)
                out.put("server_port", port)
                out.put("uuid", server.uuid)
                out.put("password", server.path)
                out.put("congestion_control", "bbr")
                out.put("udp_relay_mode", "native")
                out.put("zero_rtt_handshake", false)
                out.put("heartbeat", "10s")
                out.put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.serverName())
                    put("insecure", server.insecure)
                    put("alpn", server.alpnArray() ?: JSONArray().put("h3"))
                })
            }

            "anytls" -> {
                out.put("type", "anytls")
                out.put("server", host)
                out.put("server_port", port)
                out.put("password", server.uuid)
                out.put("idle_session_check_interval", "30s")
                out.put("idle_session_timeout", "30s")
                out.put("tls", buildTls(server, forceEnabled = true)!!)
            }

            "shadowtls" -> {
                out.put("type", "shadowtls")
                out.put("server", host)
                out.put("server_port", port)
                out.put("version", 3)
                out.put("password", server.uuid)
                out.put("tls", buildTls(server, forceEnabled = true)!!)
            }

            "socks", "socks5" -> {
                out.put("type", "socks")
                out.put("server", host)
                out.put("server_port", port)
                out.put("version", "5")
                if (server.host.isNotBlank()) put2(out, "username", server.host)
                if (server.uuid.isNotBlank()) put2(out, "password", server.uuid)
            }

            "http", "https" -> {
                out.put("type", "http")
                out.put("server", host)
                out.put("server_port", port)
                if (server.host.isNotBlank()) put2(out, "username", server.host)
                if (server.uuid.isNotBlank()) put2(out, "password", server.uuid)
                buildTls(server)?.let { out.put("tls", it) }
            }

            "ssh" -> {
                out.put("type", "ssh")
                out.put("server", host)
                out.put("server_port", port)
                out.put("user", server.host.ifBlank { "root" })
                out.put("password", server.uuid)
            }

            "wireguard", "wg" -> {
                // sing-box 1.11+ models WireGuard as an endpoint, but the
                // outbound form is still accepted for a single peer.
                out.put("type", "wireguard")
                out.put("server", host)
                out.put("server_port", port)
                out.put("private_key", server.uuid)
                out.put("peer_public_key", server.publicKey)
                if (server.shortId.isNotBlank()) out.put("pre_shared_key", server.shortId)
                out.put("local_address", JSONArray().apply {
                    val addr = server.path.ifBlank { "172.16.0.2/32" }
                    addr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
                })
                out.put("mtu", 1408)
            }

            else -> {
                // Unknown protocol: fail loudly instead of silently dialing
                // something that will never work.
                throw IllegalArgumentException(
                    "Protocol '" + server.protocol + "' is not supported by the sing-box core"
                )
            }
        }

        return out
    }

    private fun put2(target: JSONObject, key: String, value: String) {
        target.put(key, value)
    }
}
