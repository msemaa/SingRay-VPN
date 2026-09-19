package com.example.core.config

import com.example.data.entity.ServerEntity
import com.example.model.RoutingMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a runtime Xray-core configuration (Xray 1.8+ / 25.x schema).
 *
 * Inbounds are a SOCKS5 + HTTP pair on localhost. When a TUN fd is available
 * the VpnService feeds it through tun2socks into the SOCKS inbound, which is
 * how every Xray-based Android client works (Xray itself has no tun inbound).
 */
object XrayConfigGenerator {

    fun generate(
        server: ServerEntity,
        routingMode: RoutingMode = RoutingMode.RULE,
        bypassLan: Boolean = true,
        bypassDomestic: Boolean = true,
        dnsServer: String = "1.1.1.1",
        socksPort: Int = 10808,
        httpPort: Int = 10809,
        tunFd: Int? = null
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().put(dnsServer).put("8.8.8.8").put("localhost"))
            put("queryStrategy", "UseIP")
        })

        // ---- inbounds ----
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", socksPort)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("userLevel", 8)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                put("routeOnly", false)
            })
        })
        inbounds.put(JSONObject().apply {
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("port", httpPort)
            put("protocol", "http")
            put("settings", JSONObject().apply { put("userLevel", 8) })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
            })
        })
        root.put("inbounds", inbounds)

        // ---- outbounds ----
        val outbounds = JSONArray()
        outbounds.put(buildProxyOutbound(server))
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply { put("domainStrategy", "UseIP") })
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "http") })
            })
        })
        root.put("outbounds", outbounds)

        // ---- routing ----
        val rules = JSONArray()
        when (routingMode) {
            RoutingMode.GLOBAL -> rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "tcp,udp")
                put("outboundTag", "proxy")
            })
            RoutingMode.DIRECT -> rules.put(JSONObject().apply {
                put("type", "field")
                put("network", "tcp,udp")
                put("outboundTag", "direct")
            })
            RoutingMode.RULE -> {
                if (bypassLan) {
                    rules.put(JSONObject().apply {
                        put("type", "field")
                        put("ip", JSONArray().put("geoip:private"))
                        put("outboundTag", "direct")
                    })
                }
                if (bypassDomestic) {
                    rules.put(JSONObject().apply {
                        put("type", "field")
                        put("ip", JSONArray().put("geoip:ir"))
                        put("outboundTag", "direct")
                    })
                    rules.put(JSONObject().apply {
                        put("type", "field")
                        put("domain", JSONArray().put("geosite:category-ir"))
                        put("outboundTag", "direct")
                    })
                }
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "proxy")
                })
            }
        }
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", rules)
        })

        if (tunFd != null) {
            root.put("_tunFd", tunFd) // informational only; consumed by tun2socks
        }

        return root.toString(2)
    }

    private fun buildProxyOutbound(server: ServerEntity): JSONObject {
        val out = JSONObject().put("tag", "proxy")
        val protocol = server.protocol.lowercase()

        when (protocol) {
            "vless" -> {
                out.put("protocol", "vless")
                out.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", server.server)
                        put("port", server.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", server.uuid)
                            put("encryption", "none")
                            put("level", 8)
                            val flow = if (server.security.equals("reality", true)) {
                                server.fingerprint.takeIf { it.startsWith("xtls") } ?: "xtls-rprx-vision"
                            } else ""
                            if (flow.isNotBlank()) put("flow", flow)
                        }))
                    }))
                })
            }
            "vmess" -> {
                out.put("protocol", "vmess")
                out.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().put(JSONObject().apply {
                        put("address", server.server)
                        put("port", server.port)
                        put("users", JSONArray().put(JSONObject().apply {
                            put("id", server.uuid)
                            put("alterId", server.alterId)
                            put("security", "auto")
                            put("level", 8)
                        }))
                    }))
                })
            }
            "trojan" -> {
                out.put("protocol", "trojan")
                out.put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", server.server)
                        put("port", server.port)
                        put("password", server.uuid)
                        put("level", 8)
                    }))
                })
            }
            "shadowsocks", "ss" -> {
                out.put("protocol", "shadowsocks")
                out.put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", server.server)
                        put("port", server.port)
                        put("method", server.fingerprint.ifBlank { "chacha20-ietf-poly1305" })
                        put("password", server.uuid)
                        put("level", 8)
                    }))
                })
            }
            else -> {
                out.put("protocol", "freedom")
                out.put("settings", JSONObject())
                return out
            }
        }

        out.put("streamSettings", buildStreamSettings(server))
        out.put("mux", JSONObject().apply {
            put("enabled", false)
            put("concurrency", -1)
        })
        return out
    }

    private fun buildStreamSettings(server: ServerEntity): JSONObject {
        val stream = JSONObject()
        val network = server.network.ifBlank { "tcp" }.lowercase()
        val security = server.security.lowercase()
        stream.put("network", if (network == "h2") "http" else network)

        when (security) {
            "reality" -> {
                stream.put("security", "reality")
                stream.put("realitySettings", JSONObject().apply {
                    put("serverName", server.sni.ifBlank { server.server })
                    put("publicKey", server.publicKey)
                    put("shortId", server.shortId)
                    put("fingerprint", server.fingerprint.ifBlank { "chrome" })
                    put("spiderX", "/")
                    put("show", false)
                })
            }
            "tls" -> {
                stream.put("security", "tls")
                stream.put("tlsSettings", JSONObject().apply {
                    put("serverName", server.sni.ifBlank { server.host.ifBlank { server.server } })
                    put("allowInsecure", server.insecure)
                    put("fingerprint", server.fingerprint.ifBlank { "chrome" })
                    if (server.alpn.isNotBlank()) {
                        put("alpn", JSONArray(server.alpn.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                    }
                })
            }
            else -> stream.put("security", "none")
        }

        when (network) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                put("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) {
                    put("headers", JSONObject().apply { put("Host", server.host) })
                }
            })
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().apply {
                put("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) put("host", server.host)
            })
            "xhttp", "splithttp" -> stream.put("xhttpSettings", JSONObject().apply {
                put("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) put("host", server.host)
                put("mode", "auto")
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().apply {
                put("serviceName", server.path.trim('/'))
                put("multiMode", false)
            })
            "h2", "http" -> stream.put("httpSettings", JSONObject().apply {
                put("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) {
                    put("host", JSONArray().put(server.host))
                }
            })
            "tcp" -> {
                if (server.host.isNotBlank() && server.path.isNotBlank()) {
                    stream.put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                            put("request", JSONObject().apply {
                                put("path", JSONArray().put(server.path))
                                put("headers", JSONObject().apply {
                                    put("Host", JSONArray().put(server.host))
                                })
                            })
                        })
                    })
                }
            }
        }

        stream.put("sockopt", JSONObject().apply {
            put("tcpKeepAliveIdle", 100)
            put("tcpNoDelay", true)
            put("mark", 255)
        })
        return stream
    }
}
