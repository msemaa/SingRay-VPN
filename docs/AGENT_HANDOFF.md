# SingRay — Agent Handoff / Work Log

> Living document. **Any AI or developer picking this repo up must read this file first**,
> then update it before finishing their session.
> Last updated: 2026-09-19

---

## 1. What this project is

SingRay is a multi-protocol proxy client.

| Target | Location | CI workflow | Branch |
|---|---|---|---|
| Android (Kotlin / Compose) | repo root (`app/`) | `.github/workflows/build_apk.yml` | `main` |
| Windows 10/11 (C# / WPF) | `windows/` | `.github/workflows/build_windows.yml` | `windows-client` |

Both clients run **two native cores** and switch between them automatically:

* **sing-box** — hysteria, hysteria2, tuic, anytls, shadowtls, wireguard, ssh, and everything else
* **Xray-core** — REALITY, XTLS Vision, xhttp/splithttp, classic vless/vmess/trojan/ss

Core selection rules live in `CoreManager` (Android: `core/engine/CoreManager.kt`,
Windows: `windows/src/SingRay.Win/Core/CoreManager.cs`).

**Branding rule:** no third-party brand names anywhere in the UI or source
(the old "Hiddify" strings were removed and must not come back).

---

## 2. Completed work

### 2.1 Android — fake connection bug (DONE)
The original build reported `CONNECTED` after a fixed `delay(300)` while the TUN
interface was black-holed and the local proxy dialed targets directly.

Fixed by:
* real pure-Kotlin fallback core in `core/proxy/` (`ProxyCore`, `StreamTransport`,
  `VlessOutbound`, `TrojanOutbound`, `ShadowsocksOutbound`, `VmessOutbound`)
* `ConnectivityTester` performs a real SOCKS5 CONNECT + `http://cp.cloudflare.com/generate_204`
  probe; the UI only shows *Connected* after that probe succeeds
* per-tunnel traffic accounting instead of device-wide stats
* clipboard import now pastes automatically (no intermediate dialog)

### 2.2 Android — dual native cores (DONE)
`core/engine/`: `CoreEngine`, `SingBoxEngine`, `SingBoxPlatform`, `XrayEngine`, `CoreManager`.
AAR dependencies are pulled by CI into `app/libs/` (`fileTree` include `*.aar`, `*.jar`).

### 2.3 Android — CI green (DONE, commit `3cc652f`)
Root cause of the long-running build failure: the inline `python3 -c "..."` AAR-dedup
script inside `build_apk.yml` was indented *inside* the YAML literal block, so after
YAML dedent the Python body still had leading spaces → `IndentationError`.

Fixed by:
* moving the script to `.ci/strip_go_classes.py` (removes duplicate gomobile `go/`
  classes that otherwise cause `D8: Type go.Seq is defined multiple times`)
* resolving the libXray release asset from
  `api.github.com/repos/XTLS/libXray/releases/latest` instead of the dead hardcoded `v26.9.9`
* core download steps are `continue-on-error: true` and emit `::warning::`
* `keytool -genkeypair` fallback when `debug.keystore` is absent (it is gitignored)

### 2.4 Android — protocol coverage & correct configs (DONE, this session)

**Root cause of "every protocol errors out"** (from the in-app core log):

```
Sing-box (1.14.1-lx.8) failed: decode config: dns.servers[0]:
  legacy DNS server formats are deprecated in sing-box 1.12.0 and removed in 1.14.0
Xray (native) failed: No implementation found for void libXray.LibXray._init()
→ No native core available, falling back to the built-in Kotlin core
```

Both cores refused to start, so *every* node fell through to the limited Kotlin
fallback and failed the handshake.

Fixes pushed:

* **`core/config/SingBoxConfigGenerator.kt`** — rewritten for the **sing-box 1.12+/1.14 schema**:
  * typed DNS servers (`{"type":"https","server":"1.1.1.1"}`), never address strings
  * `block` outbound removed; rejection is a route action
  * sniffing and DNS hijack are route **actions** (`{"action":"sniff"}`, `{"action":"hijack-dns"}`)
  * tun uses `address: []` instead of `inet4_address`
  * `default_domain_resolver`, `experimental.cache_file`
  * protocols: vless, vmess, trojan, shadowsocks, hysteria, hysteria2, tuic,
    anytls, shadowtls, socks, http, ssh, wireguard — unknown protocols now throw
    instead of silently emitting a broken vless outbound
* **`core/config/XrayConfigGenerator.kt`** — added socks/http/wireguard, a `SUPPORTED`
  set + `supports()` so `CoreManager` can route unsupported protocols to sing-box,
  Vision flow only on raw TCP, policy/buffer tuning
* **`core/parser/ConfigParser.kt`** — rewritten. Now parses: `vless`, `vmess`
  (base64-JSON **and** plain-URI forms), `trojan`, `ss` (incl. plugin param),
  `ssr`, `hysteria`, `hysteria2`/`hy2`, `tuic`, `anytls`, `shadowtls`,
  `socks`/`socks5`, `http`/`https`, `wireguard`/`wg`, `ssh`; whole-payload base64
  subscriptions; IPv6 hosts; case-insensitive query keys
* **`model/Models.kt`** — `ProxyProtocol` now has HYSTERIA, TUIC, ANYTLS, SHADOWTLS,
  SOCKS, HTTP and `UNKNOWN` instead of silently mapping everything to VLESS

**Field packing convention** (ServerEntity has no dedicated columns for these):

| Field | Reused for |
|---|---|
| `fingerprint` | Shadowsocks cipher method |
| `path` | TUIC password / Hysteria2 obfs password / WireGuard local address / SS plugin |
| `host` | SSH + SOCKS/HTTP username |
| `uuid` | password / private key / auth string, depending on protocol |
| `publicKey` | REALITY public key **or** WireGuard peer public key |
| `shortId` | REALITY short id **or** WireGuard pre-shared key |

### 2.5 Windows client (DONE, authored + CI wired)
WPF app under `windows/`, native Windows chrome (no custom title bar, so minimise /
maximise / close / snap behave exactly like any other Windows app), tray icon,
system-proxy and TUN modes, single instance mutex, startup registration.

CI builds x64 / x86 / ARM64 → Inno Setup `setup.exe` + portable zip.
Source protection: single-file self-contained publish, `DebugType=none`, Obfuscar config.

Build failures fixed this session:
1. `NETSDK1135: SupportedOSPlatformVersion 10.0.17763.0 cannot be higher than TargetPlatformVersion 7.0`
   → TFM changed to `net8.0-windows10.0.17763.0`; added `windows/global.json` pinning SDK 8
2. `CS0104: 'Application' is an ambiguous reference between System.Windows.Forms.Application and System.Windows.Application`
   → global aliases in the csproj pin `Application`, `MessageBox`, `Clipboard` to their WPF versions

---

## 3. Known open items (next steps)

### 3.1 Dashboard header overlap (Android) — **TODO, highest priority UI bug**
In `app/src/main/java/com/example/ui/screens/DashboardScreen.kt`, the top bar
`Row` contains a title `Column` and an actions `Row` (Telegram button, theme
toggle, "Rule Based" pill). On narrow screens the actions overflow and visually
overlap because the title `Column` has no width constraint.

Fix:
* give the title column `Modifier.weight(1f, fill = false)`
* add `maxLines = 1` + `overflow = TextOverflow.Ellipsis` to
  `"Xray / Sing-box • Multi-Protocol Engine"` and to the routing-mode pill text
* replace the manual `Spacer`s in the actions row with
  `horizontalArrangement = Arrangement.spacedBy(8.dp)`
* audit the same pattern everywhere else: any `Row` with
  `Arrangement.SpaceBetween` where **neither** child has a weight. Known
  candidates: `ServersScreen` node rows, `SubscriptionsScreen` cards,
  `DiagnosticsScreen` header.

### 3.2 Xray native binding (Android) — TODO
`No implementation found for void libXray.LibXray._init()` means the bundled
libXray AAR does not match the JNI signature `XrayEngine` calls. Either:
* pin a known-good libXray release in `build_apk.yml` and match the Kotlin binding, or
* switch to `2dust/AndroidLibXrayLite` (`libv2ray`) which has a stable `RunLoop`/`StopLoop` API.

Until this is fixed, REALITY / XTLS Vision nodes fall back to sing-box (which does
support REALITY), so this is a quality issue, not a blocker.

### 3.3 Windows parity — TODO
Port the widened protocol/parser coverage from §2.4 into:
* `windows/src/SingRay.Win/Core/ConfigParser.cs`
* `windows/src/SingRay.Win/Core/SingBoxConfigBuilder.cs` (needs the same 1.14 schema fix)
* `windows/src/SingRay.Win/Core/XrayConfigBuilder.cs`

### 3.4 Per-protocol tunable settings UI — TODO
Defaults are currently hardcoded in the generators (see §4). Expose them in the
Settings screen as per-node overrides so users can change them.

### 3.5 Housekeeping — TODO
* `windows/WINDOWS.md` and the app icon (`Assets/app.ico`) were never pushed;
  the csproj and tray service already degrade gracefully without the icon
* open a PR `windows-client` → `main` once the Windows build is green

---

## 4. Per-protocol defaults currently applied

| Protocol | Defaults |
|---|---|
| VLESS | `packet_encoding=xudp`; Vision flow only on raw TCP + REALITY; uTLS `chrome` |
| VMess | `security=auto`, `alter_id` from link, `packet_encoding=xudp` |
| Trojan | TLS always on, SNI falls back to host then server address |
| Shadowsocks | cipher from link, default `chacha20-ietf-poly1305`, multiplex off, UoT off |
| Hysteria v1 | `up_mbps=100`, `down_mbps=100`, ALPN `hysteria` (core refuses to start without bandwidth) |
| Hysteria2 | ALPN `h3`, salamander obfs when the link carries `obfs-password` |
| TUIC | `congestion_control=bbr`, `udp_relay_mode=native`, `heartbeat=10s`, zero-RTT off |
| AnyTLS | idle session check/timeout `30s` |
| ShadowTLS | version 3 |
| WireGuard | `mtu=1408`, `allowedIPs=0.0.0.0/0,::/0` |
| Transports | ws `max_early_data=2048` + `Sec-WebSocket-Protocol`; grpc idle/ping `15s` |
| Routing | LAN bypass via `ip_is_private`, domestic bypass via `.ir` suffix, DNS `1.1.1.1` (DoH, proxied) + `8.8.8.8` (direct) |
| Ports | SOCKS `10808`, HTTP `10809`; TUN `172.19.0.1/30`, MTU 9000 |

---

## 5. Commit trail (this session)

| Branch | Commit | What |
|---|---|---|
| main | `3cc652f` | CI: Android build fixed (Python indentation + libXray asset resolution) |
| main | `87bc15c` | sing-box 1.14 schema + full protocol coverage |
| main | `1276893` | parser: every mainstream link format |
| main | `3494172` | model: real protocol enum |
| main | `ac1d82e` | xray: full protocol coverage |
| windows-client | `8c26617` | Windows client source + CI (4 batches) |
| windows-client | `fb7dd46` | fix NETSDK1135 (TFM / global.json) |
| windows-client | `05a4dae` | fix CS0104 (WPF vs WinForms aliases) |

---

## 6. Session checklist for the next agent

1. Read this file end to end.
2. Check the **Actions** tab for the latest run on `main` and `windows-client`.
3. Pick up §3 in order (3.1 → 3.5).
4. Keep third-party brand names out of the UI and source.
5. Never report a tunnel as connected without a real end-to-end probe.
6. **Update this file** (sections 2, 3 and 5) before you finish.
