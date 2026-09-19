using System.Text.Json;
using System.Text.Json.Nodes;
using SingRay.Models;

namespace SingRay.Core;

/// Builds a sing-box 1.11+ configuration for the Windows client.
public static class SingBoxConfigBuilder
{
    public static string Build(ServerProfile p, AppSettings s)
    {
        var root = new JsonObject
        {
            ["log"] = new JsonObject { ["level"] = "warn", ["timestamp"] = true },
            ["dns"] = new JsonObject
            {
                ["servers"] = new JsonArray
                {
                    new JsonObject { ["tag"] = "remote", ["address"] = "https://" + s.Dns + "/dns-query", ["detour"] = "proxy" },
                    new JsonObject { ["tag"] = "local", ["address"] = "udp://8.8.8.8", ["detour"] = "direct" }
                },
                ["strategy"] = "prefer_ipv4",
                ["independent_cache"] = true
            }
        };

        var inbounds = new JsonArray
        {
            new JsonObject
            {
                ["type"] = "mixed",
                ["tag"] = "mixed-in",
                ["listen"] = "127.0.0.1",
                ["listen_port"] = s.SocksPort,
                ["sniff"] = true
            },
            new JsonObject
            {
                ["type"] = "http",
                ["tag"] = "http-in",
                ["listen"] = "127.0.0.1",
                ["listen_port"] = s.HttpPort,
                ["sniff"] = true
            }
        };

        if (s.Tunnel == TunnelMode.Tun)
        {
            // Wintun based device tunnel. Requires elevation on Windows.
            inbounds.Add(new JsonObject
            {
                ["type"] = "tun",
                ["tag"] = "tun-in",
                ["address"] = new JsonArray { "172.19.0.1/30" },
                ["mtu"] = 9000,
                ["auto_route"] = true,
                ["strict_route"] = true,
                ["stack"] = "mixed",
                ["sniff"] = true
            });
        }

        root["inbounds"] = inbounds;

        root["outbounds"] = new JsonArray
        {
            BuildOutbound(p),
            new JsonObject { ["type"] = "direct", ["tag"] = "direct" },
            new JsonObject { ["type"] = "block", ["tag"] = "block" },
            new JsonObject { ["type"] = "dns", ["tag"] = "dns-out" }
        };

        var rules = new JsonArray
        {
            new JsonObject { ["protocol"] = "dns", ["outbound"] = "dns-out" }
        };

        switch (s.Routing)
        {
            case RoutingMode.Global:
                rules.Add(new JsonObject { ["outbound"] = "proxy" });
                break;
            case RoutingMode.Direct:
                rules.Add(new JsonObject { ["outbound"] = "direct" });
                break;
            default:
                if (s.BypassLan)
                    rules.Add(new JsonObject { ["ip_is_private"] = true, ["outbound"] = "direct" });
                if (s.BypassDomestic)
                    rules.Add(new JsonObject { ["geoip"] = new JsonArray { "ir" }, ["outbound"] = "direct" });
                rules.Add(new JsonObject { ["outbound"] = "proxy" });
                break;
        }

        root["route"] = new JsonObject
        {
            ["rules"] = rules,
            ["final"] = s.Routing == RoutingMode.Direct ? "direct" : "proxy",
            ["auto_detect_interface"] = true
        };

        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    private static JsonObject BuildOutbound(ServerProfile p)
    {
        var o = new JsonObject { ["tag"] = "proxy" };

        switch (p.Protocol)
        {
            case ProxyProtocol.Vless:
                o["type"] = "vless";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["uuid"] = p.Credential;
                if (!string.IsNullOrEmpty(p.Flow)) o["flow"] = p.Flow;
                AddTls(o, p);
                AddTransport(o, p);
                break;

            case ProxyProtocol.Vmess:
                o["type"] = "vmess";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["uuid"] = p.Credential;
                o["security"] = "auto";
                o["alter_id"] = p.AlterId;
                AddTls(o, p);
                AddTransport(o, p);
                break;

            case ProxyProtocol.Trojan:
                o["type"] = "trojan";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["password"] = p.Credential;
                AddTls(o, p, forceTls: true);
                AddTransport(o, p);
                break;

            case ProxyProtocol.Shadowsocks:
                o["type"] = "shadowsocks";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["method"] = string.IsNullOrEmpty(p.Method) ? "chacha20-ietf-poly1305" : p.Method;
                o["password"] = p.Credential;
                break;

            case ProxyProtocol.Hysteria2:
                o["type"] = "hysteria2";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["password"] = p.Credential;
                AddTls(o, p, forceTls: true);
                break;

            case ProxyProtocol.Tuic:
                o["type"] = "tuic";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["uuid"] = p.PublicKey;
                o["password"] = p.Credential;
                o["congestion_control"] = "bbr";
                AddTls(o, p, forceTls: true);
                break;

            case ProxyProtocol.WireGuard:
                o["type"] = "wireguard";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["private_key"] = p.PrivateKey;
                o["peer_public_key"] = p.PeerPublicKey;
                o["local_address"] = new JsonArray { string.IsNullOrEmpty(p.LocalAddress) ? "172.16.0.2/32" : p.LocalAddress };
                if (!string.IsNullOrEmpty(p.PresharedKey)) o["pre_shared_key"] = p.PresharedKey;
                break;

            case ProxyProtocol.Socks:
                o["type"] = "socks";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                if (!string.IsNullOrEmpty(p.Host)) o["username"] = p.Host;
                if (!string.IsNullOrEmpty(p.Credential)) o["password"] = p.Credential;
                break;

            default:
                o["type"] = "direct";
                break;
        }

        return o;
    }

    private static void AddTls(JsonObject o, ServerProfile p, bool forceTls = false)
    {
        var isReality = string.Equals(p.Security, "reality", StringComparison.OrdinalIgnoreCase);
        var isTls = forceTls || isReality || string.Equals(p.Security, "tls", StringComparison.OrdinalIgnoreCase);
        if (!isTls) return;

        var tls = new JsonObject
        {
            ["enabled"] = true,
            ["server_name"] = !string.IsNullOrEmpty(p.Sni) ? p.Sni : (!string.IsNullOrEmpty(p.Host) ? p.Host : p.Address),
            ["insecure"] = p.AllowInsecure
        };

        if (!string.IsNullOrEmpty(p.Alpn))
        {
            var alpn = new JsonArray();
            foreach (var a in p.Alpn.Split(',', StringSplitOptions.RemoveEmptyEntries))
                alpn.Add(a.Trim());
            tls["alpn"] = alpn;
        }

        if (!string.IsNullOrEmpty(p.Fingerprint))
            tls["utls"] = new JsonObject { ["enabled"] = true, ["fingerprint"] = p.Fingerprint };

        if (isReality)
        {
            tls["reality"] = new JsonObject
            {
                ["enabled"] = true,
                ["public_key"] = p.PublicKey,
                ["short_id"] = p.ShortId
            };
            if (tls["utls"] is null)
                tls["utls"] = new JsonObject { ["enabled"] = true, ["fingerprint"] = "chrome" };
        }

        o["tls"] = tls;
    }

    private static void AddTransport(JsonObject o, ServerProfile p)
    {
        var net = (p.Network ?? "tcp").ToLowerInvariant();
        switch (net)
        {
            case "ws":
                var ws = new JsonObject
                {
                    ["type"] = "ws",
                    ["path"] = string.IsNullOrEmpty(p.Path) ? "/" : p.Path
                };
                if (!string.IsNullOrEmpty(p.Host))
                    ws["headers"] = new JsonObject { ["Host"] = p.Host };
                o["transport"] = ws;
                break;
            case "grpc":
                o["transport"] = new JsonObject
                {
                    ["type"] = "grpc",
                    ["service_name"] = (p.Path ?? string.Empty).Trim('/')
                };
                break;
            case "httpupgrade":
                var hu = new JsonObject
                {
                    ["type"] = "httpupgrade",
                    ["path"] = string.IsNullOrEmpty(p.Path) ? "/" : p.Path
                };
                if (!string.IsNullOrEmpty(p.Host)) hu["host"] = p.Host;
                o["transport"] = hu;
                break;
            case "http":
            case "h2":
                var h2 = new JsonObject
                {
                    ["type"] = "http",
                    ["path"] = string.IsNullOrEmpty(p.Path) ? "/" : p.Path
                };
                if (!string.IsNullOrEmpty(p.Host))
                    h2["host"] = new JsonArray { p.Host };
                o["transport"] = h2;
                break;
        }
    }
}
