using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using SingRay.Core;
using SingRay.Models;
using SingRay.Services;
using SingRay.ViewModels;

namespace SingRay;

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm = new();
    private readonly TrayService _tray = new();
    private bool _reallyExit;
    private bool _loadingSettings = true;

    public MainWindow() : this(false)
    {
    }

    public MainWindow(bool startMinimized)
    {
        InitializeComponent();
        DataContext = _vm;

        _tray.ShowRequested += RestoreFromTray;
        _tray.ConnectToggleRequested += async () => await _vm.ToggleConnectionAsync();
        _tray.ExitRequested += () =>
        {
            _reallyExit = true;
            Close();
        };
        _vm.StateChanged += (text, connected) =>
            Dispatcher.Invoke(() => _tray.UpdateState(text, connected));

        LoadSettingsIntoUi();

        VersionText.Text = "SingRay 1.0.0   |   sing-box: " + CoreManager.CoreVersion(CoreType.SingBox) +
                           "   |   Xray: " + CoreManager.CoreVersion(CoreType.Xray);
        CoreInfo.Text = "Installed cores - sing-box: " + (CoreManager.SingBoxAvailable ? "yes" : "no") +
                        ", Xray: " + (CoreManager.XrayAvailable ? "yes" : "no") +
                        ". Auto picks sing-box for Hysteria2/TUIC/WireGuard and Xray for REALITY/XTLS/xhttp.";

        if (startMinimized || _vm.Settings.StartMinimized)
        {
            WindowState = WindowState.Minimized;
            ShowInTaskbar = false;
        }

        if (_vm.Settings.AutoImportClipboard)
        {
            // Silent best-effort import so a copied config is ready immediately.
            try { _vm.ImportFromClipboard(); } catch { }
        }
    }

    private void LoadSettingsIntoUi()
    {
        _loadingSettings = true;
        var s = _vm.Settings;
        CoreBox.SelectedIndex = s.PreferredCore switch
        {
            CoreType.SingBox => 1,
            CoreType.Xray => 2,
            _ => 0
        };
        TunnelBox.SelectedIndex = s.Tunnel switch
        {
            TunnelMode.ProxyOnly => 0,
            TunnelMode.Tun => 2,
            _ => 1
        };
        RoutingBox.SelectedIndex = s.Routing switch
        {
            RoutingMode.Global => 1,
            RoutingMode.Direct => 2,
            _ => 0
        };
        BypassLanBox.IsChecked = s.BypassLan;
        BypassDomesticBox.IsChecked = s.BypassDomestic;
        CloseToTrayBox.IsChecked = s.CloseToTray;
        StartMinimizedBox.IsChecked = s.StartMinimized;
        StartupBox.IsChecked = s.LaunchOnStartup;
        AutoClipboardBox.IsChecked = s.AutoImportClipboard;

        BypassLanBox.Click += (_, _) => s.BypassLan = BypassLanBox.IsChecked == true;
        BypassDomesticBox.Click += (_, _) => s.BypassDomestic = BypassDomesticBox.IsChecked == true;
        CloseToTrayBox.Click += (_, _) => s.CloseToTray = CloseToTrayBox.IsChecked == true;
        StartMinimizedBox.Click += (_, _) => s.StartMinimized = StartMinimizedBox.IsChecked == true;
        StartupBox.Click += (_, _) => s.LaunchOnStartup = StartupBox.IsChecked == true;
        AutoClipboardBox.Click += (_, _) => s.AutoImportClipboard = AutoClipboardBox.IsChecked == true;
        _loadingSettings = false;
    }

    private void OnCoreChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loadingSettings) return;
        _vm.Settings.PreferredCore = CoreBox.SelectedIndex switch
        {
            1 => CoreType.SingBox,
            2 => CoreType.Xray,
            _ => CoreType.Auto
        };
    }

    private void OnTunnelChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loadingSettings) return;
        var mode = TunnelBox.SelectedIndex switch
        {
            0 => TunnelMode.ProxyOnly,
            2 => TunnelMode.Tun,
            _ => TunnelMode.SystemProxy
        };

        if (mode == TunnelMode.Tun && !IsElevated())
        {
            MessageBox.Show(
                "TUN mode routes every app through the tunnel and needs administrator rights plus Wintun.\u000D\u000A" +
                "Restart SingRay as administrator to use it.",
                "SingRay", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        _vm.Settings.Tunnel = mode;
    }

    private void OnRoutingChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loadingSettings) return;
        _vm.Settings.Routing = RoutingBox.SelectedIndex switch
        {
            1 => RoutingMode.Global,
            2 => RoutingMode.Direct,
            _ => RoutingMode.Rule
        };
    }

    private static bool IsElevated()
    {
        try
        {
            using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
            var principal = new System.Security.Principal.WindowsPrincipal(identity);
            return principal.IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }

    private void OnAddSubscription(object sender, RoutedEventArgs e)
    {
        var url = Clipboard.ContainsText() ? Clipboard.GetText().Trim() : string.Empty;
        if (!url.StartsWith("http", StringComparison.OrdinalIgnoreCase))
        {
            MessageBox.Show("Copy a subscription link to the clipboard first, then press this button.",
                "SingRay", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        _vm.AddSubscription("Subscription", url);
        _ = _vm.UpdateSubscriptionsAsync();
    }

    private void OnCopyTelegramProxy(object sender, RoutedEventArgs e)
    {
        var link = "tg://socks?server=127.0.0.1&port=" + _vm.Settings.SocksPort;
        try
        {
            Clipboard.SetText(link);
            _tray.Notify("SingRay", "Proxy link copied. Open it to route Telegram through SingRay.");
        }
        catch
        {
            // ignored
        }
    }

    private void OnCopyLogs(object sender, RoutedEventArgs e)
    {
        var sb = new StringBuilder();
        foreach (var line in _vm.Logs) sb.AppendLine(line.Display);
        try { Clipboard.SetText(sb.ToString()); } catch { }
    }

    private void OnOpenDataFolder(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo("explorer.exe", ProfileStore.Directory) { UseShellExecute = true });
        }
        catch
        {
            // ignored
        }
    }

    protected override void OnStateChanged(EventArgs e)
    {
        base.OnStateChanged(e);
        // Standard Windows behaviour: minimize goes to the taskbar; only the
        // tray option hides it from there.
        if (WindowState == WindowState.Minimized && _vm.Settings.StartMinimized)
            ShowInTaskbar = false;
        else
            ShowInTaskbar = true;
    }

    private void RestoreFromTray()
    {
        Dispatcher.Invoke(() =>
        {
            Show();
            ShowInTaskbar = true;
            WindowState = WindowState.Normal;
            Activate();
            Topmost = true;
            Topmost = false;
        });
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_reallyExit && _vm.Settings.CloseToTray)
        {
            e.Cancel = true;
            Hide();
            ShowInTaskbar = false;
            _tray.Notify("SingRay", "Still running in the notification area.");
            return;
        }

        base.OnClosing(e);
        _vm.Dispose();
        _tray.Dispose();
        Application.Current.Shutdown();
    }
}
