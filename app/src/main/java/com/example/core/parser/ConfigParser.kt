package com.example.core.parser

import android.util.Base64
import com.example.data.entity.ServerEntity
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Share-link parser covering every format that mainstream clients emit.
 *
 * Supported schemes:
 *  vless, vmess (base64-JSON and plain URI), trojan, ss, ssr, hysteria,
 *  hysteria2/hy2, tuic, anytls, shadowtls, socks/socks5, http/https,
 *  wireguard/wg, ssh
 *
 * Everything is normalised into [ServerEntity]. Fields that have no dedicated
 * column are packed into existing ones with a fixed convention:
 *  - `fingerprint` doubles as the Shadowsocks cipher
 *  - `path` doubles as the TUIC password / Hysteria2 obfs password /
 *    WireGuard local address
 *  - `host` doubles as the SSH and SOCKS/HTTP username
 */
object ConfigParser {

    fun parseContent(rawContent: String, subscriptionId: Long? = null): List<ServerEntity> {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return emptyList()

        val decoded = tryDecodeBase64(trimmed) ?: trimmed
        val servers = mutableListOf<ServerEntity>()

        for (line in decoded.lines()) {
            val cleanLine = line.trim()
            if (cleanLine.isBlank() || cleanLine.startsWith("#") || cleanLine.startsWith("//")) continue
            parseSingleLink(cleanLine, subscriptionId)?.let { servers.add(it) }
        }

        return servers
    }

    fun parseSingleLink(rawUri: String, subscriptionId: Long? = null): ServerEntity? {
        val t = rawUri.trim()
        val scheme = t.substringBefore("://", "").lowercase()
        return try {
            when (scheme) {
                "vless" -> parseVless(t, subscriptionId)
                "vmess" -> parseVmess(t, subscriptionId)
                "trojan", "trojan-go" -> parseTrojan(t, subscriptionId)
                "ss" -> parseShadowsocks(t, subscriptionId)
                "ssr" -> parseShadowsocksR(t, subscriptionId)
                "hysteria2", "hy2" -> parseHysteria2(t, subscriptionId)
                "hysteria", "hy" -> parseHysteria1(t, subscriptionId)
                "tuic" -> parseTuic(t, subscriptionId)
                "anytls" -> parseAnyTls(t, subscriptionId)
                "shadowtls" -> parseShadowTls(t, subscriptionId)
                "socks", "socks5" -> parseSocksOrHttp(t, "socks", subscriptionId)
                "http", "https" -> parseSocksOrHttp(t, "http", subscriptionId)
                "wireguard", "wg" -> parseWireGuard(t, subscriptionId)
                "ssh" -> parseSsh(t, subscriptionId)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- v2ray

    private fun parseVless(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host?.trim('[', ']') ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQueryParams(uri.rawQuery ?: "")
        val name = decodeFragment(uri.rawFragment ?: "")

        return ServerEntity(
            name = name.ifBlank { "VLESS - $host:$port" },
            protocol = "vless",
            server = host,
            port = port,
            uuid = decodeFragment(uri.userInfo ?: ""),
            security = normaliseSecurity(q["security"]),
            sni = q["sni"] ?: q["peer"] ?: "",
            publicKey = q["pbk"] ?: "",
            shortId = q["sid"] ?: "",
            fingerprint = q["fp"] ?: "chrome",
            network = normaliseNetwork(q["type"]),
            path = q["path"] ?: q["serviceName"] ?: "",
            host = q["host"] ?: "",
            alpn = q["alpn"] ?: "",
            insecure = isTrue(q["allowInsecure"]) || isTrue(q["insecure"]),
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseVmess(uriStr: String, subId: Long?): ServerEntity? {
        val payload = uriStr.substringAfter("vmess://").trim()

        // Plain-URI variant used by a few panels: vmess://uuid@host:port?...
        if (payload.contains("@")) {
            val uri = URI(uriStr)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val q = parseQueryParams(uri.rawQuery ?: "")
            return ServerEntity(
                name = decodeFragment(uri.rawFragment ?: "").ifBlank { "VMess - $host:$port" },
                protocol = "vmess",
                server = host,
                port = port,
                uuid = uri.userInfo ?: "",
                security = normaliseSecurity(q["security"] ?: q["tls"]),
                sni = q["sni"] ?: "",
                network = normaliseNetwork(q["type"]),
                path = q["path"] ?: "",
                host = q["host"] ?: "",
                alpn = q["alpn"] ?: "",
                subscriptionId = subId,
                rawUri = uriStr
            )
        }

        val jsonStr = decodeBase64Safe(payload) ?: return null
        val json = JSONObject(jsonStr)

        val host = json.optString("add", "")
        if (host.isBlank()) return null
        val port = json.optString("port", "443").toIntOrNull() ?: 443
        val hostHeader = json.optString("host", "")
        val tls = json.optString("tls", "")
        val net = json.optString("net", "tcp")

        return ServerEntity(
            name = json.optString("ps", "").ifBlank { "VMess - $host:$port" },
            protocol = "vmess",
            server = host,
            port = port,
            uuid = json.optString("id", ""),
            alterId = json.optString("aid", "0").toIntOrNull() ?: 0,
            security = normaliseSecurity(tls),
            sni = json.optString("sni", "").ifBlank { hostHeader },
            fingerprint = json.optString("fp", "chrome"),
            network = normaliseNetwork(net),
            path = json.optString("path", "").ifBlank { json.optString("serviceName", "") },
            host = hostHeader,
            alpn = json.optString("alpn", ""),
            insecure = json.optString("allowInsecure", "") == "true",
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseTrojan(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQueryParams(uri.rawQuery ?: "")

        return ServerEntity(
            name = decodeFragment(uri.rawFragment ?: "").ifBlank { "Trojan - $host:$port" },
            protocol = "trojan",
            server = host,
            port = port,
            uuid = decodeFragment(uri.userInfo ?: ""),
            security = "tls",
            sni = q["sni"] ?: q["peer"] ?: "",
            fingerprint = q["fp"] ?: "chrome",
            network = normaliseNetwork(q["type"]),
            path = q["path"] ?: q["serviceName"] ?: "",
            host = q["host"] ?: "",
            alpn = q["alpn"] ?: "",
            insecure = isTrue(q["allowInsecure"]) || isTrue(q["insecure"]),
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    // -------------------------------------------------------- shadowsocks

    private fun parseShadowsocks(uriStr: String, subId: Long?): ServerEntity? {
        val name = decodeFragment(uriStr.substringAfter("#", ""))
        var body = uriStr.substringBefore("#").substringAfter("ss://")
        val query = if (body.contains("?")) body.substringAfter("?") else ""
        body = body.substringBefore("?")

        var method = "chacha20-ietf-poly1305"
        var password = ""
        var host: String
        var port: Int

        if (body.contains("@")) {
            val userPart = body.substringBeforeLast("@")
            val hostPart = body.substringAfterLast("@").trimEnd('/')
            val decodedUser = decodeBase64Safe(userPart) ?: URLDecoder.decode(userPart, "UTF-8")
            if (decodedUser.contains(":")) {
                method = decodedUser.substringBefore(":")
                password = decodedUser.substringAfter(":")
            } else {
                password = decodedUser
            }
            host = hostPart.substringBeforeLast(":").trim('[', ']')
            port = hostPart.substringAfterLast(":").toIntOrNull() ?: 8388
        } else {
            val decodedAll = decodeBase64Safe(body) ?: return null
            if (!decodedAll.contains("@")) return null
            val userPart = decodedAll.substringBeforeLast("@")
            val hostPart = decodedAll.substringAfterLast("@")
            method = userPart.substringBefore(":")
            password = userPart.substringAfter(":")
            host = hostPart.substringBeforeLast(":").trim('[', ']')
            port = hostPart.substringAfterLast(":").toIntOrNull() ?: 8388
        }

        if (host.isBlank()) return null
        val q = parseQueryParams(query)

        return ServerEntity(
            name = name.ifBlank { "Shadowsocks - $host:$port" },
            protocol = "shadowsocks",
            server = host,
            port = port,
            uuid = password,
            fingerprint = method,
            path = q["plugin"] ?: "",
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    /** SSR links are a fully base64 blob: host:port:proto:method:obfs:base64pass/?params */
    private fun parseShadowsocksR(uriStr: String, subId: Long?): ServerEntity? {
        val blob = decodeBase64Safe(uriStr.substringAfter("ssr://").trim()) ?: return null
        val main = blob.substringBefore("/?")
        val parts = main.split(":")
        if (parts.size < 6) return null

        val host = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        val method = parts[3]
        val password = decodeBase64Safe(parts[5]) ?: parts[5]

        val q = parseQueryParams(blob.substringAfter("/?", ""))
        val remarks = q["remarks"]?.let { decodeBase64Safe(it) } ?: ""

        return ServerEntity(
            name = remarks.ifBlank { "SSR - $host:$port" },
            protocol = "shadowsocks",
            server = host,
            port = port,
            uuid = password,
            fingerprint = method,
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    // ------------------------------------------------------------- quic

    private fun parseHysteria2(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQueryParams(uri.rawQuery ?: "")

        return ServerEntity(
            name = decodeFragment(uri.rawFragment ?: "").ifBlank { "Hysteria2 - $host:$port" },
            protocol = "hysteria2",
            server = host,
            port = port,
            uuid = decodeFragment(uri.userInfo ?: ""),
            security = "tls",
            sni = q["sni"] ?: q["peer"] ?: "",
            path = q["obfs-password"] ?: "",
            alpn = q["alpn"] ?: "h3",
            insecure = isTrue(q["insecure"]) || isTrue(q["allowInsecure"]),
            network = "quic",
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseHysteria1(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQueryParams(uri.rawQuery ?: "")

        return ServerEntity(
            name = decodeFragment(uri.rawFragment ?: "").ifBlank { "Hysteria - $host:$port" },
            protocol = "hysteria",
            server = host,
            port = port,
            uuid = q["auth"] ?: decodeFragment(uri.userInfo ?: ""),
            security = "tls",
            sni = q["peer"] ?: q["sni"] ?: "",
            alpn = q["alpn"] ?: "hysteria",
            insecure = isTrue(q["insecure"]),
            network = "quic",
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseTuic(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQueryParams(uri.rawQuery ?: "")
        val userInfo = decodeFragment(uri.userInfo ?: "")
        val uuid = userInfo.substringBefore(":")
        val password = if (userInfo.contains(":")) userInfo.substringAfter(":") else (q["password"] ?: "")

        return ServerEntity(
            name = decodeFragment(uri.rawFragment ?: "").ifBlank { "TUIC - $host:$port" },
            protocol = "tuic",
            server = host,
            port = port,
            uuid = uuid,
            path = password,
            security = "tls",
            sni = q["sni"] ?: "",
            alpn = q["alpn"] ?: "h3",
            insecure = isTrue(q["allow_insecure"]) || isTrue(q["insecure"]),
            network = "quic",
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    // ------------------------------------------------------------- other

    private fun parseAnyTls(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQueryParams(uri.rawQuery ?: "")

        return ServerEntity(
            name = decodeFragment(uri.rawFragment ?: "").ifBlank { "AnyTLS - $host:$port" },
            protocol = "anytls",
            server = host,
            port = port,
            uuid = decodeFragment(uri.userInfo ?: ""),
            security = "tls",
            sni = q["sni"] ?: "",
            insecure = isTrue(q["insecure"]) || isTrue(q["allowInsecure"]),
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseShadowTls(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val q = parseQueryParams(uri.rawQuery ?: "")

        return ServerEntity(
            name = decodeFragment(uri.rawFragment ?: "").ifBlank { "ShadowTLS - $host:$port" },
            protocol = "shadowtls",
            server = host,
            port = port,
            uuid = decodeFragment(uri.userInfo ?: ""),
            security = "tls",
            sni = q["sni"] ?: "",
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseSocksOrHttp(uriStr: String, protocol: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else if (protocol == "socks") 1080 else 8080
        var user = ""
        var pass = ""
        val info = uri.userInfo ?: ""
        if (info.isNotBlank()) {
            val decoded = if (info.contains(":")) info else (decodeBase64Safe(info) ?: info)
            user = decoded.substringBefore(":")
            pass = if (decoded.contains(":")) decoded.substringAfter(":") else ""
        }

        return ServerEntity(
            name = decodeFragment(uri.rawFragment ?: "").ifBlank { protocol.uppercase() + " - $host:$port" },
            protocol = protocol,
            server = host,
            port = port,
            uuid = pass,
            host = user,
            security = if (uriStr.startsWith("https://", true)) "tls" else "none",
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseWireGuard(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 51820
        val q = parseQueryParams(uri.rawQuery ?: "")

        return ServerEntity(
            name = decodeFragment(uri.rawFragment ?: "").ifBlank { "WireGuard - $host:$port" },
            protocol = "wireguard",
            server = host,
            port = port,
            uuid = decodeFragment(uri.userInfo ?: ""),      // private key
            publicKey = q["publickey"] ?: q["pubkey"] ?: "",
            shortId = q["presharedkey"] ?: "",
            path = q["address"] ?: q["ip"] ?: "",
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    private fun parseSsh(uriStr: String, subId: Long?): ServerEntity? {
        val uri = URI(uriStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 22
        val info = decodeFragment(uri.userInfo ?: "")

        return ServerEntity(
            name = decodeFragment(uri.rawFragment ?: "").ifBlank { "SSH - $host:$port" },
            protocol = "ssh",
            server = host,
            port = port,
            uuid = if (info.contains(":")) info.substringAfter(":") else "",
            host = if (info.contains(":")) info.substringBefore(":") else info,
            subscriptionId = subId,
            rawUri = uriStr
        )
    }

    // ------------------------------------------------------------ helpers

    private fun normaliseSecurity(raw: String?): String = when (raw?.lowercase()) {
        null, "", "none", "0", "false" -> "none"
        "reality" -> "reality"
        "xtls" -> "xtls"
        else -> "tls"
    }

    private fun normaliseNetwork(raw: String?): String = when (raw?.lowercase()) {
        null, "", "tcp", "raw", "none" -> "tcp"
        "websocket" -> "ws"
        "h2", "h2c" -> "http"
        "xhttp", "splithttp" -> "xhttp"
        else -> raw.lowercase()
    }

    private fun isTrue(raw: String?): Boolean =
        raw == "1" || raw.equals("true", true) || raw.equals("yes", true)

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            if (param.isBlank()) continue
            val parts = param.split("=", limit = 2)
            val key = try {
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
            } catch (_: Exception) { parts[0] }
            val value = if (parts.size == 2) {
                try {
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                } catch (_: Exception) { parts[1] }
            } else ""
            if (key.isNotBlank()) result[key.lowercase()] = value
        }
        return result
    }

    private fun decodeFragment(fragment: String): String = try {
        URLDecoder.decode(fragment, StandardCharsets.UTF_8.name())
    } catch (e: Exception) {
        fragment
    }

    private fun tryDecodeBase64(input: String): String? = try {
        val sanitized = input.replace("\r", "").replace("\n", "").trim()
        if (sanitized.contains(" ") || sanitized.contains("://")) null
        else String(
            Base64.decode(sanitized, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE),
            StandardCharsets.UTF_8
        )
    } catch (e: Exception) {
        null
    }

    private fun decodeBase64Safe(input: String): String? = try {
        val pad = when (input.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        val clean = (input + pad).replace("-", "+").replace("_", "/")
        String(Base64.decode(clean, Base64.DEFAULT), StandardCharsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
