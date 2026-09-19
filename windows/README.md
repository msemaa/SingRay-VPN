# SingRay for Windows

Desktop build of the SingRay client for Windows 10 (1809+) and Windows 11,
for x64, x86 and ARM64. Same engine design as the Android app: dual cores
(sing-box + Xray) with automatic switching, and an honest "Connected" state.

See **WINDOWS.md** for architecture, tunnel modes, native Windows behaviour
and source-protection details.

## Quick start (CI build)
1. The workflow **Build SingRay for Windows** runs automatically and produces,
   per architecture:
   - `SingRay-1.0.0-<arch>-setup.exe` (installer)
   - `SingRay-1.0.0-<arch>-portable.zip` (portable)
2. Tag `v1.0.0` to publish a GitHub Release with those files attached, then
   share the installer/zip with others.
