# Native cores (sing-box + Xray)

The app now has a complete core abstraction (`com.example.core.engine`) that can
run **sing-box** and **Xray-core** natively and switch between them automatically
based on the config the user connects with.

The Go-based cores ship as `.aar` binaries (tens of MB, per-ABI native code).
They are **not** included in this repository. Drop them in this folder and
Gradle will pick them up automatically (`implementation(fileTree("libs"))`).

## 1. sing-box (libbox.aar)

Latest release: https://github.com/SagerNet/sing-box/releases

Build it yourself (recommended, gives you the newest version):

```bash
git clone https://github.com/SagerNet/sing-box
cd sing-box
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
make lib_android          # produces libbox.aar
cp libbox.aar <project>/app/libs/
```

Or copy `libbox.aar` from a release of a client that already bundles it
(e.g. SFA / Husi builds).

Expected Java package: `io.nekohasekai.libbox`.

## 2. Xray-core (libXray.aar or libv2ray.aar)

Latest releases:
- https://github.com/XTLS/libXray/releases  -> `libXray.aar` (package `libXray`)
- https://github.com/2dust/AndroidLibXrayLite -> `libv2ray.aar` (package `libv2ray`)

```bash
git clone https://github.com/XTLS/libXray
cd libXray
./gen_android.sh          # or: gomobile bind -target=android ./
cp libXray.aar <project>/app/libs/
```

Both bindings are supported; the engine detects whichever is present.

## 3. Geo assets (optional but recommended)

Put `geoip.dat` / `geosite.dat` (Xray) and `geoip.db` / `geosite.db` (sing-box)
in `app/src/main/assets/`. Without them, `geoip:ir` / `geosite:category-ir`
rules are ignored and everything simply goes through the proxy.

## 4. Verifying

After dropping the .aar files in, rebuild. On connect the log panel shows:

```
CORE  Auto-selected Sing-box for VLESS/reality/tcp
SING-BOX  Started (1.11.x)
TEST  Tunnel verified through Sing-box in 214 ms
```

If nothing is bundled, the app logs
`No native core available, falling back to the built-in Kotlin core`
and keeps working with the built-in core (VLESS/VMess/Trojan/SS over
tcp|ws|tls, local SOCKS 10808 / HTTP 10809).
