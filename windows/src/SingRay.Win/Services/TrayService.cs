using System.Drawing;
using System.IO;
using System.Windows;
using Forms = System.Windows.Forms;

namespace SingRay.Services;

/// Standard Windows notification-area (system tray) integration.
public sealed class TrayService : IDisposable
{
    private readonly Forms.NotifyIcon _icon;
    private readonly Forms.ToolStripMenuItem _connectItem;
    private readonly Forms.ToolStripMenuItem _statusItem;

    public event Action? ShowRequested;
    public event Action? ConnectToggleRequested;
    public event Action? ExitRequested;

    public TrayService()
    {
        _statusItem = new Forms.ToolStripMenuItem("Disconnected") { Enabled = false };
        _connectItem = new Forms.ToolStripMenuItem("Connect");
        _connectItem.Click += (_, _) => ConnectToggleRequested?.Invoke();

        var showItem = new Forms.ToolStripMenuItem("Open SingRay");
        showItem.Click += (_, _) => ShowRequested?.Invoke();

        var exitItem = new Forms.ToolStripMenuItem("Exit");
        exitItem.Click += (_, _) => ExitRequested?.Invoke();

        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add(_statusItem);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(showItem);
        menu.Items.Add(_connectItem);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(exitItem);

        _icon = new Forms.NotifyIcon
        {
            Text = "SingRay",
            Visible = true,
            Icon = LoadIcon(),
            ContextMenuStrip = menu
        };
        _icon.DoubleClick += (_, _) => ShowRequested?.Invoke();
    }

    private static Icon LoadIcon()
    {
        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
            if (File.Exists(path)) return new Icon(path);
            var exe = System.Reflection.Assembly.GetEntryAssembly()?.Location;
            if (!string.IsNullOrEmpty(exe))
            {
                var extracted = Icon.ExtractAssociatedIcon(Environment.ProcessPath ?? exe);
                if (extracted != null) return extracted;
            }
        }
        catch
        {
            // fall through
        }
        return SystemIcons.Application;
    }

    public void UpdateState(string status, bool connected)
    {
        _statusItem.Text = status;
        _connectItem.Text = connected ? "Disconnect" : "Connect";
        _icon.Text = ("SingRay - " + status).Length > 63
            ? ("SingRay - " + status).Substring(0, 63)
            : "SingRay - " + status;
    }

    public void Notify(string title, string message)
    {
        try
        {
            _icon.BalloonTipTitle = title;
            _icon.BalloonTipText = message;
            _icon.ShowBalloonTip(3000);
        }
        catch
        {
            // ignored
        }
    }

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }
}
