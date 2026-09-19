using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using SingRay.Core;
using SingRay.Models;
using SingRay.Net;
using SingRay.Services;

namespace SingRay.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged, IDisposable
{
    private readonly ProfileStore _store = new();
    private readonly SubscriptionService _subs = new();
    private readonly CoreManager _core;
    private CancellationTokenSource? _cts;

    public MainViewModel()
    {
        _core = new CoreManager(AppendLog);
        Settings = _store.LoadSettings();
        foreach (var p in _store.LoadProfiles()) Profiles.Add(p);
        foreach (var s in _store.LoadSubscriptions()) Subscriptions.Add(s);
        SelectedProfile = Profiles.FirstOrDefault(p => p.Id == Settings.SelectedProfileId) ?? Profiles.FirstOrDefault();

        ConnectCommand = new RelayCommand(async () => await ToggleConnectionAsync());
        ImportClipboardCommand = new RelayCommand(ImportFromClipboard);
        DeleteProfileCommand = new RelayCommand(DeleteSelected, () => SelectedProfile != null);
        TestSelectedCommand = new RelayCommand(async () => await TestSelectedAsync());
        TestAllCommand = new RelayCommand(async () => await TestAllAsync());
        UpdateSubscriptionsCommand = new RelayCommand(async () => await UpdateSubscriptionsAsync());
        ClearLogCommand = new RelayCommand(() => Logs.Clear());
        SaveSettingsCommand = new RelayCommand(SaveSettings);

        AppendLog(new LogLine { Level = "INFO", Tag = "APP", Message = "SingRay ready" });
        AppendLog(new LogLine
        {
            Level = CoreManager.SingBoxAvailable || CoreManager.XrayAvailable ? "INFO" : "WARN",
            Tag = "CORE",
            Message = "sing-box: " + CoreManager.CoreVersion(CoreType.SingBox) + "  |  xray: " + CoreManager.CoreVersion(CoreType.Xray)
        });
    }

    // ------------------------------------------------------------ state

    public AppSettings Settings { get; }

    public ObservableCollection<ServerProfile> Profiles { get; } = new();
    public ObservableCollection<Subscription> Subscriptions { get; } = new();
    public ObservableCollection<LogLine> Logs { get; } = new();

    private ServerProfile? _selectedProfile;
    public ServerProfile? SelectedProfile
    {
        get => _selectedProfile;
        set
        {
            if (!SetField(ref _selectedProfile, value)) return;
            Settings.SelectedProfileId = value?.Id ?? string.Empty;
            _store.SaveSettings(Settings);
            OnPropertyChanged(nameof(SelectedSummary));
            OnPropertyChanged(nameof(PlannedCore));
        }
    }

    private ConnectionState _state = ConnectionState.Disconnected;
    public ConnectionState State
    {
        get => _state;
        private set
        {
            if (!SetField(ref _state, value)) return;
            OnPropertyChanged(nameof(StateText));
            OnPropertyChanged(nameof(IsConnected));
            OnPropertyChanged(nameof(ConnectButtonText));
            StateChanged?.Invoke(StateText, IsConnected);
        }
    }

    private string _statusDetail = "Not connected";
    public string StatusDetail
    {
        get => _statusDetail;
        private set => SetField(ref _statusDetail, value);
    }

    private string _activeCoreText = "-";
    public string ActiveCoreText
    {
        get => _activeCoreText;
        private set => SetField(ref _activeCoreText, value);
    }

    private int _delayMs = -1;
    public int DelayMs
    {
        get => _delayMs;
        private set
        {
            if (!SetField(ref _delayMs, value)) return;
            OnPropertyChanged(nameof(DelayText));
        }
    }

    public string DelayText => DelayMs > 0 ? DelayMs + " ms" : "--";
    public bool IsConnected => State == ConnectionState.Connected;
    public string StateText => State switch
    {
        ConnectionState.Connected => "Connected",
        ConnectionState.Connecting => "Connecting...",
        ConnectionState.Disconnecting => "Disconnecting...",
        ConnectionState.Failed => "Failed",
        _ => "Disconnected"
    };
    public string ConnectButtonText => State switch
    {
        ConnectionState.Connected => "Disconnect",
        ConnectionState.Connecting => "Cancel",
        _ => "Connect"
    };

    public string SelectedSummary => SelectedProfile == null
        ? "No profile selected"
        : SelectedProfile.Display;

    public string PlannedCore => SelectedProfile == null
        ? "-"
        : CoreManager.Pick(SelectedProfile, Settings.PreferredCore) switch
        {
            CoreType.SingBox => "sing-box",
            CoreType.Xray => "Xray",
            _ => "no core installed"
        };

    public string ProxyEndpoints => "SOCKS5 127.0.0.1:" + Settings.SocksPort + "   |   HTTP 127.0.0.1:" + Settings.HttpPort;

    public event Action<string, bool>? StateChanged;

    // ------------------------------------------------------------ commands

    public RelayCommand ConnectCommand { get; }
    public RelayCommand ImportClipboardCommand { get; }
    public RelayCommand DeleteProfileCommand { get; }
    public RelayCommand TestSelectedCommand { get; }
    public RelayCommand TestAllCommand { get; }
    public RelayCommand UpdateSubscriptionsCommand { get; }
    public RelayCommand ClearLogCommand { get; }
    public RelayCommand SaveSettingsCommand { get; }

    // ------------------------------------------------------------ actions

    public async Task ToggleConnectionAsync()
    {
        if (State is ConnectionState.Connected or ConnectionState.Connecting)
        {
            Disconnect();
            return;
        }
        await ConnectAsync();
    }

    public async Task ConnectAsync()
    {
        var profile = SelectedProfile;
        if (profile == null)
        {
            StatusDetail = "Select a profile first.";
            return;
        }

        State = ConnectionState.Connecting;
        StatusDetail = "Starting core...";
        DelayMs = -1;

        var result = await Task.Run(() => _core.Start(profile, Settings));
        if (!result.Success)
        {
            State = ConnectionState.Failed;
            StatusDetail = result.Message;
            AppendLog(new LogLine { Level = "ERROR", Tag = "CORE", Message = result.Message });
            _core.Stop();
            return;
        }

        ActiveCoreText = result.Core == CoreType.SingBox ? "sing-box" : "Xray";
        StatusDetail = "Verifying real connectivity...";

        _cts = new CancellationTokenSource();
        var delay = -1;
        for (var attempt = 0; attempt < 3 && delay < 0; attempt++)
        {
            await Task.Delay(500, _cts.Token);
            delay = await ConnectivityTester.SocksProbeAsync(Settings.SocksPort, 8000, _cts.Token);
        }

        if (delay < 0)
        {
            // Honest failure: never report "connected" without a real 204 response.
            _core.Stop();
            State = ConnectionState.Failed;
            StatusDetail = "Core started but no traffic passed (handshake/TLS failed). Check the Logs tab.";
            AppendLog(new LogLine { Level = "ERROR", Tag = "PROBE", Message = StatusDetail });
            return;
        }

        profile.LastDelayMs = delay;
        profile.LastDelayIsReal = true;
        profile.LastTested = DateTime.Now;
        _store.SaveProfiles(Profiles);

        if (Settings.Tunnel == TunnelMode.SystemProxy)
        {
            try
            {
                SystemProxy.Enable(Settings.HttpPort, Settings.BypassLan);
                AppendLog(new LogLine { Level = "INFO", Tag = "PROXY", Message = "Windows system proxy enabled" });
            }
            catch (Exception ex)
            {
                AppendLog(new LogLine { Level = "WARN", Tag = "PROXY", Message = "System proxy failed: " + ex.Message });
            }
        }

        DelayMs = delay;
        State = ConnectionState.Connected;
        StatusDetail = "Verified via HTTP 204 in " + delay + " ms through " + ActiveCoreText;
        AppendLog(new LogLine { Level = "INFO", Tag = "PROBE", Message = StatusDetail });
    }

    public void Disconnect()
    {
        State = ConnectionState.Disconnecting;
        try { _cts?.Cancel(); } catch { }
        try { SystemProxy.Disable(); } catch { }
        _core.Stop();
        ActiveCoreText = "-";
        DelayMs = -1;
        State = ConnectionState.Disconnected;
        StatusDetail = "Not connected";
        AppendLog(new LogLine { Level = "INFO", Tag = "APP", Message = "Disconnected" });
    }

    /// Reads whatever is on the clipboard and imports it immediately - no extra
    /// paste dialog, matching the Android behaviour.
    public void ImportFromClipboard()
    {
        string text;
        try
        {
            text = Clipboard.ContainsText() ? Clipboard.GetText() : string.Empty;
        }
        catch
        {
            text = string.Empty;
        }

        if (string.IsNullOrWhiteSpace(text))
        {
            StatusDetail = "Clipboard is empty.";
            return;
        }

        var parsed = ConfigParser.ParseContent(text);
        if (parsed.Count == 0)
        {
            StatusDetail = "No valid config found on the clipboard.";
            AppendLog(new LogLine { Level = "WARN", Tag = "IMPORT", Message = StatusDetail });
            return;
        }

        var added = 0;
        foreach (var p in parsed)
        {
            if (Profiles.Any(existing => existing.RawUri == p.RawUri && !string.IsNullOrEmpty(p.RawUri))) continue;
            Profiles.Add(p);
            added++;
        }

        _store.SaveProfiles(Profiles);
        SelectedProfile ??= Profiles.FirstOrDefault();
        StatusDetail = added + " config(s) imported from clipboard.";
        AppendLog(new LogLine { Level = "INFO", Tag = "IMPORT", Message = StatusDetail });
    }

    public void ImportText(string text)
    {
        var parsed = ConfigParser.ParseContent(text);
        foreach (var p in parsed) Profiles.Add(p);
        _store.SaveProfiles(Profiles);
        StatusDetail = parsed.Count + " config(s) imported.";
    }

    private void DeleteSelected()
    {
        if (SelectedProfile == null) return;
        Profiles.Remove(SelectedProfile);
        SelectedProfile = Profiles.FirstOrDefault();
        _store.SaveProfiles(Profiles);
    }

    public async Task TestSelectedAsync()
    {
        if (SelectedProfile == null) return;
        await TestProfileAsync(SelectedProfile);
        _store.SaveProfiles(Profiles);
    }

    public async Task TestAllAsync()
    {
        var snapshot = Profiles.ToList();
        foreach (var p in snapshot) await TestProfileAsync(p);
        _store.SaveProfiles(Profiles);
    }

    private async Task TestProfileAsync(ServerProfile p)
    {
        // When the tunnel is up, measure the real proxied delay; otherwise fall
        // back to a TCP handshake and label it as such so the number never lies.
        if (State == ConnectionState.Connected && p.Id == SelectedProfile?.Id)
        {
            var real = await ConnectivityTester.SocksProbeAsync(Settings.SocksPort);
            p.LastDelayMs = real;
            p.LastDelayIsReal = true;
        }
        else
        {
            var tcp = await ConnectivityTester.TcpPingAsync(p.Address, p.Port);
            p.LastDelayMs = tcp;
            p.LastDelayIsReal = false;
        }
        p.LastTested = DateTime.Now;
        p.Refresh();
    }

    public async Task UpdateSubscriptionsAsync()
    {
        foreach (var sub in Subscriptions.ToList())
        {
            try
            {
                var fetched = await _subs.FetchAsync(sub);
                var stale = Profiles.Where(p => p.SubscriptionId == sub.Id).ToList();
                foreach (var s in stale) Profiles.Remove(s);
                foreach (var p in fetched) Profiles.Add(p);
                AppendLog(new LogLine { Level = "INFO", Tag = "SUB", Message = sub.Name + ": " + fetched.Count + " nodes" });
            }
            catch (Exception ex)
            {
                AppendLog(new LogLine { Level = "ERROR", Tag = "SUB", Message = sub.Name + ": " + ex.Message });
            }
        }
        _store.SaveProfiles(Profiles);
        _store.SaveSubscriptions(Subscriptions);
    }

    public void AddSubscription(string name, string url)
    {
        var sub = new Subscription { Name = string.IsNullOrWhiteSpace(name) ? url : name, Url = url };
        Subscriptions.Add(sub);
        _store.SaveSubscriptions(Subscriptions);
    }

    public void SaveSettings()
    {
        _store.SaveSettings(Settings);
        StartupRegistration.Apply(Settings.LaunchOnStartup);
        OnPropertyChanged(nameof(ProxyEndpoints));
        OnPropertyChanged(nameof(PlannedCore));
        StatusDetail = "Settings saved.";
    }

    public void AppendLog(LogLine line)
    {
        void Add()
        {
            Logs.Add(line);
            while (Logs.Count > 800) Logs.RemoveAt(0);
        }

        var app = Application.Current;
        if (app?.Dispatcher != null && !app.Dispatcher.CheckAccess()) app.Dispatcher.Invoke(Add);
        else Add();
    }

    public void Dispose()
    {
        try { SystemProxy.Disable(); } catch { }
        _core.Dispose();
    }

    // ------------------------------------------------------------ INPC

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    private bool SetField<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnPropertyChanged(name);
        return true;
    }
}
