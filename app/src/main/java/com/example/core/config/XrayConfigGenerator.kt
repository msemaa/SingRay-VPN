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
 *
 * Protocols Xray itself cannot speak (hysteria, hysteria2, tuic, anytls,
 * shadowtls, ssh) are rejected here on purpose so the core manager can switch
 * to sing-box instead of silently building a broken config.
 */
object XrayConfigGenerator {

    /** Protocols this core can actually carry. */
    val SUPPORTED = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "ss",
        "socks", "socks5", "http", "https", "wireguard", "wg"
    )

    fun supports(protocol: String): Boolean = protocol.lowercase() in SUPPORTED

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
        require(supports(server.protocol)) {
            "Protocol '" + server.protocol + "' is not supported by the Xray core"
        }

        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().put(dnsServer).put("8.8.8.8").put("localhost"))
            put("queryStrategy", "UseIP")
            put("disableCache", false)
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
                        put("domain", JSONArray().put("geosite:category-ir").put("regexp:.*\\.ir$"))
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

        root.put("policy", JSONObject().apply {
            put("levels", JSONObject().apply {
                put("8", JSONObject().apply {
                    put("handshake", 4)
                    put("connIdle", 300)
                    put("uplinkOnly", 1)
                    put("downlinkOnly", 1)
                    put("bufferSize", 512)
                })
            })
        })

        if (tunFd != null) {
            root.put("_tunFd", tunFd) // informational only; consumed by tun2socks
        }

        return root.toString(2)
    }

    private fun buildProxyOutbound(server: ServerEntity): JSONObject {
        val out = JSONObject().put("tag", "proxy")
        val protocol = server.protocol.lowercase()
        var needsStream = true

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
                            // Vision flow is only valid over raw TCP.
                            val raw = server.network.isBlank() ||
                                server.network.equals("tcp", true) ||
                                server.network.equals("raw", true)
                            if (raw && server.security.equals("reality", true)) {
                                put("flow", "xtls-rprx-vision")
                            }
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
                        put("uot", false)
                        put("level", 8)
                    }))
                })
            }
            "socks", "socks5" -> {
                out.put("protocol", "socks")
                out.put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", server.server)
                        put("port", server.port)
                        if (server.host.isNotBlank()) {
                            put("users", JSONArray().put(JSONObject().apply {
                                put("user", server.host)
                                put("pass", server.uuid)
                                put("level", 8)
                            }))
                        }
                    }))
                })
            }
            "http", "https" -> {
                out.put("protocol", "http")
                out.put("settings", JSONObject().apply {
                    put("servers", JSONArray().put(JSONObject().apply {
                        put("address", server.server)
                        put("port", server.port)
                        if (server.host.isNotBlank()) {
                            put("users", JSONArray().put(JSONObject().apply {
                                put("user", server.host)
                                put("pass", server.uuid)
                            }))
                        }
                    }))
                })
            }
            "wireguard", "wg" -> {
                needsStream = false
                out.put("protocol", "wireguard")
                out.put("settings", JSONObject().apply {
                    put("secretKey", server.uuid)
                    put("address", JSONArray().apply {
                        val addr = server.path.ifBlank { "172.16.0.2/32" }
                        addr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
                    })
                    put("peers", JSONArray().put(JSONObject().apply {
                        put("endpoint", server.server + ":" + server.port)
                        put("publicKey", server.publicKey)
                        if (server.shortId.isNotBlank()) put("preSharedKey", server.shortId)
                        put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
                    }))
                    put("mtu", 1408)
                })
            }
            else -> throw IllegalArgumentException(
                "Protocol '" + server.protocol + "' is not supported by the Xray core"
            )
        }

        if (needsStream) {
            out.put("streamSettings", buildStreamSettings(server))
            out.put("mux", JSONObject().apply {
                put("enabled", false)
                put("concurrency", -1)
            })
        }
        return out
    }

    private fun buildStreamSettings(server: ServerEntity): JSONObject {
        val stream = JSONObject()
        val network = server.network.ifBlank { "tcp" }.lowercase()
        val security = server.security.lowercase()
        stream.put(
            "network",
            when (network) {
                "h2", "h2c" -> "http"
                "raw" -> "tcp"
                "splithttp" -> "xhttp"
                "quic" -> "tcp" // Xray has no bare QUIC transport for these protocols
                else -> network
            }
        )

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
            "tls", "xtls" -> {
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
            "ws", "websocket" -> stream.put("wsSettings", JSONObject().apply {
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
                put("idle_timeout", 60)
                put("health_check_timeout", 20)
            })
            "h2", "h2c", "http" -> stream.put("httpSettings", JSONObject().apply {
                put("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) {
                    put("host", JSONArray().put(server.host))
                }
            })
            "tcp", "raw", "" -> {
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
