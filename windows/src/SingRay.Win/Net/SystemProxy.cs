using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace SingRay.Net;

/// Sets/restores the Windows (WinINET) system proxy, the same mechanism used by
/// Settings > Network > Proxy. Works per-user, requires no elevation.
public static class SystemProxy
{
    private const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    private const int INTERNET_OPTION_SETTINGS_CHANGED = 39;
    private const int INTERNET_OPTION_REFRESH = 37;

    [DllImport("wininet.dll", SetLastError = true, CharSet = CharSet.Auto)]
    private static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);

    private static bool _previousEnabled;
    private static string _previousServer = string.Empty;
    private static string _previousOverride = string.Empty;
    private static bool _applied;

    public static void Enable(int httpPort, bool bypassLan)
    {
        using var key = Registry.CurrentUser.OpenSubKey(KeyPath, writable: true);
        if (key == null) return;

        if (!_applied)
        {
            _previousEnabled = (key.GetValue("ProxyEnable") as int? ?? 0) == 1;
            _previousServer = key.GetValue("ProxyServer") as string ?? string.Empty;
            _previousOverride = key.GetValue("ProxyOverride") as string ?? string.Empty;
        }

        key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
        key.SetValue("ProxyServer", "127.0.0.1:" + httpPort, RegistryValueKind.String);
        key.SetValue("ProxyOverride",
            bypassLan
                ? "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"
                : "localhost;127.*",
            RegistryValueKind.String);

        _applied = true;
        Refresh();
    }

    public static void Disable()
    {
        if (!_applied) return;
        using var key = Registry.CurrentUser.OpenSubKey(KeyPath, writable: true);
        if (key == null) return;

        key.SetValue("ProxyEnable", _previousEnabled ? 1 : 0, RegistryValueKind.DWord);
        if (string.IsNullOrEmpty(_previousServer)) key.DeleteValue("ProxyServer", false);
        else key.SetValue("ProxyServer", _previousServer, RegistryValueKind.String);
        if (string.IsNullOrEmpty(_previousOverride)) key.DeleteValue("ProxyOverride", false);
        else key.SetValue("ProxyOverride", _previousOverride, RegistryValueKind.String);

        _applied = false;
        Refresh();
    }

    private static void Refresh()
    {
        InternetSetOption(IntPtr.Zero, INTERNET_OPTION_SETTINGS_CHANGED, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, INTERNET_OPTION_REFRESH, IntPtr.Zero, 0);
    }
}
