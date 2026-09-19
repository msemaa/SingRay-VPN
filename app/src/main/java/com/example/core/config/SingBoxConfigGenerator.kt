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
 *   `decode config: dns.rules[0]: json: unknown field "outbound"`
 *
 * Everything below therefore uses the modern shapes:
 *  - DNS servers are typed objects (`type` + `server`), never address strings
 *  - `dns-direct` carries NO detour. A detour pointing at a plain `direct`
 *    outbound is rejected by 1.14 with "detour to an empty direct outbound
 *    makes no sense", which aborts DNS start-up and therefore the whole core
 *    before any outbound is dialled -- every protocol then fails.
 *  - DNS rules never use the removed `outbound` field; the proxy host is
 *    resolved through `route.default_domain_resolver`
 *  - route rules use `action: "route"`; sniffing and DNS hijacking are actions
 *  - the tun inbound uses the `address` array instead of `inet4_address`
 *  - WireGuard is an `endpoint` with a `peers` array, not an outbound
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

    /** True when this protocol is declared under `endpoints` instead of `outbounds`. */
    private fun isEndpointProtocol(protocol: String): Boolean =
        protocol.lowercase() in setOf("wireguard", "wg")

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

        if (isEndpointProtocol(server.protocol)) {
            // sing-box 1.14 removed the legacy `wireguard` outbound.
            root.put("endpoints", JSONArray().put(buildWireGuardEndpoint(server)))
            root.put("outbounds", JSONArray().put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            }))
        } else {
            root.put("outbounds", buildOutbounds(server))
        }

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

    private fun dnsHostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").substringBefore("?")

    private fun dnsPathOf(url: String): String? {
        val afterHost = url.substringAfter("://").substringAfter("/", "")
        return if (afterHost.isBlank()) null else "/" + afterHost.substringBefore("?")
    }

    /**
     * Accepts any of these user inputs and emits a valid 1.14 DNS server:
     *   1.1.1.1                    -> udp
     *   udp://1.1.1.1              -> udp
     *   tls://1.1.1.1              -> tls (DoT)
     *   quic://dns.adguard.com     -> quic (DoQ)
     *   https://1.1.1.1/dns-query  -> https (DoH, path preserved)
     *   h3://dns.google/dns-query  -> h3
     *   local | system             -> the platform resolver
     */
    private fun dnsServerObject(tag: String, raw: String, detour: String?): JSONObject {
        val value = raw.trim()
        val obj = JSONObject().put("tag", tag)

        when {
            value.isBlank() -> {
                obj.put("type", "udp")
                obj.put("server", "1.1.1.1")
            }
            value.equals("local", true) || value.equals("system", true) -> {
                obj.put("type", "local")
            }
            value.startsWith("https://", true) -> {
                obj.put("type", "https")
                obj.put("server", dnsHostOf(value))
                dnsPathOf(value)?.let { obj.put("path", it) }
            }
            value.startsWith("h3://", true) -> {
                obj.put("type", "h3")
                obj.put("server", dnsHostOf(value))
                dnsPathOf(value)?.let { obj.put("path", it) }
            }
            value.startsWith("quic://", true) -> {
                obj.put("type", "quic")
                obj.put("server", dnsHostOf(value))
            }
            value.startsWith("tls://", true) -> {
                obj.put("type", "tls")
                obj.put("server", dnsHostOf(value))
            }
            value.startsWith("udp://", true) || value.startsWith("dns://", true) -> {
                obj.put("type", "udp")
                obj.put("server", dnsHostOf(value))
            }
            else -> {
                obj.put("type", "udp")
                obj.put("server", value)
            }
        }

        // Only a remote resolver may take a detour, and only towards a real
        // proxy outbound. Never towards `direct`: 1.14 rejects that outright.
        if (!detour.isNullOrBlank() && obj.optString("type") != "local") {
            obj.put("detour", detour)
        }
        return obj
    }

    private fun buildDns(dnsServer: String, bypassIran: Boolean): JSONObject {
        val servers = JSONArray()

        // Remote resolver: whatever the user configured, tunnelled via the proxy.
        servers.put(
            dnsServerObject(
                "dns-remote",
                dnsServer.ifBlank { "https://1.1.1.1/dns-query" },
                "proxy"
            )
        )

        // Direct resolver: plain, no detour. Resolves the proxy's own hostname
        // and any bypassed domain without looping through the tunnel.
        servers.put(dnsServerObject("dns-direct", "local", null))

        val rules = JSONArray()
        // NOTE: do NOT add a `{"outbound": "any"}` rule here. That field was
        // deprecated in sing-box 1.12 and removed in 1.14: the whole config is
        // rejected at decode time.
        if (bypassIran) {
            rules.put(JSONObject().apply {
                put("rule_set", JSONArray().put("geosite-ir"))
                put("server", "dns-direct")
            })
            rules.put(JSONObject().apply {
                put("domain_suffix", JSONArray().put(".ir"))
                put("server", "dns-direct")
            })
        }

        return JSONObject().apply {
            put("servers", servers)
            if (rules.length() > 0) put("rules", rules)
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

    /**
     * WireGuard in the modern endpoint form.
     *
     * Field packing used by this app:
     *  uuid      -> private key
     *  publicKey -> peer public key
     *  shortId   -> pre-shared key (optional)
     *  path      -> local addresses, comma separated
     */
    private fun buildWireGuardEndpoint(server: ServerEntity): JSONObject = JSONObject().apply {
        put("type", "wireguard")
        put("tag", "proxy")
        put("system", false)
        put("mtu", 1408)
        put("address", JSONArray().apply {
            val addr = server.path.ifBlank { "172.16.0.2/32" }
            addr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
        })
        put("private_key", server.uuid)
        put("peers", JSONArray().put(JSONObject().apply {
            put("address", server.server)
            put("port", server.port)
            put("public_key", server.publicKey)
            if (server.shortId.isNotBlank()) put("pre_shared_key", server.shortId)
            put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
            put("persistent_keepalive_interval", 25)
        }))
    }

    // ---------------------------------------------------------------- route

    private fun buildRuleSets(enabled: Boolean): JSONArray? {
        if (!enabled) return null
        fun remote(tag: String, url: String) = JSONObject().apply {
            put("type", "remote")
            put("tag", tag)
            put("format", "binary")
            put("url", url)
            put("download_detour", "proxy")
            put("update_interval", "7d")
        }
        return JSONArray()
            .put(
                remote(
                    "geosite-ir",
                    "https://raw.githubusercontent.com/Chocolate4U/Iran-sing-box-rules/rule-set/geosite-ir.srs"
                )
            )
            .put(
                remote(
                    "geoip-ir",
                    "https://raw.githubusercontent.com/Chocolate4U/Iran-sing-box-rules/rule-set/geoip-ir.srs"
                )
            )
    }

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
                        put("action", "route")
                        put("outbound", "direct")
                    })
                }
                if (bypassIran) {
                    rules.put(JSONObject().apply {
                        put("rule_set", JSONArray().put("geosite-ir").put("geoip-ir"))
                        put("action", "route")
                        put("outbound", "direct")
                    })
                    rules.put(JSONObject().apply {
                        put("domain_suffix", JSONArray().put(".ir"))
                        put("action", "route")
                        put("outbound", "direct")
                    })
                }
            }
        }

        return JSONObject().apply {
            put("rules", rules)
            buildRuleSets(bypassIran && routingMode == RoutingMode.RULE)?.let { put("rule_set", it) }
            put("final", if (routingMode == RoutingMode.DIRECT) "direct" else "proxy")
            put("auto_detect_interface", true)
            // Resolves outbound server domains (including the proxy's own host)
            // without looping back through the tunnel.
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

    /** Raw TCP means: no v2ray transport at all (tcp / raw / empty). */
    private fun ServerEntity.isRawTcp(): Boolean =
        network.isBlank() ||
            network.equals("tcp", true) ||
            network.equals("raw", true)

    private fun ServerEntity.isTlsLike(): Boolean =
        security.lowercase() in setOf("tls", "reality", "xtls")

    /** TLS block with sane, protocol independent defaults. Null means no TLS. */
    private fun buildTls(server: ServerEntity, forceEnabled: Boolean = false): JSONObject? {
        val sec = server.security.lowercase()
        val enabled = forceEnabled || server.isTlsLike()
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
                val rawPath = server.path.ifBlank { "/" }
                // Panels encode early data as `/path?ed=2048`.
                val edValue = Regex("[?&]ed=(\\d+)").find(rawPath)?.groupValues?.get(1)?.toIntOrNull()
                put("path", rawPath.substringBefore("?").ifBlank { "/" })
                put("headers", JSONObject().apply {
                    if (server.host.isNotBlank()) put("Host", server.host)
                })
                if (edValue != null) {
                    put("max_early_data", edValue)
                    put("early_data_header_name", "Sec-WebSocket-Protocol")
                }
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
                put("path", server.path.substringBefore("?").ifBlank { "/" })
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
        val host = server.server
        val port = server.port

        when (server.protocol.lowercase()) {
            "vless" -> {
                out.put("type", "vless")
                out.put("server", host)
                out.put("server_port", port)
                out.put("uuid", server.uuid)
                // XTLS Vision is only valid over raw TCP with TLS or REALITY.
                // Over ws/grpc/httpupgrade, or without TLS, it must be omitted.
                if (server.isRawTcp() && server.security.equals("reality", true)) {
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
                // Trojan is TLS by definition, unless the panel explicitly
                // publishes a plain (security=none) node.
                if (!server.security.equals("none", true)) {
                    out.put("tls", buildTls(server, forceEnabled = true)!!)
                }
                buildTransport(server)?.let { out.put("transport", it) }
            }

            "shadowsocks", "ss" -> {
                out.put("type", "shadowsocks")
                out.put("server", host)
                out.put("server_port", port)
                out.put("method", server.fingerprint.ifBlank { "chacha20-ietf-poly1305" })
                out.put("password", server.uuid)
                out.put("udp_over_tcp", false)
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
                if (server.host.isNotBlank()) out.put("username", server.host)
                if (server.uuid.isNotBlank()) out.put("password", server.uuid)
            }

            "http", "https" -> {
                out.put("type", "http")
                out.put("server", host)
                out.put("server_port", port)
                if (server.host.isNotBlank()) out.put("username", server.host)
                if (server.uuid.isNotBlank()) out.put("password", server.uuid)
                buildTls(server)?.let { out.put("tls", it) }
            }

            "ssh" -> {
                out.put("type", "ssh")
                out.put("server", host)
                out.put("server_port", port)
                out.put("user", server.host.ifBlank { "root" })
                val secret = server.uuid.trim()
                if (secret.startsWith("-----BEGIN")) {
                    // PEM private key instead of a password.
                    out.put("private_key", secret)
                    if (server.shortId.isNotBlank()) out.put("private_key_passphrase", server.shortId)
                } else if (secret.isNotBlank()) {
                    out.put("password", secret)
                }
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
}
