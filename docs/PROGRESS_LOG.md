# SingRay - Progress Log (append-only)

> Read `docs/AGENT_HANDOFF.md` first for the architecture, field-packing
> conventions and the per-protocol default table. This file is the
> chronological log: every round appends one section so the next AI can see
> exactly where the previous one stopped.

---

## Round A - runtime truth (branch `main`)

**Problem reported by the user:** the app said *Connected*, ping worked, but no
traffic actually flowed. Every protocol failed.

**Root causes found in the in-app CORE LOGS:**

1. `sing-box failed: decode config: dns.servers[0]: legacy DNS server formats are
   deprecated in sing-box 1.12.0 and removed in 1.14.0`
   -> our generator still emitted the pre-1.12 DNS shape, so sing-box never started.
2. `No implementation found for void libXray.LibXray._init()`
   -> the libXray Java classes were on the classpath but the gomobile `.so` was
   missing/mismatched, and `XrayEngine.isAvailable()` returned a hardcoded `true`.
3. With both native cores down, everything silently fell back to the limited
   Kotlin core -> `Tunnel handshake failed: stream closed after 0/1 bytes`.

**Commits**

| SHA | What |
| --- | --- |
| `3cc652f` | CI: fixed the indentation crash in the AAR-dedup step, removed the dead hardcoded libXray tag |
| `87bc15c` | `SingBoxConfigGenerator` rewritten for the sing-box 1.14 schema |
| `1276893` | `ConfigParser` rewritten: every mainstream link scheme |
| `3494172` | `ProxyProtocol` enum: real protocols instead of a silent VLESS fallback |
| `ac1d82e` | `XrayConfigGenerator`: full protocol coverage + `SUPPORTED` set |
| `e78b595` | `XrayEngine`: probe the JNI bridge before claiming availability |

---

## Round B - UI correctness (this round)

**Problem:** on the dashboard the Telegram button, the theme toggle and the
routing pill overlapped / slid under each other (see the user's screenshot).

**Cause:** the top bar was a `Row(SpaceBetween)` whose left child was an
unconstrained `Column`. With a long routing label, a long core name, a narrow
screen or a large system font, the left column claimed all the width and pushed
the three action items into each other.

**Fix (`ui/screens/DashboardScreen.kt`):**

* left `Column` -> `Modifier.weight(1f, fill = false)` with
  `maxLines = 1` + `TextOverflow.Ellipsis` on the title and the subtitle;
* right action group -> `Arrangement.spacedBy(8.dp)` + `wrapContentWidth()`
  instead of hand-placed `Spacer`s;
* routing pill -> `widthIn(max = 110.dp)`, single line, ellipsised.

**Rule for future screens:** in any `Row(horizontalArrangement = SpaceBetween)`
the text side must carry `weight(1f)` and the action side must carry
`wrapContentWidth()`. Never rely on intrinsic width for a header.

---

## Still open (ordered by value)

1. **Bundle a matching libXray build.** `XrayEngine` now degrades gracefully, but
   until the AAR ships a `libgojni.so` for the built ABIs, Xray is skipped and
   sing-box carries everything. Check the `Prepare native cores` step of
   `.github/workflows/build_apk.yml`.
2. **Compile guard-rails.** `SingBoxConfigGenerator` throws and
   `XrayConfigGenerator.generate` has a `require(...)` for unsupported
   protocols. Every caller (`CoreManager`, `MainViewModel.generateCurrentSingBoxJson`)
   must catch `IllegalArgumentException` and surface it as a user-facing error.
   `ProxyProtocol.UNKNOWN` was added - any exhaustive `when` over the enum must
   handle it.
3. **Audit the other screens** (`Servers`, `Routing`, `Subscriptions`,
   `Diagnostics`) for the header pattern described in Round B.
4. **Windows parity.** Mirror the protocol expansion into
   `windows/src/SingRay.Win/Core/{ConfigParser,SingBoxConfigBuilder,XrayConfigBuilder}.cs`
   (same scheme list, same 1.14 schema, same tuned defaults).
5. **Windows CI** lives on branch `windows-client`; last two failures were
   `NETSDK1135` (fixed in `fb7dd46`) and `CS0104` (fixed in `05a4dae`).
   Merge `windows-client` into `main` once a run is green.
