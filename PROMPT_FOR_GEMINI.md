# Prompt to paste into Google AI Studio (Gemini) with GitHub connected

Copy everything below the line into the AI Studio chat.

---

You are working on the Android VPN client **SingRay** in this repository.
The full application source is already complete and must NOT be rewritten.
There is a dual-core architecture in `app/src/main/java/com/example/core/engine/`
(`CoreEngine.kt`, `CoreManager.kt`, `SingBoxEngine.kt`, `SingBoxPlatform.kt`,
`XrayEngine.kt`) that talks to the native sing-box and Xray cores **through Java
reflection**, so the project compiles with or without the native binaries.
Read `CORES.md` and `app/libs/README.md` first.

Your job is ONLY the things that require network access, real Gradle builds, and
a device. Do these tasks in order, committing to the repo and running GitHub
Actions:

## Task 1 — Bundle the native cores
Fetch/build the latest binaries and commit them (or keep them CI-generated):
- `libbox.aar` from the latest release of https://github.com/SagerNet/sing-box
  (`make lib_android`, gomobile, `-javapkg=io.nekohasekai`, android api 24,
  tags: with_gvisor, with_quic, with_wireguard, with_utls, with_clash_api)
- `libXray.aar` from the latest release of https://github.com/XTLS/libXray
  (or `libv2ray.aar` from 2dust/AndroidLibXrayLite)
Put them in `app/libs/`. `app/build.gradle.kts` already has
`implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))`.
The workflow `.github/workflows/build_apk.yml` already contains steps for this —
fix it if the upstream build scripts changed.

## Task 2 — Make the reflection bindings match the real APIs
After the AARs are present, check the actual signatures with `javap` and adjust
`SingBoxEngine.kt`, `SingBoxPlatform.kt`, `XrayEngine.kt` if a method name or
parameter list differs from what the reflection code tries:
- sing-box: `io.nekohasekai.libbox.Libbox.setup(...)`, `newService(configJson, platformInterface)`,
  `BoxService.start()/close()`, `Libbox.version()`, and the full
  `PlatformInterface` method set (`openTun`, `writeLog`,
  `usePlatformAutoDetectInterfaceControl`, `autoDetectInterfaceControl`,
  `findConnectionOwner`, `packageNameByUid`, `uidByPackageName`, ...).
  Prefer implementing `PlatformInterface` as a **real Kotlin class** instead of a
  `java.lang.reflect.Proxy` once the AAR is on the classpath.
- Xray: `libXray.LibXray.initXray/runXray/stopXray` (or
  `libv2ray.Libv2ray.startV2Ray*`). Note newer libXray takes base64-encoded
  JSON args and returns a JSON result string — adapt if needed.

## Task 3 — tun2socks for the Xray path
Xray has no tun inbound. Add a tun2socks bridge (hev-socks5-tunnel or
badvpn-tun2socks .so/.aar) so that when `CoreManager` selects Xray, the TUN fd
created in `SingRayVpnService.establishTun()` is piped into the local SOCKS
inbound on 127.0.0.1:10808. Today the Xray path only works as a local proxy
(apps must use the SOCKS/HTTP port), and sing-box owns the TUN directly.

## Task 4 — Geo assets
Download `geoip.dat`/`geosite.dat` (Xray) and `geoip.db`/`geosite.db` (sing-box)
into `app/src/main/assets/`, copy them to `filesDir` at first launch, and point
the generated configs at them so the `geoip:ir` / `geosite:category-ir` bypass
rules actually work.

## Task 5 — Build, fix compile errors, ship
Run `./gradlew :app:assembleDebug` (and `assembleRelease` if signing secrets
exist). Fix every compile error without changing the architecture or the UI.
Make sure the workflow uploads the APK artifact. Report the artifact link.

## Task 6 — On-device verification checklist
For each config type confirm the log line shows the expected core and that
the state becomes CONNECTED only after the real `generate_204` probe passes:
- VLESS + REALITY + tcp/xhttp  -> Xray
- VLESS/VMess/Trojan/SS + ws/tls -> sing-box
- Hysteria2, TUIC, WireGuard, SSH -> sing-box
- ShadowSocks 2022 ciphers -> sing-box
Also verify: "Import from Clipboard" auto-pastes, subscription update, ping
(real delay through the tunnel), routing modes Rule/Global/Direct, the Telegram
quick-connect card, and that no "Hiddify" string exists anywhere.

## Rules
- Do not redesign the UI or rename packages (`com.example`, applicationId
  `com.aistudio.singray.core`).
- Do not replace the built-in Kotlin core in `core/proxy/*` — it is the fallback.
- Never report a connection as successful unless the core's own SOCKS probe
  succeeded (this is the bug that was fixed; keep it).
- Keep all changes in small commits with clear messages.
