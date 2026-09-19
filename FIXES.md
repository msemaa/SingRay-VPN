# SingRay - Core rebuild notes

## 1. The real bug: the app never used the proxy
The old `LocalProxyServer` accepted SOCKS5 from local apps and then opened a
**direct** socket to the destination. Nothing was ever sent to the configured
server. On top of that:

- `SingRayVpnService` established a TUN interface that routed `0.0.0.0/0` into
  the app but never read/wrote packets, so all real traffic was black-holed.
- The status was set to `CONNECTED` unconditionally after `delay(300)`.
- Traffic stats came from device-wide `android.net.TrafficStats`, so numbers
  moved even though nothing went through the proxy.
- Ping was a plain TCP handshake to host:port, which stays green even when the
  UUID/password is wrong or the node is dead.

That is exactly the symptom you described: "connected, ping fine, but nothing
works".

## 2. What was added (real protocol core, pure Kotlin)
`app/src/main/java/com/example/core/proxy/`

| File | Purpose |
| --- | --- |
| `ProxyCore.kt` | Outbound interface, socket protection (`VpnService.protect`), address encoding, UUID parsing, factory that picks the protocol |
| `StreamTransport.kt` | TCP + TLS (SNI, ALPN, allow-insecure) + WebSocket (RFC 6455) + HTTP Upgrade transports |
| `VlessOutbound.kt` | Full VLESS request/response header |
| `VmessOutbound.kt` | VMess **AEAD** header, AES-128-GCM chunk stream, FNV1a checksum, response verification |
| `TrojanOutbound.kt` | SHA-224 password hash + Trojan request over TLS |
| `ShadowsocksOutbound.kt` | AEAD ciphers: aes-128/192/256-gcm and chacha20-ietf-poly1305 (HKDF-SHA1 `ss-subkey`) |
| `ConnectivityTester.kt` | Real delay: opens the tunnel and performs an actual `GET http://cp.cloudflare.com/generate_204` through it |

Unsupported-by-design configs (REALITY, Hysteria2, TUIC, WireGuard, gRPC, SSH,
XTLS flow) now fail with a clear message instead of pretending to connect.

## 3. Rewritten pieces
- **`core/LocalProxyServer.kt`** - SOCKS5 **and** HTTP/HTTPS-CONNECT inbound that
  forwards every stream through the selected outbound, returns proper SOCKS5 /
  502 error codes on failure, and counts only bytes that really crossed the
  tunnel.
- **`service/SingRayVpnService.kt`** - no more fake TUN. It starts SOCKS5 on
  `127.0.0.1:10808` and HTTP on `127.0.0.1:10809`, **verifies the tunnel with a
  real request before reporting CONNECTED**, exposes `lastError`, and reports
  throughput from proxy counters only.
- **`MainViewModel`** - passes the selected server id to the service, exposes
  `lastError`, plus `realDelayTest()` / `realDelayTestAll()` (genuine end-to-end
  tests, not TCP ping).
- **`ConfigParser.parseQueryParams`** - `split("=", limit = 2)` so values that
  contain `=` (base64 padding, paths, hosts) are no longer dropped.

## 4. Your other two requests
- **Branding**: every occurrence of the third-party name was removed. The
  Telegram card now reads "اتصال فوری تلگرام (پروکسی داخلی برنامه)".
- **Import from Clipboard**: tapping it now reads the clipboard instantly and
  imports everything it finds, with a toast showing the count. The manual dialog
  only opens if the clipboard is empty or contains no valid link.

## 5. Honest limitation
This core tunnels apps that speak SOCKS5/HTTP proxy (Telegram, browsers with a
proxy set, etc.). Transparent system-wide VPN capture requires a native
tun2socks / sing-box binary; that is intentionally not faked here. The fake TUN
was the reason the old build "looked connected" while doing nothing.
