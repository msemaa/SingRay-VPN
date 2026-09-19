using System.Text;
using System.Text.Json;
using SingRay.Models;

namespace SingRay.Core;

/// Parses share links and subscription payloads into ServerProfile objects.
/// Supports vless, vmess, trojan, ss, hysteria2/hy2, tuic, wireguard, socks, http,
/// plain lists and base64-encoded subscription bodies.
public static class ConfigParser
{
    private const char LF = '\u000A';
    private const char CR = '\u000D';

    public static List<ServerProfile> ParseContent(string content)
    {
        var result = new List<ServerProfile>();
        if (string.IsNullOrWhiteSpace(content)) return result;

        content = content.Trim();

        // The whole payload may be base64 (classic subscription body).
        if (!content.Contains("://") && TryDecodeBase64(content, out var decoded))
            content = decoded;

        foreach (var raw in content.Split(new[] { LF, CR }, StringSplitOptions.RemoveEmptyEntries))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith('#')) continue;
            var profile = ParseUri(line);
            if (profile != null) result.Add(profile);
        }
        return result;
    }

    public static ServerProfile? ParseUri(string uri)
    {
        try
        {
            var scheme = uri.Split("://")[0].ToLowerInvariant();
            return scheme switch
            {
                "vless" => ParseVless(uri),
                "vmess" => ParseVmess(uri),
                "trojan" => ParseTrojan(uri),
                "ss" => ParseShadowsocks(uri),
                "hysteria2" or "hy2" => ParseHysteria2(uri),
                "tuic" => ParseTuic(uri),
                "wireguard" or "wg" => ParseWireGuard(uri),
                "socks" or "socks5" => ParseSocksOrHttp(uri, ProxyProtocol.Socks),
                _ => null
            };
        }
        catch
        {
            return null;
        }
    }

    // ------------------------------------------------------------- helpers

    private static Dictionary<string, string> Query(string query)
    {
        var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrEmpty(query)) return dict;
        foreach (var pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var parts = pair.Split('=', 2);
            var key = Unescape(parts[0]);
            if (string.IsNullOrWhiteSpace(key)) continue;
            dict[key] = parts.Length > 1 ? Unescape(parts[1]) : string.Empty;
        }
        return dict;
    }

    private static string Unescape(string value)
    {
        try { return Uri.UnescapeDataString(value); } catch { return value; }
    }

    private static string Fragment(string uri)
    {
        var idx = uri.IndexOf('#');
        return idx >= 0 && idx < uri.Length - 1 ? Unescape(uri.Substring(idx + 1)) : string.Empty;
    }

    private static bool TryDecodeBase64(string input, out string output)
    {
        output = string.Empty;
        try
        {
            var s = input.Trim().Replace('-', '+').Replace('_', '/');
            switch (s.Length % 4)
            {
                case 2: s += "=="; break;
                case 3: s += "="; break;
                case 1: return false;
            }
            output = Encoding.UTF8.GetString(Convert.FromBase64String(s));
            return output.Length > 0;
        }
        catch
        {
            return false;
        }
    }

    private static void ApplyStream(ServerProfile p, Dictionary<string, string> q)
    {
        p.Network = q.GetValueOrDefault("type", q.GetValueOrDefault("net", "tcp"));
        p.Security = q.GetValueOrDefault("security", p.Security);
        p.Sni = q.GetValueOrDefault("sni", q.GetValueOrDefault("peer", string.Empty));
        p.Host = q.GetValueOrDefault("host", string.Empty);
        p.Path = q.GetValueOrDefault("path", q.GetValueOrDefault("serviceName", string.Empty));
        p.Alpn = q.GetValueOrDefault("alpn", string.Empty);
        p.Flow = q.GetValueOrDefault("flow", string.Empty);
        p.Fingerprint = q.GetValueOrDefault("fp", string.Empty);
        p.PublicKey = q.GetValueOrDefault("pbk", string.Empty);
        p.ShortId = q.GetValueOrDefault("sid", string.Empty);
        var insecure = q.GetValueOrDefault("allowInsecure", q.GetValueOrDefault("insecure", "0"));
        p.AllowInsecure = insecure is "1" or "true" or "True";
        if (string.IsNullOrEmpty(p.Security) && !string.IsNullOrEmpty(p.PublicKey)) p.Security = "reality";
        if (string.IsNullOrEmpty(p.Security)) p.Security = "none";
    }

    // ------------------------------------------------------------- parsers

    private static ServerProfile ParseVless(string uri)
    {
        var u = new Uri(uri);
        var q = Query(u.Query);
        var p = new ServerProfile
        {
            Protocol = ProxyProtocol.Vless,
            Credential = u.UserInfo,
            Address = u.Host,
            Port = u.Port > 0 ? u.Port : 443,
            Name = Fragment(uri),
            RawUri = uri
        };
        ApplyStream(p, q);
        if (string.IsNullOrWhiteSpace(p.Name)) p.Name = p.Endpoint;
        return p;
    }

    private static ServerProfile? ParseVmess(string uri)
    {
        var payload = uri.Substring("vmess://".Length);

        // Standard v2rayN base64 JSON form.
        if (TryDecodeBase64(payload, out var json) && json.TrimStart().StartsWith('{'))
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            string Get(string name, string fallback = "")
            {
                if (!root.TryGetProperty(name, out var v)) return fallback;
                return v.ValueKind == JsonValueKind.Number ? v.ToString() : (v.GetString() ?? fallback);
            }

            var tls = Get("tls");
            var p = new ServerProfile
            {
                Protocol = ProxyProtocol.Vmess,
                Name = Get("ps"),
                Address = Get("add"),
                Port = int.TryParse(Get("port", "443"), out var port) ? port : 443,
                Credential = Get("id"),
                AlterId = int.TryParse(Get("aid", "0"), out var aid) ? aid : 0,
                Network = Get("net", "tcp"),
                Host = Get("host"),
                Path = Get("path"),
                Sni = Get("sni"),
                Alpn = Get("alpn"),
                Fingerprint = Get("fp"),
                Security = string.IsNullOrEmpty(tls) ? "none" : tls,
                RawUri = uri
            };
            if (string.IsNullOrWhiteSpace(p.Name)) p.Name = p.Endpoint;
            return p;
        }

        try
        {
            var u = new Uri(uri);
            var q = Query(u.Query);
            var p = new ServerProfile
            {
                Protocol = ProxyProtocol.Vmess,
                Credential = u.UserInfo,
                Address = u.Host,
                Port = u.Port > 0 ? u.Port : 443,
                Name = Fragment(uri),
                RawUri = uri
            };
            ApplyStream(p, q);
            if (string.IsNullOrWhiteSpace(p.Name)) p.Name = p.Endpoint;
            return p;
        }
        catch
        {
            return null;
        }
    }

    private static ServerProfile ParseTrojan(string uri)
    {
        var u = new Uri(uri);
        var q = Query(u.Query);
        var p = new ServerProfile
        {
            Protocol = ProxyProtocol.Trojan,
            Credential = Unescape(u.UserInfo),
            Address = u.Host,
            Port = u.Port > 0 ? u.Port : 443,
            Name = Fragment(uri),
            Security = "tls",
            RawUri = uri
        };
        ApplyStream(p, q);
        if (p.Security == "none") p.Security = "tls";
        if (string.IsNullOrWhiteSpace(p.Name)) p.Name = p.Endpoint;
        return p;
    }

    private static ServerProfile? ParseShadowsocks(string uri)
    {
        var body = uri.Substring("ss://".Length);
        var name = Fragment(uri);
        var hashIdx = body.IndexOf('#');
        if (hashIdx >= 0) body = body.Substring(0, hashIdx);

        var query = string.Empty;
        var qIdx = body.IndexOf('?');
        if (qIdx >= 0)
        {
            query = body.Substring(qIdx);
            body = body.Substring(0, qIdx);
        }

        string method, password, host;
        int port;

        if (body.Contains('@'))
        {
            var at = body.LastIndexOf('@');
            var userInfo = body.Substring(0, at);
            var hostPart = body.Substring(at + 1);
            if (!userInfo.Contains(':') && TryDecodeBase64(userInfo, out var decodedUser))
                userInfo = decodedUser;
            var mp = userInfo.Split(':', 2);
            method = Unescape(mp[0]);
            password = mp.Length > 1 ? Unescape(mp[1]) : string.Empty;
            var hp = hostPart.Split(':');
            host = hp[0];
            port = hp.Length > 1 && int.TryParse(hp[1], out var pp) ? pp : 8388;
        }
        else
        {
            if (!TryDecodeBase64(body, out var plain)) return null;
            var at = plain.LastIndexOf('@');
            if (at < 0) return null;
            var mp = plain.Substring(0, at).Split(':', 2);
            method = mp[0];
            password = mp.Length > 1 ? mp[1] : string.Empty;
            var hp = plain.Substring(at + 1).Split(':');
            host = hp[0];
            port = hp.Length > 1 && int.TryParse(hp[1], out var pp) ? pp : 8388;
        }

        var q = Query(query);
        return new ServerProfile
        {
            Protocol = ProxyProtocol.Shadowsocks,
            Method = method,
            Credential = password,
            Address = host,
            Port = port,
            Name = string.IsNullOrWhiteSpace(name) ? host + ":" + port : name,
            Network = q.GetValueOrDefault("type", "tcp"),
            Path = q.GetValueOrDefault("path", string.Empty),
            Host = q.GetValueOrDefault("host", string.Empty),
            RawUri = uri
        };
    }

    private static ServerProfile ParseHysteria2(string uri)
    {
        var u = new Uri(uri);
        var q = Query(u.Query);
        var p = new ServerProfile
        {
            Protocol = ProxyProtocol.Hysteria2,
            Credential = Unescape(u.UserInfo),
            Address = u.Host,
            Port = u.Port > 0 ? u.Port : 443,
            Name = Fragment(uri),
            Security = "tls",
            Sni = q.GetValueOrDefault("sni", string.Empty),
            AllowInsecure = q.GetValueOrDefault("insecure", "0") is "1" or "true",
            RawUri = uri
        };
        if (string.IsNullOrWhiteSpace(p.Name)) p.Name = p.Endpoint;
        return p;
    }

    private static ServerProfile ParseTuic(string uri)
    {
        var u = new Uri(uri);
        var q = Query(u.Query);
        var info = Unescape(u.UserInfo).Split(':', 2);
        var p = new ServerProfile
        {
            Protocol = ProxyProtocol.Tuic,
            PublicKey = info[0],
            Credential = info.Length > 1 ? info[1] : string.Empty,
            Address = u.Host,
            Port = u.Port > 0 ? u.Port : 443,
            Name = Fragment(uri),
            Security = "tls",
            Sni = q.GetValueOrDefault("sni", string.Empty),
            Alpn = q.GetValueOrDefault("alpn", "h3"),
            AllowInsecure = q.GetValueOrDefault("allow_insecure", "0") is "1" or "true",
            RawUri = uri
        };
        if (string.IsNullOrWhiteSpace(p.Name)) p.Name = p.Endpoint;
        return p;
    }

    private static ServerProfile ParseWireGuard(string uri)
    {
        var u = new Uri(uri);
        var q = Query(u.Query);
        var p = new ServerProfile
        {
            Protocol = ProxyProtocol.WireGuard,
            PrivateKey = Unescape(u.UserInfo),
            Address = u.Host,
            Port = u.Port > 0 ? u.Port : 51820,
            PeerPublicKey = q.GetValueOrDefault("publickey", q.GetValueOrDefault("pbk", string.Empty)),
            LocalAddress = q.GetValueOrDefault("address", "172.16.0.2/32"),
            PresharedKey = q.GetValueOrDefault("presharedkey", string.Empty),
            Name = Fragment(uri),
            RawUri = uri
        };
        if (string.IsNullOrWhiteSpace(p.Name)) p.Name = p.Endpoint;
        return p;
    }

    private static ServerProfile ParseSocksOrHttp(string uri, ProxyProtocol protocol)
    {
        var u = new Uri(uri);
        var info = Unescape(u.UserInfo);
        if (!info.Contains(':') && TryDecodeBase64(info, out var decoded)) info = decoded;
        var parts = info.Split(':', 2);
        var p = new ServerProfile
        {
            Protocol = protocol,
            Host = parts.Length > 0 ? parts[0] : string.Empty,
            Credential = parts.Length > 1 ? parts[1] : string.Empty,
            Address = u.Host,
            Port = u.Port > 0 ? u.Port : 1080,
            Name = Fragment(uri),
            RawUri = uri
        };
        if (string.IsNullOrWhiteSpace(p.Name)) p.Name = p.Endpoint;
        return p;
    }
}
