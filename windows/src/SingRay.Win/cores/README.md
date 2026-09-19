# cores

The native engines live here, next to SingRay.exe after publish:

- sing-box.exe - https://github.com/SagerNet/sing-box/releases (windows-amd64 / windows-386 / windows-arm64)
- xray.exe - https://github.com/XTLS/Xray-core/releases (Xray-windows-64 / -32 / -arm64-v8a)
- geoip.dat, geosite.dat - shipped inside the Xray zip, used for rule based routing
- wintun.dll - https://www.wintun.net, required only for TUN mode

The GitHub Actions workflow downloads the latest versions automatically,
so this folder can stay empty in the repository.
