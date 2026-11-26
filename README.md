<div align="center">

# FPTN Flow

**Reworked Material 3 Android client for the open-source [FPTN](https://fptn.org/) protocol**

**English** &nbsp;·&nbsp; [Русский](README.ru.md)

[![Version](https://img.shields.io/badge/version-v0.1-00667D?style=flat-square)](https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square)](LICENSE)

[**Download APK (Releases)**](https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest) &nbsp;&nbsp;|&nbsp;&nbsp; [**Get Token (@fptn_bot)**](https://t.me/fptn_bot) &nbsp;&nbsp;|&nbsp;&nbsp; [**User Guide**](docs/USER_GUIDE.md)

</div>

---

> [!NOTE]
> **Personal solo project.** FPTN Flow started as an app I built for myself and maintain solo — a deeply reworked Android client on top of the open-source FPTN protocol and its native BoringSSL core. If you find it useful and enjoy using it, I would genuinely appreciate a ⭐ star, feedback, or bug reports in [Issues](https://github.com/jixijehofiri01-creator/FPTN-FLOW/issues).

---

## Why FPTN Flow

FPTN Flow is fully compatible with official tokens from **[@fptn_bot](https://t.me/fptn_bot)** and self-hosted `fptn` servers, while rethinking the everyday Android experience for both phones and tablets:

- **Material 3 UI for phones & tablets** — dynamic colors, live speed telemetry, customizable home quick tiles, instant **English / Russian** language switching, and adaptive two-pane tablet layouts.
- **Multiple tokens & smart server picker** — store multiple official or private tokens under custom names, encrypt credentials at rest with Android Keystore (AES-256-GCM), search servers, and auto-pick the lowest ping.
- **Local banks & services keep working** — protected Russian banking/government apps and regional routes bypass the tunnel automatically so you don't have to toggle VPN constantly or expose servers to blocks.
- **Resilient mobile connections** — automatic Wi-Fi ↔ 4G/5G profile switching, stall watchdog recovery, and built-in **Smart DNS** (17 DoH/DoT/IPv4 presets) + custom domain routing rules.
- **Built-in update checker & backups** — checks GitHub Releases for new APKs matching your device CPU, and exports/imports tokens and settings cleanly.
- **Optional Root Stealth mode** — kernel `fptn0` tunnel for rooted devices (Magisk / KernelSU / APatch) with strict input sanitization and isolated `iptables` chains.

> [!TIP]
> **About the wild Material 3 visual effects:** volumetric 3D weather, animated country flags, liquid-glass connect waves, solar lighting, and tactile haptics are a fun visual playground exploring what Material 3 and Compose Canvas can do. Prefer a strict, calm interface? **Every single visual effect can be turned off completely in *Settings → Appearance*** (and approximate location `ACCESS_COARSE_LOCATION` is never requested unless you explicitly enable Auto-Location weather).

---

## Quick Start

1. Open **[GitHub Releases](https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest)** and install the APK for your device (for almost all modern Android phones & tablets, pick **`arm64-v8a`**).
2. Get a free access token from **[@fptn_bot](https://t.me/fptn_bot)** (or use your own FPTN server token).
3. Go to **Settings → Tokens**, paste the token (`fptnb:`, `fptn:`, or JSON), return to **Home**, and pull the switch to connect.

<details>
<summary><strong>Which APK file should I download?</strong></summary>

| File | Best for |
| :--- | :--- |
| `FPTN-Flow-v0.1-arm64-v8a.apk` | **Recommended** — modern 64-bit Android smartphones & tablets |
| `FPTN-Flow-v0.1-armeabi-v7a.apk` | Older 32-bit ARM Android devices |
| `FPTN-Flow-v0.1-x86_64.apk` | Intel/AMD tablets, Chromebooks, and emulators |
| `FPTN-Flow-v0.1-universal.apk` | Universal build for any Android 7.0+ (includes all 3 ABIs) |
| `SHA256SUMS.txt` | SHA-256 verification hashes for all APKs |

</details>

---

## Documentation

| Guide | Audience | Contents |
| :--- | :--- | :--- |
| [User Guide (EN)](docs/USER_GUIDE.md) · [RU](docs/USER_GUIDE.ru.md) | Everyday users | Setup, DPI bypass tips, Smart DNS, browser `.ru` routing, FAQ |
| [Developer Guide (EN)](docs/DEVELOPMENT.md) · [RU](docs/DEVELOPMENT.ru.md) | Developers | Code layout, JNI core contract, tests, and multi-ABI release builds |
| [Architecture](ARCHITECTURE.md) | Developers | Module diagram, TUN packet loop, threading, and security design |
| [What Changed](DIFFERENCES.md) | Everyone | Detailed comparison with the original FPTN Android client |
| [Contributing](CONTRIBUTING.md) | Contributors | Bug reports, pull requests, and verification checklist |

---

<details>
<summary><strong>Original FPTN credits, native binary verification & GPL-3.0 license</strong></summary>

FPTN Flow is built on top of the open-source **FPTN** protocol and prebuilt native library (`libfptn_native_lib.so`) from **[FPTN Project](https://github.com/fptn-project)**.

- Upstream project website: [fptn.org](https://fptn.org/)
- Upstream Android client: [fptn-project/FptnClient-Android](https://github.com/fptn-project/FptnClient-Android)
- Upstream C++ core source: [fptn-project/fptn](https://github.com/fptn-project/fptn)
- Native binary SHA-256 checksums: [app/src/main/jniLibs/SHA256SUMS.txt](app/src/main/jniLibs/SHA256SUMS.txt)

Licensed under **GNU General Public License v3.0 (GPL-3.0)** — see [LICENSE](LICENSE).

</details>
