<p align="center">
  <strong>English</strong> &nbsp;·&nbsp; <a href="USER_GUIDE.ru.md">Русский</a> &nbsp;·&nbsp; <a href="../README.md">README</a> &nbsp;·&nbsp; <a href="DEVELOPMENT.md">Developer Guide</a>
</p>

# User Guide — FPTN Flow (Android Fork)

This guide covers installation, choosing the right APK architecture, adding FPTN access tokens, tuning DPI bypass, Smart DNS and website split-routing, optional Root Stealth mode, and resolving common connection issues.

---

## 1. Choosing the Right APK & Installing

FPTN Flow bundles native BoringSSL / `libfptn` binaries for three CPU architectures. On the [GitHub Releases](https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest) page, pick the build matching your device:

| Build Variant | Best For | Notes |
| :--- | :--- | :--- |
| **`arm64-v8a`** *(Recommended)* | Almost all Android phones & tablets made since 2016 | Smallest APK size, native 64-bit performance |
| **`armeabi-v7a`** | Legacy 32-bit ARM Android phones | Use only if `arm64-v8a` fails to install |
| **`x86_64`** | Android emulators, Intel/AMD tablets, Chromebooks | Native x86_64 execution |
| **`universal`** | Any Android 7.0+ device when unsure | Contains all three ABIs in one APK |

**Installation steps:**
1. Download the APK from GitHub Releases.
2. Tap the APK in your file manager and allow *Install unknown apps* if prompted.
3. Launch **FPTN Flow** and allow notifications (optional, used for the persistent VPN status bar) and Android VPN permission on first connection.

---

## 2. Adding Access Tokens (Multi-Token Manager)

FPTN Flow works with both official FPTN servers and self-hosted `fptn` / `fptn-admin` servers:

- **Official tokens:** Request a token from the official Telegram bot [@fptn_bot](https://t.me/fptn_bot).
- **Supported formats:** `fptnb:` / `fptnb://` (Brotli-compressed), `fptn:` / `fptn://` (Base64), raw JSON tokens, and `fptn://` deep links opened from messengers.
- **Multiple tokens:** Open **Settings → Tokens** to add, rename, enable/disable, or delete multiple tokens side by side. Servers from all enabled tokens appear together in the Server Picker and can be sorted by ping or grouped by token.

---

## 3. Censorship Bypass & Stability Tuning

Open **Settings → Bypass & Protocol** and **Settings → Stability** to adapt to your ISP or mobile carrier:

- **Bypass Method:**
  - `SNI / Reality` *(recommended)* — mimics real TLS handshakes of Chrome, Firefox, Yandex Browser, or Safari (16 selectable profiles mapped to compiled-in BoringSSL fingerprints).
  - `TLS Obfuscation` — disguises the connection as an already established TLS session.
- **SNI Auto-Scanner:** Tap **Auto-scan SNI** to test domains from bundled `russia.sni` (997 domains) or `global.sni` (282 domains) directly against your active server via native `SniChecker`. Enable **Auto-rotate SNI on failure** to recover automatically from TLS Error `600`.
- **Sliding Tunnel (`Rolling` / `Dual` / `Triple`):** Keeps standby handshakes warm in the background so long sessions survive aggressive carrier DPI resets.
- **Stall Watchdog (0 B/s Recovery):** Detects silent mobile blackholes using dual probes (UDP:53 DNS + ICMP) and reconnects cleanly. Use *Gentle* on jittery LTE/5G networks, *Balanced* for everyday use, or *Fast* when testing unstable links.
- **Network Presets & Manual Overrides:** Built-in presets (*Mobile 4G/5G*, *Wi-Fi / Home*, *DPI Maximum*, *Games & Calls*) switch automatically when moving between Wi-Fi and cellular. If you pin a specific setting (e.g., Smart DNS or custom SNI), manage it in **Settings → Presets → Manual Overrides** so auto-presets never overwrite it.

---

## 4. Smart DNS, Browser `.ru` Routing & Permanent RU Protection

### Smart DNS Modes (`Settings → DNS`)
- **Auto FPTN:** Uses the VPN server's internal DNS (`172.20.0.1` / `/api/v1/dns`).
- **Smart DNS (DoH):** Routes DNS queries inside the tunnel via an encrypted DoH proxy (`TunnelDohResolver`) with 17 built-in catalog servers plus custom user entries.
- **AdGuard DoH:** Built-in ad/tracker filtering over DoH.
- **Custom IPv4:** Direct DNS IPv4 resolver inside the tunnel.

### Why `.ru` / Russian Services Always Stay Direct (Even With Root)
To protect FPTN exit servers from abuse and blocking, **Russian protection rules are permanently active and cannot be disabled — even on rooted devices**:
- **82 Russian domestic ASN CIDRs** (`SmartRfRouter`) and **507 official protected RU app rules** (+ 4 exceptions from `always_excluded_apps.xml`) always bypass the VPN tunnel directly.
- **Telegram Data Center subnets** (`91.108.0.0/16`, `149.154.160.0/20`, etc.) are always forced through the encrypted VPN tunnel.
- Additional regional bypass profiles (*China*, *Iran*, *Vietnam*) and custom direct/VPN domain rules are available in **Settings → Routing & Apps**.

### Important Tip for Chrome / Chromium Browsers (`.ru` & Custom Direct Sites)
FPTN Flow uses **live in-tunnel Split-DNS** and `www.` pre-resolution to resolve `.ru` and custom direct sites directly over your physical ISP network while blocking IPv6 bypass leaks.
- If **Chrome** (or another Chromium browser) has a third-party **Secure DNS (DoH)** provider enabled inside browser settings (`Settings → Privacy and security → Use secure DNS`), Chrome bypasses Android's UDP:53 resolver entirely, preventing domain-based Split-DNS from seeing lookups.
- **Fix:** Set Chrome's *Use secure DNS* to **With your current service provider** (system default), or disable third-party browser DoH, then reconnect FPTN Flow.

---

## 5. Optional Root Stealth Mode (`fptn0`)

Available automatically only when Root access (Magisk, KernelSU, or APatch) is detected (**Settings → Root Stealth**):

- Opens a hidden kernel TUN interface (`fptn0`, customizable) without occupying Android's single `VpnService` slot.
- Uses strictly isolated Netfilter chains (`FPTN_*`), `table 198`, `pref 12600`, and upper-byte `fwmark 0x46000000/0xFF000000` — never touching system `netd`, `ip6tables`, or `rp_filter`.
- **Secondary VPN Chaining:** Allows running another regular Android `VpnService` app alongside FPTN Root Stealth without routing deadlocks.
- **Adaptive Root Boost:** Dynamically prioritizes tunnel packet handling when the device is cool and idle, and automatically yields when games, high CPU/thermal load, low RAM, or battery saver are active.
- **Safe Rollback:** Stopping the VPN or tapping **Reset Root Rules** synchronously tears down all `FPTN_*` chains, `ip rule`, `table 198`, and `fptn0`.

---

## 6. Appearance, 3D Weather & Backups

- **Language:** Switch instantly between *System*, *English*, and *Русский* in **Settings → Appearance** without dropping your active VPN session.
- **3D Volumetric Weather & Shaders:** Choose from 13 weather modes (*Clear*, *Partly Cloudy*, *Cloudy*, *3D Fog*, *Drizzle*, *Rain*, *Heavy Rain*, *Storm*, *Snow*, *Blizzard*, *Hail*, *Auto Location*, *Off*) with wind-strength and 3D fog-depth sliders, plus optional AGSL water-wave ripples and silk-physics flags. Low-RAM devices automatically reduce shader load via `DevicePerformanceProfile`.
- **Backup & Restore (`Settings → Backup`):** Export full client settings (`BackupCodec` v14), official-compatible FPTN format, or raw tokens to a file (Android SAF) or clipboard.

---

## 7. Troubleshooting & FAQ

- **Error `600` (TLS stream truncated / handshake reset):** Usually caused by ISP DPI blocking the current SNI domain or strategy. Run **Auto-scan SNI**, switch the Reality browser profile (e.g., Chrome 149 / Firefox 150 / Yandex 26.4), or try `TLS Obfuscation`.
- **Error `401` / `403` (Unauthorized):** Your token has expired or been revoked. Request a fresh token from [@fptn_bot](https://t.me/fptn_bot) or your server administrator.
- **Error `609` (Port unreachable):** The selected node is offline or blocked on your carrier. Pick another server or enable **Auto-failover**.
- **Tunnel connects, then drops to `0 B/s` on mobile data:** Enable **Stall Watchdog** (*Gentle* or *Balanced*), set **MTU** to `1360`–`1400`, and switch to **Dual Rolling Tunnel**.
- **VPN killed in background:** Exclude **FPTN Flow** from Android battery optimization in **Settings → Permissions & Battery**.
- **Diagnostics:** Open **Settings → Logs** to inspect or copy the local ring-buffer log (no logs are ever uploaded externally).
