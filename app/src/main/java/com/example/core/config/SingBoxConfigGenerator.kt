package com.example.core.config

import com.example.data.entity.ServerEntity
import com.example.model.RoutingMode
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigGenerator {

    fun generateSingBoxJson(
        server: ServerEntity,
        routingMode: RoutingMode = RoutingMode.RULE,
        bypassLan: Boolean = true,
        bypassIran: Boolean = true,
        dnsServer: String = "1.1.1.1",
        mtu: Int = 1500
    ): String {
        val root = JSONObject()

        // 1. Log block
        val log = JSONObject().apply {
            put("disabled", false)
            put("level", "info")
            put("timestamp", true)
        }
        root.put("log", log)

        // 2. DNS block
        val dns = JSONObject()
        val dnsServers = JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "dns-remote")
                put("address", "https://$dnsServer/dns-query")
                put("detour", "proxy")
            })
            put(JSONObject().apply {
                put("tag", "dns-local")
                put("address", "udp://8.8.8.8")
                put("detour", "direct")
            })
        }
        dns.put("servers", dnsServers)
        root.put("dns", dns)

        // 3. Inbounds (TUN)
        val inbounds = JSONArray()
        val tunInbound = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "singray-tun")
            put("inet4_address", "172.19.0.1/30")
            put("mtu", mtu)
            put("auto_route", true)
            put("strict_route", true)
            put("stack", "mixed")
            put("sniff", true)
        }
        inbounds.put(tunInbound)
        root.put("inbounds", inbounds)

        // 4. Outbounds
        val outbounds = JSONArray()

        // Selector / Main Outbound
        val nodeOutbound = createOutboundForServer(server)
        outbounds.put(JSONObject().apply {
            put("type", "selector")
            put("tag", "proxy")
            put("outbounds", JSONArray().put(nodeOutbound.getString("tag")))
        })
        outbounds.put(nodeOutbound)

        // Direct & Block
        outbounds.put(JSONObject().apply {
            put("type", "direct")
            put("tag", "direct")
        })
        outbounds.put(JSONObject().apply {
            put("type", "block")
            put("tag", "block")
        })
        root.put("outbounds", outbounds)

        // 5. Route block
        val route = JSONObject()
        val rules = JSONArray()

        // DNS rule
        rules.put(JSONObject().apply {
            put("protocol", "dns")
            put("outbound", "dns-remote")
        })

        when (routingMode) {
            RoutingMode.GLOBAL -> {
                rules.put(JSONObject().apply {
                    put("outbound", "proxy")
                })
            }
            RoutingMode.DIRECT -> {
                rules.put(JSONObject().apply {
                    put("outbound", "direct")
                })
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
                        put("geoip", JSONArray().put("ir"))
                        put("geosite", JSONArray().put("category-ir"))
                        put("outbound", "direct")
                    })
                }
                rules.put(JSONObject().apply {
                    put("outbound", "proxy")
                })
            }
        }

        route.put("rules", rules)
        route.put("auto_detect_interface", true)
        root.put("route", route)

        return root.toString(2)
    }

    /**
     * Runtime config actually handed to the native sing-box core.
     *
     * Unlike [generateSingBoxJson] (which is the pretty preview shown in the UI)
     * this always exposes a mixed SOCKS/HTTP inbound on localhost, and adds the
     * tun inbound only when the VpnService established a TUN interface.
     */
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
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("disabled", false)
            put("level", "warn")
            put("timestamp", true)
        })

        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "dns-remote")
                    put("address", "https://$dnsServer/dns-query")
                    put("detour", "proxy")
                })
                put(JSONObject().apply {
                    put("tag", "dns-direct")
                    put("address", "udp://8.8.8.8")
                    put("detour", "direct")
                })
            })
            put("strategy", "prefer_ipv4")
            put("independent_cache", true)
        })

        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("type", "mixed")
            put("tag", "mixed-in")
            put("listen", "127.0.0.1")
            put("listen_port", socksPort)
            put("sniff", true)
            put("sniff_override_destination", false)
        })
        inbounds.put(JSONObject().apply {
            put("type", "http")
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("listen_port", httpPort)
            put("sniff", true)
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
                put("sniff", true)
            })
        }
        root.put("inbounds", inbounds)

        val nodeOutbound = createOutboundForServer(server).apply { put("tag", "proxy") }
        val outbounds = JSONArray()
            .put(nodeOutbound)
            .put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
            .put(JSONObject().apply {
                put("type", "block")
                put("tag", "block")
            })
            .put(JSONObject().apply {
                put("type", "dns")
                put("tag", "dns-out")
            })
        root.put("outbounds", outbounds)

        val rules = JSONArray()
        rules.put(JSONObject().apply {
            put("protocol", "dns")
            put("outbound", "dns-out")
        })
        when (routingMode) {
            RoutingMode.GLOBAL -> rules.put(JSONObject().apply { put("outbound", "proxy") })
            RoutingMode.DIRECT -> rules.put(JSONObject().apply { put("outbound", "direct") })
            RoutingMode.RULE -> {
                if (bypassLan) {
                    rules.put(JSONObject().apply {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    })
                }
                if (bypassIran) {
                    rules.put(JSONObject().apply {
                        put("geoip", JSONArray().put("ir"))
                        put("outbound", "direct")
                    })
                }
                rules.put(JSONObject().apply { put("outbound", "proxy") })
            }
        }
        root.put("route", JSONObject().apply {
            put("rules", rules)
            put("final", if (routingMode == RoutingMode.DIRECT) "direct" else "proxy")
            put("auto_detect_interface", true)
        })

        return root.toString()
    }

    private fun createOutboundForServer(server: ServerEntity): JSONObject {
        val out = JSONObject()
        val tag = "node-out"
        out.put("tag", tag)

        when (server.protocol.lowercase()) {
            "vless" -> {
                out.put("type", "vless")
                out.put("server", server.server)
                out.put("server_port", server.port)
                out.put("uuid", server.uuid)
                out.put("flow", if (server.security == "reality") "xtls-rprx-vision" else "")

                val tls = JSONObject().apply {
                    put("enabled", server.security == "tls" || server.security == "reality")
                    put("server_name", server.sni.ifBlank { server.server })
                    put("insecure", server.insecure)
                    if (server.security == "reality") {
                        val reality = JSONObject().apply {
                            put("enabled", true)
                            put("public_key", server.publicKey)
                            put("short_id", server.shortId)
                        }
                        put("reality", reality)
                    }
                    if (server.alpn.isNotBlank()) {
                        put("alpn", JSONArray(server.alpn.split(",")))
                    }
                }
                out.put("tls", tls)

                if (server.network == "ws") {
                    val transport = JSONObject().apply {
                        put("type", "ws")
                        put("path", server.path)
                        val headers = JSONObject()
                        if (server.host.isNotBlank()) headers.put("Host", server.host)
                        put("headers", headers)
                    }
                    out.put("transport", transport)
                }
            }
            "vmess" -> {
                out.put("type", "vmess")
                out.put("server", server.server)
                out.put("server_port", server.port)
                out.put("uuid", server.uuid)
                out.put("security", "auto")
                out.put("alter_id", server.alterId)

                val tls = JSONObject().apply {
                    put("enabled", server.security == "tls")
                    put("server_name", server.sni.ifBlank { server.server })
                }
                out.put("tls", tls)
            }
            "trojan" -> {
                out.put("type", "trojan")
                out.put("server", server.server)
                out.put("server_port", server.port)
                out.put("password", server.uuid)

                val tls = JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.sni.ifBlank { server.server })
                }
                out.put("tls", tls)
            }
            "shadowsocks" -> {
                out.put("type", "shadowsocks")
                out.put("server", server.server)
                out.put("server_port", server.port)
                out.put("method", server.fingerprint.ifBlank { "chacha20-ietf-poly1305" })
                out.put("password", server.uuid)
            }
            "hysteria2" -> {
                out.put("type", "hysteria2")
                out.put("server", server.server)
                out.put("server_port", server.port)
                out.put("password", server.uuid)

                val tls = JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.sni.ifBlank { server.server })
                    put("insecure", server.insecure)
                    put("alpn", JSONArray().put("h3"))
                }
                out.put("tls", tls)
            }
            "ssh" -> {
                out.put("type", "ssh")
                out.put("server", server.server)
                out.put("server_port", server.port)
                out.put("user", server.host.ifBlank { "root" })
                out.put("password", server.uuid)
            }
            else -> {
                out.put("type", "vless")
                out.put("server", server.server)
                out.put("server_port", server.port)
                out.put("uuid", server.uuid)
            }
        }

        return out
    }
}
