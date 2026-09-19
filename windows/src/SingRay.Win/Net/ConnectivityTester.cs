using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace SingRay.Net;

/// Honest connectivity checks: every "Connected" state in SingRay is backed by a
/// real HTTP 204 round trip through the local SOCKS5 inbound. TCP reachability
/// alone is never reported as success.
public static class ConnectivityTester
{
    private const string TestHost = "cp.cloudflare.com";
    private const string TestPath = "/generate_204";

    /// Real end-to-end delay in ms through the local SOCKS5 proxy, or -1 on failure.
    public static async Task<int> SocksProbeAsync(int socksPort, int timeoutMs = 8000, CancellationToken ct = default)
    {
        var sw = Stopwatch.StartNew();
        try
        {
            using var client = new TcpClient();
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(timeoutMs);

            await client.ConnectAsync(IPAddress.Loopback, socksPort, cts.Token);
            client.NoDelay = true;
            await using var stream = client.GetStream();

            // SOCKS5 greeting, no auth.
            await stream.WriteAsync(new byte[] { 0x05, 0x01, 0x00 }, cts.Token);
            var greeting = new byte[2];
            if (!await ReadExactAsync(stream, greeting, cts.Token)) return -1;
            if (greeting[0] != 0x05 || greeting[1] != 0x00) return -1;

            // CONNECT to the test host by domain name, port 80.
            var host = Encoding.ASCII.GetBytes(TestHost);
            var req = new byte[7 + host.Length];
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03;
            req[4] = (byte)host.Length;
            Buffer.BlockCopy(host, 0, req, 5, host.Length);
            req[5 + host.Length] = 0x00;
            req[6 + host.Length] = 0x50; // port 80
            await stream.WriteAsync(req, cts.Token);

            var head = new byte[4];
            if (!await ReadExactAsync(stream, head, cts.Token)) return -1;
            if (head[1] != 0x00) return -1; // non-zero reply = proxy refused

            var skip = head[3] switch
            {
                0x01 => 4,
                0x04 => 16,
                0x03 => await ReadLengthPrefixAsync(stream, cts.Token),
                _ => -1
            };
            if (skip < 0) return -1;
            var rest = new byte[skip + 2];
            if (!await ReadExactAsync(stream, rest, cts.Token)) return -1;

            // Minimal HTTP request; anything other than a 2xx/204 counts as failure.
            var httpRequest = Encoding.ASCII.GetBytes(
                "GET " + TestPath + " HTTP/1.1\u000D\u000AHost: " + TestHost +
                "\u000D\u000AUser-Agent: SingRay\u000D\u000AConnection: close\u000D\u000A\u000D\u000A");
            await stream.WriteAsync(httpRequest, cts.Token);

            var buffer = new byte[128];
            var read = await stream.ReadAsync(buffer, cts.Token);
            if (read <= 12) return -1;
            var statusLine = Encoding.ASCII.GetString(buffer, 0, read);
            if (!statusLine.StartsWith("HTTP/1.", StringComparison.Ordinal)) return -1;
            var ok = statusLine.Contains(" 204") || statusLine.Contains(" 200");
            if (!ok) return -1;

            sw.Stop();
            return (int)Math.Max(1, sw.ElapsedMilliseconds);
        }
        catch
        {
            return -1;
        }
    }

    /// TCP handshake time to the remote endpoint. Used only as a hint, never as
    /// proof of a working tunnel.
    public static async Task<int> TcpPingAsync(string host, int port, int timeoutMs = 5000)
    {
        var sw = Stopwatch.StartNew();
        try
        {
            using var client = new TcpClient();
            using var cts = new CancellationTokenSource(timeoutMs);
            await client.ConnectAsync(host, port, cts.Token);
            sw.Stop();
            return (int)Math.Max(1, sw.ElapsedMilliseconds);
        }
        catch
        {
            return -1;
        }
    }

    private static async Task<int> ReadLengthPrefixAsync(NetworkStream stream, CancellationToken ct)
    {
        var len = new byte[1];
        return await ReadExactAsync(stream, len, ct) ? len[0] : -1;
    }

    private static async Task<bool> ReadExactAsync(NetworkStream stream, byte[] buffer, CancellationToken ct)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(offset, buffer.Length - offset), ct);
            if (read <= 0) return false;
            offset += read;
        }
        return true;
    }
}
