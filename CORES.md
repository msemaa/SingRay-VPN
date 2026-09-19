# Dual-core architecture (sing-box + Xray) with auto-switching

## Before
The app had **no** sing-box or Xray core. `SingBoxConfigGenerator` only produced
JSON for display, and all traffic was handled by the pure-Kotlin core
(`core/proxy/*`), which speaks VLESS / VMess / Trojan / Shadowsocks over
tcp | ws | tls only.

## Now
A core abstraction layer was added: `app/src/main/java/com/example/core/engine/`

| File | Purpose |
|---|---|
| `CoreEngine.kt` | `CoreType { AUTO, SING_BOX, XRAY, BUILT_IN }`, engine interface |
| `SingBoxEngine.kt` | Drives native sing-box (`io.nekohasekai.libbox`) |
| `SingBoxPlatform.kt` | `PlatformInterface` proxy: TUN fd, `protect()`, logs |
| `XrayEngine.kt` | Drives `libXray` / `libv2ray` (Xray-core) |
| `CoreManager.kt` | Availability detection + **automatic core selection** |
| `core/config/XrayConfigGenerator.kt` | Full Xray JSON (REALITY, xhttp, gRPC, ws, tcp-http, routing) |
| `SingBoxConfigGenerator.generateRuntimeJson()` | Runtime sing-box JSON (mixed inbound + optional tun) |

## Auto-switch rules (`CoreManager.pickCore`)

1. `hysteria2`, `tuic`, `wireguard`, `ssh`, `shadowtls` -> **sing-box** (only core that supports them)
2. `reality` / XTLS-Vision / present `publicKey` -> **Xray** (reference implementation), sing-box as fallback
3. `grpc`, `xhttp`, `httpupgrade`, `h2` transports -> **Xray**
4. everything else (vless/vmess/trojan/ss over tcp|ws|tls) -> **sing-box**, then Xray
5. chosen core fails to start -> next available native core
6. no native core bundled -> **Built-in** Kotlin core (honest log message)

The user can also force a core in *Routing / Core* (`Auto | Sing-box | Xray | Built-in`).
The screen shows the active core and the installed native versions.

## Connection flow (`SingRayVpnService`)

1. Pick core for the selected config.
2. sing-box: `VpnService.Builder` creates the TUN, the fd is handed to libbox
   (`auto_route`, stack `mixed`), so the tunnel is system-wide.
3. Xray: local `mixed`/SOCKS 10808 + HTTP 10809 inbounds.
4. `ConnectivityTester.socksProbe()` performs a real
   `GET http://cp.cloudflare.com/generate_204` **through the core's own SOCKS
   inbound**; only on success does the state become `CONNECTED`. A core that
   starts but does not pass data is reported as an error, never as "connected".
5. On failure, the next core is tried, then the built-in core.

## Required binaries

The Go cores are distributed as `.aar` files and are **not** in this repo
(this build environment has no internet access). Drop them into `app/libs/`:

- `libbox.aar` — sing-box (`make lib_android`, latest release)
- `libXray.aar` or `libv2ray.aar` — Xray-core

`app/build.gradle.kts` already contains
`implementation(fileTree("libs") { include("*.aar", "*.jar") })`, and all native
calls go through reflection, so the project **compiles and runs with or without**
the AARs. As soon as an AAR is present, that core is detected and used
automatically. Full instructions and links: `app/libs/README.md`.
