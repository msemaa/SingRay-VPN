using System.Text.Json;
using System.Text.Json.Nodes;
using SingRay.Models;

namespace SingRay.Core;

/// Builds a sing-box 1.12+ / 1.14 configuration for the Windows client.
///
/// The previous revision emitted the legacy schema (address strings for DNS
/// servers, `block` / `dns` outbounds, bare `outbound` route rules, a
/// `wireguard` outbound) and, like the Android generator, gave `dns-direct`
/// a detour towards the plain `direct` outbound. sing-box 1.14 rejects that
/// detour outright ("detour to an empty direct outbound makes no sense"),
/// which aborts DNS start-up before any outbound is dialled.
public static class SingBoxConfigBuilder
{
    public static string Build(ServerProfile p, AppSettings s)
    {
        var root = new JsonObject
        {
            ["log"] = new JsonObject { ["level"] = "warn", ["timestamp"] = true },
            ["dns"] = BuildDns(s)
        };

        var inbounds = new JsonArray
        {
            new JsonObject
            {
                ["type"] = "mixed",
                ["tag"] = "mixed-in",
                ["listen"] = "127.0.0.1",
                ["listen_port"] = s.SocksPort
            },
            new JsonObject
            {
                ["type"] = "http",
                ["tag"] = "http-in",
                ["listen"] = "127.0.0.1",
                ["listen_port"] = s.HttpPort
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
                ["stack"] = "mixed"
            });
        }

        root["inbounds"] = inbounds;

        // WireGuard is an endpoint in the modern schema, not an outbound.
        if (p.Protocol == ProxyProtocol.WireGuard)
        {
            root["endpoints"] = new JsonArray { BuildWireGuardEndpoint(p) };
            root["outbounds"] = new JsonArray
            {
                new JsonObject { ["type"] = "direct", ["tag"] = "direct" }
            };
        }
        else
        {
            root["outbounds"] = new JsonArray
            {
                BuildOutbound(p),
                new JsonObject { ["type"] = "direct", ["tag"] = "direct" }
            };
        }

        // Sniffing and DNS hijacking are route actions now; the `dns` and
        // `block` outbounds were removed.
        var rules = new JsonArray
        {
            new JsonObject { ["action"] = "sniff" },
            new JsonObject { ["protocol"] = "dns", ["action"] = "hijack-dns" }
        };

        var useRuleSets = false;

        switch (s.Routing)
        {
            case RoutingMode.Global:
            case RoutingMode.Direct:
                // Handled by `final`.
                break;
            default:
                if (s.BypassLan)
                {
                    rules.Add(new JsonObject
                    {
                        ["ip_is_private"] = true,
                        ["action"] = "route",
                        ["outbound"] = "direct"
                    });
                }
                if (s.BypassDomestic)
                {
                    useRuleSets = true;
                    rules.Add(new JsonObject
                    {
                        ["rule_set"] = new JsonArray { "geosite-ir", "geoip-ir" },
                        ["action"] = "route",
                        ["outbound"] = "direct"
                    });
                    rules.Add(new JsonObject
                    {
                        ["domain_suffix"] = new JsonArray { ".ir" },
                        ["action"] = "route",
                        ["outbound"] = "direct"
                    });
                }
                break;
        }

        var route = new JsonObject
        {
            ["rules"] = rules,
            ["final"] = s.Routing == RoutingMode.Direct ? "direct" : "proxy",
            ["auto_detect_interface"] = true,
            // Resolves outbound server domains (including the proxy's own host)
            // without looping back through the tunnel.
            ["default_domain_resolver"] = new JsonObject { ["server"] = "dns-direct" }
        };

        if (useRuleSets)
            route["rule_set"] = BuildRuleSets();

        root["route"] = route;

        root["experimental"] = new JsonObject
        {
            ["cache_file"] = new JsonObject { ["enabled"] = true, ["store_fakeip"] = false }
        };

        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    // ------------------------------------------------------------------ dns

    private static JsonObject BuildDns(AppSettings s)
    {
        var configured = string.IsNullOrWhiteSpace(s.Dns) ? "https://1.1.1.1/dns-query" : s.Dns.Trim();

        var servers = new JsonArray
        {
            // Remote resolver: tunnelled through the proxy.
            DnsServer("dns-remote", configured, "proxy"),
            // Direct resolver: plain, NO detour. A detour towards the `direct`
            // outbound is rejected by sing-box 1.14.
            DnsServer("dns-direct", "local", "")
        };

        return new JsonObject
        {
            ["servers"] = servers,
            ["rules"] = new JsonArray
            {
                new JsonObject
                {
                    ["domain_suffix"] = new JsonArray { ".ir" },
                    ["server"] = "dns-direct"
                }
            },
            ["final"] = "dns-remote",
            ["strategy"] = "prefer_ipv4",
            ["independent_cache"] = true
        };
    }

    /// Auto-detects the DNS transport from the user supplied value:
    /// plain IP, udp://, tls://, quic://, https://, h3://, or local/system.
    private static JsonObject DnsServer(string tag, string raw, string detour)
    {
        var value = (raw ?? string.Empty).Trim();
        var o = new JsonObject { ["tag"] = tag };

        static string HostOf(string url)
        {
            var afterScheme = url[(url.IndexOf("://", StringComparison.Ordinal) + 3)..];
            var host = afterScheme.Split('/')[0];
            return host.Split('?')[0];
        }

        static string? PathOf(string url)
        {
            var afterScheme = url[(url.IndexOf("://", StringComparison.Ordinal) + 3)..];
            var slash = afterScheme.IndexOf('/');
            if (slash < 0) return null;
            var path = afterScheme[slash..].Split('?')[0];
            return string.IsNullOrEmpty(path) ? null : path;
        }

        if (value.Length == 0)
        {
            o["type"] = "udp";
            o["server"] = "1.1.1.1";
        }
        else if (value.Equals("local", StringComparison.OrdinalIgnoreCase) ||
                 value.Equals("system", StringComparison.OrdinalIgnoreCase))
        {
            o["type"] = "local";
        }
        else if (value.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            o["type"] = "https";
            o["server"] = HostOf(value);
            var path = PathOf(value);
            if (path is not null) o["path"] = path;
        }
        else if (value.StartsWith("h3://", StringComparison.OrdinalIgnoreCase))
        {
            o["type"] = "h3";
            o["server"] = HostOf(value);
            var path = PathOf(value);
            if (path is not null) o["path"] = path;
        }
        else if (value.StartsWith("quic://", StringComparison.OrdinalIgnoreCase))
        {
            o["type"] = "quic";
            o["server"] = HostOf(value);
        }
        else if (value.StartsWith("tls://", StringComparison.OrdinalIgnoreCase))
        {
            o["type"] = "tls";
            o["server"] = HostOf(value);
        }
        else if (value.StartsWith("udp://", StringComparison.OrdinalIgnoreCase) ||
                 value.StartsWith("dns://", StringComparison.OrdinalIgnoreCase))
        {
            o["type"] = "udp";
            o["server"] = HostOf(value);
        }
        else
        {
            o["type"] = "udp";
            o["server"] = value;
        }

        var type = o["type"]?.GetValue<string>();
        if (!string.IsNullOrEmpty(detour) && type != "local")
            o["detour"] = detour;

        return o;
    }

    private static JsonArray BuildRuleSets()
    {
        static JsonObject Remote(string tag, string url) => new()
        {
            ["type"] = "remote",
            ["tag"] = tag,
            ["format"] = "binary",
            ["url"] = url,
            ["download_detour"] = "proxy",
            ["update_interval"] = "7d"
        };

        return new JsonArray
        {
            Remote("geosite-ir", "https://raw.githubusercontent.com/Chocolate4U/Iran-sing-box-rules/rule-set/geosite-ir.srs"),
            Remote("geoip-ir", "https://raw.githubusercontent.com/Chocolate4U/Iran-sing-box-rules/rule-set/geoip-ir.srs")
        };
    }

    // ------------------------------------------------------------- outbound

    private static JsonObject BuildWireGuardEndpoint(ServerProfile p)
    {
        var e = new JsonObject
        {
            ["type"] = "wireguard",
            ["tag"] = "proxy",
            ["mtu"] = 1408,
            ["address"] = new JsonArray
            {
                string.IsNullOrEmpty(p.LocalAddress) ? "172.16.0.2/32" : p.LocalAddress
            },
            ["private_key"] = p.PrivateKey
        };

        var peer = new JsonObject
        {
            ["address"] = p.Address,
            ["port"] = p.Port,
            ["public_key"] = p.PeerPublicKey,
            ["allowed_ips"] = new JsonArray { "0.0.0.0/0", "::/0" },
            ["persistent_keepalive_interval"] = 25
        };
        if (!string.IsNullOrEmpty(p.PresharedKey))
            peer["pre_shared_key"] = p.PresharedKey;

        e["peers"] = new JsonArray { peer };
        return e;
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
                // XTLS Vision requires raw TCP with TLS/REALITY.
                if (!string.IsNullOrEmpty(p.Flow) && IsRawTcp(p) && IsTlsLike(p))
                    o["flow"] = p.Flow;
                o["packet_encoding"] = "xudp";
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
                o["packet_encoding"] = "xudp";
                AddTls(o, p);
                AddTransport(o, p);
                break;

            case ProxyProtocol.Trojan:
                o["type"] = "trojan";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["password"] = p.Credential;
                // Honour explicit no-TLS Trojan nodes.
                AddTls(o, p, forceTls: !string.Equals(p.Security, "none", StringComparison.OrdinalIgnoreCase));
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
                AddTls(o, p, forceTls: true, defaultAlpn: "h3");
                break;

            case ProxyProtocol.Tuic:
                o["type"] = "tuic";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["uuid"] = p.PublicKey;
                o["password"] = p.Credential;
                o["congestion_control"] = "bbr";
                o["udp_relay_mode"] = "native";
                AddTls(o, p, forceTls: true, defaultAlpn: "h3");
                break;

            case ProxyProtocol.Socks:
                o["type"] = "socks";
                o["server"] = p.Address;
                o["server_port"] = p.Port;
                o["version"] = "5";
                if (!string.IsNullOrEmpty(p.Host)) o["username"] = p.Host;
                if (!string.IsNullOrEmpty(p.Credential)) o["password"] = p.Credential;
                break;

            default:
                o["type"] = "direct";
                break;
        }

        return o;
    }

    private static bool IsRawTcp(ServerProfile p)
    {
        var net = (p.Network ?? string.Empty).ToLowerInvariant();
        return net.Length == 0 || net == "tcp" || net == "raw";
    }

    private static bool IsTlsLike(ServerProfile p)
    {
        var sec = (p.Security ?? string.Empty).ToLowerInvariant();
        return sec == "tls" || sec == "reality" || sec == "xtls";
    }

    private static void AddTls(JsonObject o, ServerProfile p, bool forceTls = false, string defaultAlpn = "")
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
        else if (!string.IsNullOrEmpty(defaultAlpn))
        {
            tls["alpn"] = new JsonArray { defaultAlpn };
        }

        if (!string.IsNullOrEmpty(p.Fingerprint) &&
            !string.Equals(p.Fingerprint, "none", StringComparison.OrdinalIgnoreCase))
        {
            tls["utls"] = new JsonObject { ["enabled"] = true, ["fingerprint"] = p.Fingerprint };
        }

        if (isReality)
        {
            var reality = new JsonObject
            {
                ["enabled"] = true,
                ["public_key"] = p.PublicKey
            };
            if (!string.IsNullOrEmpty(p.ShortId)) reality["short_id"] = p.ShortId;
            tls["reality"] = reality;

            // REALITY requires a uTLS fingerprint.
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
            case "websocket":
                var rawPath = string.IsNullOrEmpty(p.Path) ? "/" : p.Path;
                var ws = new JsonObject
                {
                    ["type"] = "ws",
                    ["path"] = rawPath.Split('?')[0] is { Length: > 0 } cleaned ? cleaned : "/"
                };
                if (!string.IsNullOrEmpty(p.Host))
                    ws["headers"] = new JsonObject { ["Host"] = p.Host };
                // Panels encode early data as `/path?ed=2048`.
                var edIndex = rawPath.IndexOf("ed=", StringComparison.OrdinalIgnoreCase);
                if (edIndex >= 0 &&
                    int.TryParse(new string(rawPath[(edIndex + 3)..].TakeWhile(char.IsDigit).ToArray()), out var ed) &&
                    ed > 0)
                {
                    ws["max_early_data"] = ed;
                    ws["early_data_header_name"] = "Sec-WebSocket-Protocol";
                }
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
                    ["path"] = string.IsNullOrEmpty(p.Path) ? "/" : p.Path.Split('?')[0]
                };
                if (!string.IsNullOrEmpty(p.Host)) hu["host"] = p.Host;
                o["transport"] = hu;
                break;
            case "http":
            case "h2":
            case "h2c":
                var h2 = new JsonObject
                {
                    ["type"] = "http",
                    ["path"] = string.IsNullOrEmpty(p.Path) ? "/" : p.Path
                };
                if (!string.IsNullOrEmpty(p.Host))
                    h2["host"] = new JsonArray { p.Host };
                o["transport"] = h2;
                break;
            case "quic":
                o["transport"] = new JsonObject { ["type"] = "quic" };
                break;
        }
    }
}
