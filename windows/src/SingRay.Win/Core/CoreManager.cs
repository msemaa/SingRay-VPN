using System.Diagnostics;
using System.IO;
using SingRay.Models;

namespace SingRay.Core;

public sealed record CoreStartResult(bool Success, CoreType Core, string Message);

/// Runs sing-box.exe / xray.exe as a child process and picks the right core
/// for the selected config automatically.
public sealed class CoreManager : IDisposable
{
    private readonly Action<LogLine> _log;
    private Process? _process;
    private CoreType _running = CoreType.Auto;
    private string? _configPath;

    public CoreManager(Action<LogLine> log) => _log = log;

    public CoreType RunningCore => _running;
    public bool IsRunning => _process is { HasExited: false };

    public static string CoreDirectory =>
        Path.Combine(AppContext.BaseDirectory, "cores");

    public static string WorkDirectory
    {
        get
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "SingRay");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    public static string SingBoxPath => Path.Combine(CoreDirectory, "sing-box.exe");
    public static string XrayPath => Path.Combine(CoreDirectory, "xray.exe");

    public static bool SingBoxAvailable => File.Exists(SingBoxPath);
    public static bool XrayAvailable => File.Exists(XrayPath);

    public static string CoreVersion(CoreType core)
    {
        var exe = core == CoreType.SingBox ? SingBoxPath : XrayPath;
        if (!File.Exists(exe)) return "not installed";
        try
        {
            var psi = new ProcessStartInfo(exe, "version")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var proc = Process.Start(psi);
            if (proc == null) return "unknown";
            var output = proc.StandardOutput.ReadToEnd();
            proc.WaitForExit(4000);
            var first = output.Split('\u000A').FirstOrDefault()?.Trim();
            return string.IsNullOrWhiteSpace(first) ? "unknown" : first;
        }
        catch
        {
            return "unknown";
        }
    }

    /// Auto-selection rules, mirroring the Android client.
    public static CoreType Pick(ServerProfile p, CoreType preference)
    {
        if (preference == CoreType.SingBox && SingBoxAvailable) return CoreType.SingBox;
        if (preference == CoreType.Xray && XrayAvailable) return CoreType.Xray;

        var net = (p.Network ?? "tcp").ToLowerInvariant();
        var isReality = string.Equals(p.Security, "reality", StringComparison.OrdinalIgnoreCase)
                        || !string.IsNullOrEmpty(p.PublicKey);

        // Only sing-box implements these.
        var singBoxOnly = p.Protocol is ProxyProtocol.Hysteria2 or ProxyProtocol.Tuic
            or ProxyProtocol.WireGuard or ProxyProtocol.Ssh;
        if (singBoxOnly)
            return SingBoxAvailable ? CoreType.SingBox : CoreType.Auto;

        // Xray is the reference implementation for REALITY / XTLS-Vision and xhttp.
        if (isReality || net is "xhttp" or "splithttp" || !string.IsNullOrEmpty(p.Flow))
        {
            if (XrayAvailable) return CoreType.Xray;
            if (SingBoxAvailable) return CoreType.SingBox;
            return CoreType.Auto;
        }

        if (SingBoxAvailable) return CoreType.SingBox;
        if (XrayAvailable) return CoreType.Xray;
        return CoreType.Auto;
    }

    public CoreStartResult Start(ServerProfile profile, AppSettings settings)
    {
        Stop();

        var core = Pick(profile, settings.PreferredCore);
        if (core == CoreType.Auto)
        {
            return new CoreStartResult(false, CoreType.Auto,
                "No core executable found. Place sing-box.exe and xray.exe in the 'cores' folder next to SingRay.exe.");
        }

        _log(new LogLine
        {
            Level = "INFO",
            Tag = "CORE",
            Message = $"Auto-selected {core} for {profile.Protocol}/{profile.Security}/{profile.Network}"
        });

        var json = core == CoreType.SingBox
            ? SingBoxConfigBuilder.Build(profile, settings)
            : XrayConfigBuilder.Build(profile, settings);

        _configPath = Path.Combine(WorkDirectory, core == CoreType.SingBox ? "singbox.json" : "xray.json");
        File.WriteAllText(_configPath, json);

        var exe = core == CoreType.SingBox ? SingBoxPath : XrayPath;
        var args = core == CoreType.SingBox
            ? $"run -c \"{_configPath}\" -D \"{WorkDirectory}\""
            : $"run -c \"{_configPath}\"";

        var psi = new ProcessStartInfo(exe, args)
        {
            WorkingDirectory = CoreDirectory,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        psi.EnvironmentVariables["XRAY_LOCATION_ASSET"] = CoreDirectory;

        try
        {
            _process = new Process { StartInfo = psi, EnableRaisingEvents = true };
            _process.OutputDataReceived += (_, e) => Relay(core, e.Data, "INFO");
            _process.ErrorDataReceived += (_, e) => Relay(core, e.Data, "WARN");
            if (!_process.Start())
                return new CoreStartResult(false, core, $"Could not start {core}.");

            _process.BeginOutputReadLine();
            _process.BeginErrorReadLine();
            _running = core;

            // Give the core a moment to bind its inbounds or die noisily.
            if (_process.WaitForExit(700))
            {
                var code = _process.ExitCode;
                return new CoreStartResult(false, core,
                    $"{core} exited immediately (code {code}). See the log tab for details.");
            }

            return new CoreStartResult(true, core, $"{core} started");
        }
        catch (Exception ex)
        {
            return new CoreStartResult(false, core, $"{core} failed to start: {ex.Message}");
        }
    }

    private void Relay(CoreType core, string? line, string level)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        _log(new LogLine { Level = level, Tag = core.ToString().ToUpperInvariant(), Message = line.Trim() });
    }

    public void Stop()
    {
        try
        {
            if (_process is { HasExited: false })
            {
                _process.Kill(entireProcessTree: true);
                _process.WaitForExit(3000);
            }
        }
        catch
        {
            // ignored
        }
        finally
        {
            _process?.Dispose();
            _process = null;
            _running = CoreType.Auto;
        }
    }

    public void Dispose() => Stop();
}
