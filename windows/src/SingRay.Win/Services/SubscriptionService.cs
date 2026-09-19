using System.Net.Http;
using SingRay.Core;
using SingRay.Models;

namespace SingRay.Services;

public sealed class SubscriptionService
{
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var handler = new HttpClientHandler { UseProxy = false };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(25) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("SingRay/1.0");
        return client;
    }

    public async Task<List<ServerProfile>> FetchAsync(Subscription sub, CancellationToken ct = default)
    {
        var body = await Http.GetStringAsync(sub.Url, ct);
        var profiles = ConfigParser.ParseContent(body);
        foreach (var p in profiles) p.SubscriptionId = sub.Id;
        sub.LastUpdated = DateTime.Now;
        sub.NodeCount = profiles.Count;
        return profiles;
    }
}
