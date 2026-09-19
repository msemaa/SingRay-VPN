using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text.Json.Serialization;

namespace SingRay.Models;

public enum ProxyProtocol { Vless, Vmess, Trojan, Shadowsocks, Hysteria2, Tuic, WireGuard, Socks, Http, Ssh, Unknown }

public enum CoreType { Auto, SingBox, Xray }

public enum ConnectionState { Disconnected, Connecting, Connected, Disconnecting, Failed }

public enum RoutingMode { Rule, Global, Direct }

public enum TunnelMode { ProxyOnly, SystemProxy, Tun }

public sealed class ServerProfile : INotifyPropertyChanged
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = string.Empty;
    public ProxyProtocol Protocol { get; set; } = ProxyProtocol.Unknown;
    public string Address { get; set; } = string.Empty;
    public int Port { get; set; }

    public string Credential { get; set; } = string.Empty;
    public int AlterId { get; set; }
    public string Security { get; set; } = string.Empty;
    public string Network { get; set; } = "tcp";
    public string Sni { get; set; } = string.Empty;
    public string Host { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
    public string Alpn { get; set; } = string.Empty;
    public string Flow { get; set; } = string.Empty;
    public string Fingerprint { get; set; } = string.Empty;
    public string PublicKey { get; set; } = string.Empty;
    public string ShortId { get; set; } = string.Empty;
    public string Method { get; set; } = string.Empty;

    public string PrivateKey { get; set; } = string.Empty;
    public string PeerPublicKey { get; set; } = string.Empty;
    public string LocalAddress { get; set; } = string.Empty;
    public string PresharedKey { get; set; } = string.Empty;

    public bool AllowInsecure { get; set; }
    public string SubscriptionId { get; set; } = string.Empty;
    public string RawUri { get; set; } = string.Empty;

    public int LastDelayMs { get; set; } = -1;
    public bool LastDelayIsReal { get; set; }
    public DateTime? LastTested { get; set; }
    public bool IsFavorite { get; set; }

    [JsonIgnore]
    public string Endpoint => Address + ":" + Port;

    [JsonIgnore]
    public string Display => (string.IsNullOrWhiteSpace(Name) ? Endpoint : Name)
                             + "  -  " + Protocol.ToString().ToUpperInvariant()
                             + " / " + (string.IsNullOrEmpty(Network) ? "tcp" : Network)
                             + (string.IsNullOrEmpty(Security) || Security == "none" ? string.Empty : " / " + Security);

    [JsonIgnore]
    public string DelayDisplay => LastDelayMs switch
    {
        > 0 when LastDelayIsReal => LastDelayMs + " ms (real)",
        > 0 => LastDelayMs + " ms (tcp)",
        -1 => "untested",
        _ => "timeout"
    };

    /// Refresh computed columns in the UI after a test.
    public void Refresh()
    {
        OnPropertyChanged(nameof(LastDelayMs));
        OnPropertyChanged(nameof(DelayDisplay));
        OnPropertyChanged(nameof(Display));
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed class Subscription
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = string.Empty;
    public string Url { get; set; } = string.Empty;
    public DateTime? LastUpdated { get; set; }
    public int NodeCount { get; set; }

    [JsonIgnore]
    public string Display => Name + "  -  " + NodeCount + " nodes"
                             + (LastUpdated.HasValue ? "  -  " + LastUpdated.Value.ToString("yyyy-MM-dd HH:mm") : string.Empty);
}

public sealed class AppSettings
{
    public CoreType PreferredCore { get; set; } = CoreType.Auto;
    public RoutingMode Routing { get; set; } = RoutingMode.Rule;
    public TunnelMode Tunnel { get; set; } = TunnelMode.SystemProxy;
    public int SocksPort { get; set; } = 10808;
    public int HttpPort { get; set; } = 10809;
    public string Dns { get; set; } = "1.1.1.1";
    public bool BypassLan { get; set; } = true;
    public bool BypassDomestic { get; set; } = true;
    public bool StartMinimized { get; set; }
    public bool CloseToTray { get; set; } = true;
    public bool LaunchOnStartup { get; set; }
    public bool AutoImportClipboard { get; set; } = true;
    public string SelectedProfileId { get; set; } = string.Empty;
}

public sealed class LogLine
{
    public DateTime Time { get; set; } = DateTime.Now;
    public string Level { get; set; } = "INFO";
    public string Tag { get; set; } = "APP";
    public string Message { get; set; } = string.Empty;

    public string Display => Time.ToString("HH:mm:ss") + "  [" + Level + "]  " + Tag + ": " + Message;
}
