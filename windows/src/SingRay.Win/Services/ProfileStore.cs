using System.IO;
using System.Text.Json;
using SingRay.Models;

namespace SingRay.Services;

/// Persists profiles, subscriptions and settings as JSON under %APPDATA%\SingRay.
public sealed class ProfileStore
{
    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    public static string Directory
    {
        get
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "SingRay");
            System.IO.Directory.CreateDirectory(dir);
            return dir;
        }
    }

    private static string ProfilesPath => Path.Combine(Directory, "profiles.json");
    private static string SubscriptionsPath => Path.Combine(Directory, "subscriptions.json");
    private static string SettingsPath => Path.Combine(Directory, "settings.json");

    public List<ServerProfile> LoadProfiles() => Read<List<ServerProfile>>(ProfilesPath) ?? new List<ServerProfile>();

    public void SaveProfiles(IEnumerable<ServerProfile> profiles) => Write(ProfilesPath, profiles.ToList());

    public List<Subscription> LoadSubscriptions() => Read<List<Subscription>>(SubscriptionsPath) ?? new List<Subscription>();

    public void SaveSubscriptions(IEnumerable<Subscription> subs) => Write(SubscriptionsPath, subs.ToList());

    public AppSettings LoadSettings() => Read<AppSettings>(SettingsPath) ?? new AppSettings();

    public void SaveSettings(AppSettings settings) => Write(SettingsPath, settings);

    private static T? Read<T>(string path)
    {
        try
        {
            if (!File.Exists(path)) return default;
            return JsonSerializer.Deserialize<T>(File.ReadAllText(path), Options);
        }
        catch
        {
            return default;
        }
    }

    private static void Write<T>(string path, T value)
    {
        try
        {
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(value, Options));
            File.Move(tmp, path, true);
        }
        catch
        {
            // storage failures must never crash the UI
        }
    }
}
