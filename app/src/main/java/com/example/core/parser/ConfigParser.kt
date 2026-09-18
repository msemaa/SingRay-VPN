package com.example.core.parser

import android.util.Base64
import com.example.data.entity.ServerEntity
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ConfigParser {

    fun parseContent(rawContent: String, subscriptionId: Long? = null): List<ServerEntity> {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Check if content is whole base64 encoded subscription
        val decoded = tryDecodeBase64(trimmed) ?: trimmed

        val servers = mutableListOf<ServerEntity>()
        val lines = decoded.lines()

        for (line in lines) {
            val cleanLine = line.trim()
            if (cleanLine.isBlank() || cleanLine.startsWith("#") || cleanLine.startsWith("//")) continue
            val server = parseSingleLink(cleanLine, subscriptionId)
            if (server != null) {
                servers.add(server)
            }
        }

        return servers
    }

    fun parseSingleLink(rawUri: String, subscriptionId: Long? = null): ServerEntity? {
        val trimmed = rawUri.trim()
        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed, subscriptionId)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed, subscriptionId)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed, subscriptionId)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed, subscriptionId)
                trimmed.startsWith("hysteria2://", ignoreCase = true) || trimmed.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(trimmed, subscriptionId)
                trimmed.startsWith("ssh://", ignoreCase = true) -> parseSsh(trimmed, subscriptionId)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVless(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val userInfo = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        val queryParams = parseQueryParams(uri.rawQuery ?: "")
        val fragment = decodeFragment(uri.rawFragment ?: "$host:$port")

        val security = queryParams["security"] ?: "none"
        val sni = queryParams["sni"] ?: queryParams["host"] ?: ""
        val pbk = queryParams["pbk"] ?: ""
        val sid = queryParams["sid"] ?: ""
        val fp = queryParams["fp"] ?: "chrome"
        val type = queryParams["type"] ?: "tcp"
        val path = queryParams["path"] ?: ""
        val hostHeader = queryParams["host"] ?: ""
        val alpn = queryParams["alpn"] ?: ""

        return ServerEntity(
            name = fragment.ifBlank { "VLESS - $host:$port" },
            protocol = "vless",
            server = host,
            port = port,
            uuid = userInfo,
            security = security,
            sni = sni,
            publicKey = pbk,
            shortId = sid,
            fingerprint = fp,
            network = type,
            path = path,
            host = hostHeader,
            alpn = alpn,
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseVmess(uriStr: String, subId: Long?): ServerEntity? {
        val base64Data = uriStr.substringAfter("vmess://").trim()
        val jsonStr = decodeBase64Safe(base64Data) ?: return null
        val json = JSONObject(jsonStr)

        val host = json.optString("add", "")
        val port = json.optInt("port", 443)
        val id = json.optString("id", "")
        val aid = json.optInt("aid", 0)
        val ps = json.optString("ps", "$host:$port")
        val net = json.optString("net", "tcp")
        val type = json.optString("type", "none")
        val hostHeader = json.optString("host", "")
        val path = json.optString("path", "")
        val tls = json.optString("tls", "")
        val sni = json.optString("sni", hostHeader)

        return ServerEntity(
            name = ps.ifBlank { "VMess - $host:$port" },
            protocol = "vmess",
            server = host,
            port = port,
            uuid = id,
            alterId = aid,
            security = if (tls.equals("tls", true)) "tls" else "none",
            sni = sni,
            network = net,
            path = path,
            host = hostHeader,
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseTrojan(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        val queryParams = parseQueryParams(uri.rawQuery ?: "")
        val fragment = decodeFragment(uri.rawFragment ?: "$host:$port")

        val sni = queryParams["sni"] ?: queryParams["peer"] ?: host
        val type = queryParams["type"] ?: "tcp"
        val path = queryParams["path"] ?: ""

        return ServerEntity(
            name = fragment.ifBlank { "Trojan - $host:$port" },
            protocol = "trojan",
            server = host,
            port = port,
            uuid = password,
            security = "tls",
            sni = sni,
            network = type,
            path = path,
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseShadowsocks(uriStr: String, subId: Long?): ServerEntity? {
        val fragment = uriStr.substringAfter("#", "")
        val decodedFragment = decodeFragment(fragment)
        val mainPart = uriStr.substringBefore("#").substringAfter("ss://")

        var method = "chacha20-ietf-poly1305"
        var password = ""
        var host = ""
        var port = 8388

        if (mainPart.contains("@")) {
            val userPart = mainPart.substringBefore("@")
            val hostPart = mainPart.substringAfter("@")
            val decodedUser = decodeBase64Safe(userPart) ?: userPart
            if (decodedUser.contains(":")) {
                method = decodedUser.substringBefore(":")
                password = decodedUser.substringAfter(":")
            }
            if (hostPart.contains(":")) {
                host = hostPart.substringBefore(":")
                port = hostPart.substringAfter(":").substringBefore("/").toIntOrNull() ?: 8388
            }
        } else {
            val decodedAll = decodeBase64Safe(mainPart) ?: return null
            if (decodedAll.contains("@") && decodedAll.contains(":")) {
                val userPart = decodedAll.substringBefore("@")
                val hostPart = decodedAll.substringAfter("@")
                method = userPart.substringBefore(":")
                password = userPart.substringAfter(":")
                host = hostPart.substringBefore(":")
                port = hostPart.substringAfter(":").toIntOrNull() ?: 8388
            }
        }

        if (host.isBlank()) return null

        return ServerEntity(
            name = decodedFragment.ifBlank { "Shadowsocks - $host:$port" },
            protocol = "shadowsocks",
            server = host,
            port = port,
            uuid = password,
            fingerprint = method, // store cipher method here
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseHysteria2(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val auth = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        val queryParams = parseQueryParams(uri.rawQuery ?: "")
        val fragment = decodeFragment(uri.rawFragment ?: "$host:$port")

        val sni = queryParams["sni"] ?: host
        val insecure = queryParams["insecure"] == "1" || queryParams["insecure"] == "true"
        val alpn = queryParams["alpn"] ?: "h3"

        return ServerEntity(
            name = fragment.ifBlank { "Hysteria 2 - $host:$port" },
            protocol = "hysteria2",
            server = host,
            port = port,
            uuid = auth,
            security = "tls",
            sni = sni,
            alpn = alpn,
            insecure = insecure,
            network = "quic",
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseSsh(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val userInfo = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 22
        val fragment = decodeFragment(uri.rawFragment ?: "$host:$port")

        val user = if (userInfo.contains(":")) userInfo.substringBefore(":") else userInfo
        val pass = if (userInfo.contains(":")) userInfo.substringAfter(":") else ""

        return ServerEntity(
            name = fragment.ifBlank { "SSH - $host:$port" },
            protocol = "ssh",
            server = host,
            port = port,
            uuid = pass, // password
            host = user, // username
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            val parts = param.split("=")
            if (parts.size == 2) {
                val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                result[key] = value
            }
        }
        return result
    }

    private fun decodeFragment(fragment: String): String {
        return try {
            URLDecoder.decode(fragment, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            fragment
        }
    }

    private fun tryDecodeBase64(input: String): String? {
        return try {
            val sanitized = input.replace("\r", "").replace("\n", "").trim()
            if (sanitized.contains(" ") || sanitized.contains("://")) return null
            val decodedBytes = Base64.decode(sanitized, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE)
            String(decodedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBase64Safe(input: String): String? {
        return try {
            val pad = when (input.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            val clean = (input + pad).replace("-", "+").replace("_", "/")
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
