using System.Text.Json;
using System.Text.Json.Nodes;
using SingRay.Models;

namespace SingRay.Core;

/// Builds an Xray-core configuration (Xray 1.8+ / 25.x schema).
public static class XrayConfigBuilder
{
    public static string Build(ServerProfile p, AppSettings s)
    {
        var root = new JsonObject
        {
            ["log"] = new JsonObject { ["loglevel"] = "warning" },
            ["dns"] = new JsonObject
            {
                ["servers"] = new JsonArray { s.Dns, "8.8.8.8", "localhost" },
                ["queryStrategy"] = "UseIP"
            },
            ["inbounds"] = new JsonArray
            {
                new JsonObject
                {
                    ["tag"] = "socks-in",
                    ["listen"] = "127.0.0.1",
                    ["port"] = s.SocksPort,
                    ["protocol"] = "socks",
                    ["settings"] = new JsonObject { ["auth"] = "noauth", ["udp"] = true },
                    ["sniffing"] = new JsonObject
                    {
                        ["enabled"] = true,
                        ["destOverride"] = new JsonArray { "http", "tls", "quic" }
                    }
                },
                new JsonObject
                {
                    ["tag"] = "http-in",
                    ["listen"] = "127.0.0.1",
                    ["port"] = s.HttpPort,
                    ["protocol"] = "http",
                    ["sniffing"] = new JsonObject
                    {
                        ["enabled"] = true,
                        ["destOverride"] = new JsonArray { "http", "tls" }
                    }
                }
            },
            ["outbounds"] = new JsonArray
            {
                BuildOutbound(p),
                new JsonObject
                {
                    ["tag"] = "direct",
                    ["protocol"] = "freedom",
                    ["settings"] = new JsonObject { ["domainStrategy"] = "UseIP" }
                },
                new JsonObject { ["tag"] = "block", ["protocol"] = "blackhole" }
            }
        };

        var rules = new JsonArray();
        switch (s.Routing)
        {
            case RoutingMode.Global:
                rules.Add(new JsonObject { ["type"] = "field", ["network"] = "tcp,udp", ["outboundTag"] = "proxy" });
                break;
            case RoutingMode.Direct:
                rules.Add(new JsonObject { ["type"] = "field", ["network"] = "tcp,udp", ["outboundTag"] = "direct" });
                break;
            default:
                if (s.BypassLan)
                    rules.Add(new JsonObject { ["type"] = "field", ["ip"] = new JsonArray { "geoip:private" }, ["outboundTag"] = "direct" });
                if (s.BypassDomestic)
                {
                    rules.Add(new JsonObject { ["type"] = "field", ["ip"] = new JsonArray { "geoip:ir" }, ["outboundTag"] = "direct" });
                    rules.Add(new JsonObject { ["type"] = "field", ["domain"] = new JsonArray { "geosite:category-ir" }, ["outboundTag"] = "direct" });
                }
                rules.Add(new JsonObject { ["type"] = "field", ["network"] = "tcp,udp", ["outboundTag"] = "proxy" });
                break;
        }

        root["routing"] = new JsonObject
        {
            ["domainStrategy"] = "IPIfNonMatch",
            ["rules"] = rules
        };

        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    private static JsonObject BuildOutbound(ServerProfile p)
    {
        var o = new JsonObject { ["tag"] = "proxy" };

        switch (p.Protocol)
        {
            case ProxyProtocol.Vless:
                var vlessUser = new JsonObject
                {
                    ["id"] = p.Credential,
                    ["encryption"] = "none",
                    ["level"] = 8
                };
                var flow = p.Flow;
                if (string.IsNullOrEmpty(flow) && string.Equals(p.Security, "reality", StringComparison.OrdinalIgnoreCase))
                    flow = "xtls-rprx-vision";
                if (!string.IsNullOrEmpty(flow)) vlessUser["flow"] = flow;

                o["protocol"] = "vless";
                o["settings"] = new JsonObject
                {
                    ["vnext"] = new JsonArray
                    {
                        new JsonObject
                        {
                            ["address"] = p.Address,
                            ["port"] = p.Port,
                            ["users"] = new JsonArray { vlessUser }
                        }
                    }
                };
                break;

            case ProxyProtocol.Vmess:
                o["protocol"] = "vmess";
                o["settings"] = new JsonObject
                {
                    ["vnext"] = new JsonArray
                    {
                        new JsonObject
                        {
                            ["address"] = p.Address,
                            ["port"] = p.Port,
                            ["users"] = new JsonArray
                            {
                                new JsonObject
                                {
                                    ["id"] = p.Credential,
                                    ["alterId"] = p.AlterId,
                                    ["security"] = "auto",
                                    ["level"] = 8
                                }
                            }
                        }
                    }
                };
                break;

            case ProxyProtocol.Trojan:
                o["protocol"] = "trojan";
                o["settings"] = new JsonObject
                {
                    ["servers"] = new JsonArray
                    {
                        new JsonObject
                        {
                            ["address"] = p.Address,
                            ["port"] = p.Port,
                            ["password"] = p.Credential,
                            ["level"] = 8
                        }
                    }
                };
                break;

            case ProxyProtocol.Shadowsocks:
                o["protocol"] = "shadowsocks";
                o["settings"] = new JsonObject
                {
                    ["servers"] = new JsonArray
                    {
                        new JsonObject
                        {
                            ["address"] = p.Address,
                            ["port"] = p.Port,
                            ["method"] = string.IsNullOrEmpty(p.Method) ? "chacha20-ietf-poly1305" : p.Method,
                            ["password"] = p.Credential,
                            ["level"] = 8
                        }
                    }
                };
                break;

            case ProxyProtocol.Socks:
                o["protocol"] = "socks";
                o["settings"] = new JsonObject
                {
                    ["servers"] = new JsonArray
                    {
                        new JsonObject { ["address"] = p.Address, ["port"] = p.Port }
                    }
                };
                break;

            default:
                o["protocol"] = "freedom";
                o["settings"] = new JsonObject();
                return o;
        }

        o["streamSettings"] = BuildStream(p);
        return o;
    }

    private static JsonObject BuildStream(ServerProfile p)
    {
        var net = (p.Network ?? "tcp").ToLowerInvariant();
        var stream = new JsonObject
        {
            ["network"] = net == "h2" ? "http" : net
        };

        if (string.Equals(p.Security, "reality", StringComparison.OrdinalIgnoreCase))
        {
            stream["security"] = "reality";
            stream["realitySettings"] = new JsonObject
            {
                ["serverName"] = string.IsNullOrEmpty(p.Sni) ? p.Address : p.Sni,
                ["publicKey"] = p.PublicKey,
                ["shortId"] = p.ShortId,
                ["fingerprint"] = string.IsNullOrEmpty(p.Fingerprint) ? "chrome" : p.Fingerprint,
                ["spiderX"] = "/"
            };
        }
        else if (string.Equals(p.Security, "tls", StringComparison.OrdinalIgnoreCase))
        {
            var tls = new JsonObject
            {
                ["serverName"] = !string.IsNullOrEmpty(p.Sni) ? p.Sni : (!string.IsNullOrEmpty(p.Host) ? p.Host : p.Address),
                ["allowInsecure"] = p.AllowInsecure,
                ["fingerprint"] = string.IsNullOrEmpty(p.Fingerprint) ? "chrome" : p.Fingerprint
            };
            if (!string.IsNullOrEmpty(p.Alpn))
            {
                var alpn = new JsonArray();
                foreach (var a in p.Alpn.Split(',', StringSplitOptions.RemoveEmptyEntries))
                    alpn.Add(a.Trim());
                tls["alpn"] = alpn;
            }
            stream["security"] = "tls";
            stream["tlsSettings"] = tls;
        }
        else
        {
            stream["security"] = "none";
        }

        switch (net)
        {
            case "ws":
                var ws = new JsonObject { ["path"] = string.IsNullOrEmpty(p.Path) ? "/" : p.Path };
                if (!string.IsNullOrEmpty(p.Host))
                    ws["headers"] = new JsonObject { ["Host"] = p.Host };
                stream["wsSettings"] = ws;
                break;
            case "httpupgrade":
                var hu = new JsonObject { ["path"] = string.IsNullOrEmpty(p.Path) ? "/" : p.Path };
                if (!string.IsNullOrEmpty(p.Host)) hu["host"] = p.Host;
                stream["httpupgradeSettings"] = hu;
                break;
            case "xhttp":
            case "splithttp":
                var xh = new JsonObject { ["path"] = string.IsNullOrEmpty(p.Path) ? "/" : p.Path, ["mode"] = "auto" };
                if (!string.IsNullOrEmpty(p.Host)) xh["host"] = p.Host;
                stream["xhttpSettings"] = xh;
                break;
            case "grpc":
                stream["grpcSettings"] = new JsonObject
                {
                    ["serviceName"] = (p.Path ?? string.Empty).Trim('/'),
                    ["multiMode"] = false
                };
                break;
            case "http":
            case "h2":
                var h2 = new JsonObject { ["path"] = string.IsNullOrEmpty(p.Path) ? "/" : p.Path };
                if (!string.IsNullOrEmpty(p.Host)) h2["host"] = new JsonArray { p.Host };
                stream["httpSettings"] = h2;
                break;
        }

        return stream;
    }
}
