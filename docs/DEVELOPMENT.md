<p align="center">
  <strong>English</strong> &nbsp;·&nbsp; <a href="DEVELOPMENT.ru.md">Русский</a> &nbsp;·&nbsp; <a href="../README.md">README</a> &nbsp;·&nbsp; <a href="USER_GUIDE.md">User Guide</a> &nbsp;·&nbsp; <a href="../ARCHITECTURE.md">Architecture</a>
</p>

---

# Developer Guide — FPTN Flow (Android Fork)

This guide documents the codebase architecture, native JNI bridge, VPN packet lifecycle, Root Stealth Netfilter invariants, bilingual localization architecture, and local testing pipeline.

---

## 1. Module & Package Layout

All application code resides in `:app` (`namespace = "com.example"`, `applicationId = "org.fptnflow.vpn"`):

```text
app/src/main/
├── jniLibs/
│   ├── arm64-v8a/libfptn_native_lib.so
│   ├── armeabi-v7a/libfptn_native_lib.so
│   └── x86_64/libfptn_native_lib.so
├── res/
│   ├── raw/
│   │   ├── russia.sni                  # 997 Russian SNI candidates
│   │   └── global.sni                  # 282 global SNI candidates
│   ├── values/
│   │   ├── strings.xml                 # Default English strings
│   │   ├── always_excluded_apps.xml    # 507 official protected RU app rules
│   │   └── app_exclusion_exceptions.xml# 4 official exclusion exceptions
│   └── values-ru/strings.xml           # Russian string resources
└── java/
    ├── org/fptn/vpn/services/          # IMMUTABLE JNI bridge to libfptn_native_lib.so
    │   ├── snichecker/SniChecker.java
    │   └── websocket/
    │       ├── FptnNativeLoader.java
    │       ├── NativeHttpsClientImpl.java
    │       ├── NativeResponse.java
    │       └── NativeWebSocketClientImpl.java
    └── com/example/
        ├── FptnApplication.kt          # Manual constructor DI graph (Room, DataStore, Repo, Haptics)
        ├── MainActivity.kt             # Edge-to-Edge host, deep-link (fptn:/fptnb:), locale wrapper
        ├── navigation/FptnNavGraph.kt  # Navigation Compose routes
        ├── core/
        │   ├── clipboard/              # ClipboardHelper
        │   ├── config/                 # FptnConstants (official links & probe timeouts)
        │   ├── haptics/                # HapticEngine
        │   ├── logging/                # AppLogStore (in-memory diagnostic ring buffer)
        │   ├── performance/            # DevicePerformanceProfile (shader/effect tiering)
        │   └── ui/
        │       ├── components/         # SettingsComponents, SmoothSettingsSlider, ClockAngleDialControl, AppIconCache
        │       ├── effects/            # WaterWaveModifier (AGSL), CrackedGlassEffects
        │       ├── flags/              # CountryFlagCanvas, FlagMatrixTransitionCanvas, PirateFlagRenderer
        │       ├── localization/       # AppLocalization.kt (LocalIsRussian, tr(), enum localized* helpers)
        │       └── theme/              # Color.kt, Theme.kt, Type.kt (Oceanic M3 palette)
        ├── domain/                     # Pure Kotlin logic (tested on JVM without emulator)
        │   ├── atmosphere/             # SolarAtmosphereEngine
        │   ├── dns/                    # DnsEndpointParser
        │   ├── model/                  # VpnSettings, TokenModels, RoutingAndDnsModels, NetworkPresetModels, AppearanceModels
        │   ├── root/                   # SafeRootRulePlanner
        │   ├── routing/                # SmartRfRouter (82 RU CIDRs), ProtectedRuApps (507 rules + 4 exceptions)
        │   ├── token/                  # FptnTokenParser
        │   └── vpn/                    # StallWatchdogEvaluator, VpnErrorClassifier
        ├── data/
        │   ├── local/                  # FptnDatabase (Room), TokenDao, TokenEntities, SettingsDataStore
        │   ├── remote/                 # WeatherRepository, CoatOfArmsRepository (59 coats of arms)
        │   └── repository/             # FptnRepository, BackupCodec (v14), SponsorsFetcher
        ├── vpn/
        │   ├── dns/                    # TunnelDohResolver (DoH proxy + live Split-DNS forwarder)
        │   ├── root/                   # RootShellManager, RootStealthTunOpener, RootTunFdHelper, AdaptiveRootBoostController
        │   ├── service/                # FptnVpnService, FptnTileService, VpnStateHolder
        │   ├── sni/                    # SniAutoScanner
        │   └── tunnel/                 # FptnProtocolClient, TunPacketLoop
        └── feature/                    # Jetpack Compose screens (incl. WeatherVolumetricRenderer) & FptnViewModel / FptnUiState
```

**Dependency direction:** `feature` → `domain` / `data` / `vpn` → `org.fptn.vpn.services`. The `domain` package contains zero Android UI dependencies so business rules can be verified quickly with standard JUnit tests.

---

## 2. Native JNI Bridge Contract

The prebuilt C++ library `libfptn_native_lib.so` (built from upstream `libfptn` + BoringSSL) exports JNI functions bound by exact package, class, and method signatures:

- `org.fptn.vpn.services.websocket.FptnNativeLoader` — loads `fptn_native_lib` safely once and records any load error.
- `org.fptn.vpn.services.websocket.NativeHttpsClientImpl` — BoringSSL HTTPS client used for `/api/v1/login` and `/api/v1/dns` with MD5 certificate fingerprint verification and censorship strategy injection.
- `org.fptn.vpn.services.websocket.NativeWebSocketClientImpl` — manages the native tunnel handle behind a `ReentrantReadWriteLock` (`nativeCreate`, `nativeRun`, `nativeStop`, `nativeSend`, `nativeIsStarted`, `nativeDestroy`, IPv4/IPv6 getters) and dispatches `onOpenImpl`, `onMessageImpl`, `onFailureImpl` (offloaded to a dedicated daemon executor to avoid native callback deadlocks), and `onSocketOpenedImpl(int fd)` (used to call `VpnService.protect(fd)`).
- `org.fptn.vpn.services.snichecker.SniChecker` — tests candidate SNI domains directly against the target server using the configured bypass strategy.

> **Do not move, rename, or obfuscate `org.fptn.vpn.services.*`.** Also note in `SniSpoofingMode.nativeStrategyName()` that `SNI_REALITY_CHROME_148` and `SNI_REALITY_CHROME_147` map to compiled-in `SNI-REALITY-CHROME-149` and `SNI-REALITY-CHROME-146` respectively to avoid silent fallback to plain `SNI` inside the prebuilt binary.

---

## 3. VPN Lifecycle, Tunneling & Watchdog

- **`FptnVpnService`** (`foregroundServiceType="specialUse"`, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="vpn_tunnel"`):
  - `onStartCommand` always returns `START_NOT_STICKY` and calls `stopSelf(startId)` on null/unknown intents when no tunnel is active.
  - Exposes reactive tunnel state and speed telemetry via `VpnStateHolder` (`StateFlow`), consumed by `FptnViewModel` and `FptnTileService`.
  - Cleanly tears down the TUN file descriptor, releases native handles, and executes idempotent Root rule cleanup on stop or error.
- **`TunPacketLoop` & `TunnelDohResolver`:**
  - Reads IPv4/IPv6 packets from the TUN fd, performs live Split-DNS for `DIRECT_RF` domains (suppressing `AAAA` to prevent IPv6 bypass leaks, forwarding `A` queries via a `protect()`ed `DatagramSocket` to physical-network DNS, and dynamically adding `/32 RETURN` rules in Root mode), resolves other DNS queries via in-tunnel DoH when enabled, and forwards packets over the native WebSocket tunnel.
  - **`StallWatchdogEvaluator`** tracks byte throughput; when silent 0 B/s stalls exceed the configured probe threshold, it fires dual probes (UDP:53 DNS + ICMP) and triggers controlled session recovery if no response arrives within the stall window.
- **`FptnTileService`:**
  - Executes all state lookups and actions inside a `tileScope` (`SupervisorJob() + Dispatchers.IO`) canceled in `onDestroy()`. Never calls `runBlocking` on the Main thread.

---

## 4. Root Stealth Mode & Netfilter Safety (`SafeRootRulePlanner`)

Root Stealth Mode opens a kernel TUN interface (`fptn0`, configurable via `SafeRootRulePlanner.sanitizeTunName`, rejecting `tun0..tun9` collisions) through `libsu` (`RootShellManager` + `RootStealthTunOpener`) instead of binding Android's `VpnService`.

To prevent routing loops, broken Play Services, or false "No Internet" indicators on AOSP/Pixel Android 10–16, `SafeRootRulePlanner` enforces strict invariants verified by `SecurityAndConcurrencyAuditTest`:

1. **Isolated Chains Only:** Creates and jumps only to `FPTN_OUTPUT`, `FPTN_PREROUTING`, `FPTN_POSTROUTING`, `FPTN_MSS`, and `FPTN_DNS_NAT`.
2. **Forbidden Commands Guard (`validateSafety()`):** Explicitly rejects any command touching `rp_filter`, `ip6tables`, `oem_out`, `bw_`, `st_`, `netd`, or `wlan+`.
3. **Upper-Byte Fwmark (`0x46000000/0xFF000000`):** Preserves AOSP's lower 24 fwmark bits (`netId`, `explicitlySelected`, `protectedFromVpn`, permission bits).
4. **Routing Table & Priority:** Uses `table 198` at `pref 12600` (strictly after AOSP `RULE_PRIORITY_SECURE_VPN = 12000` and `RULE_PRIORITY_PROHIBIT_NON_VPN = 12500`, before default network rules).
5. **Mandatory UID Bypasses:** Always bypasses `appUid`, `AID_ROOT` (`0`), and `AID_NETWORK_STACK` (`1073`), plus VPN server IP, loopback, and RFC1918 LAN.
6. **Secondary VPN Chaining:** Marks FPTN's own outbound sockets with `0x20000/0x20000` (`protectedFromVpn`), allowing `Apps → Secondary VpnService (tun0) → FPTN Root Stealth (fptn0) → Internet` without routing loops.
7. **Serialized Execution:** `RootShellManager` serializes Netfilter mutations with a `ReentrantLock`, keeping `suspend` calls outside the lock.

---

## 5. Bilingual Localization System (EN / RU)

The client supports three language modes (`AppLanguage.SYSTEM`, `AppLanguage.RU`, `AppLanguage.EN`) stored in `SettingsDataStore` and switched instantaneously without recreating `MainActivity`:

- **Resource layer:** `app/src/main/res/values/strings.xml` (English default) and `app/src/main/res/values-ru/strings.xml` (Russian).
- **Runtime CompositionLocal layer (`AppLocalization.kt`):**
  - `rememberLocalizedEnvironment()` builds a locale-overridden `Configuration` and wraps the Activity context via `LocalizedActivityContextWrapper` (preserving Activity delegation for `startActivity()` and system services) while providing `LocalContext`, `LocalConfiguration`, and `LocalIsRussian`.
  - Use `@Composable tr(ru, en)` in composables.
  - Inside non-composable lambdas (`onClick`, snackbar callbacks, slider formatters), capture `val isRu = LocalIsRussian.current` in the parent composable and call `tr(isRu, ru, en)`.
  - Domain enums and presets expose `@Composable` `.localizedTitle()`, `.localizedShortTitle()`, `.localizedSubtitle()`, and `.localizedName()` extensions in `AppLocalization.kt`.

---

## 6. Build, Verification & Testing

### Toolchain Versions (`gradle/libs.versions.toml`)
- AGP `9.1.1` · Kotlin `2.4.21` · KSP `2.3.5` · Compose BOM `2024.09.00`
- Room `2.7.0` · DataStore `1.1.7` · Navigation Compose `2.8.9`
- OkHttp `4.10.0` · Brotli `0.1.2` · ZXing `3.5.3` · libsu `6.0.0` · JNA `5.17.0`
- Robolectric `4.16.1` · Coroutines Test `1.10.2`

### Commands

```bash
# Build Debug APK (all bundled ABIs: arm64-v8a, armeabi-v7a, x86_64)
gradle :app:assembleDebug

# Single-ABI build for faster local iteration
gradle :app:assembleDebug -PtargetAbi=arm64-v8a

# Run all 4 JVM & Robolectric test suites
gradle :app:testDebugUnitTest

# Run static analysis
gradle :app:lintDebug
```

### Existing Test Suites (`app/src/test/java/com/example/`)
- `FptnDomainUnitTest.kt` — token parsing (`fptnb:`, `fptn:`, JSON), DNS endpoint validation, Smart RU routing (82 CIDRs, 507 protected apps, permanent RU lock, `www.` expansion), Split-DNS packet parsing, presets, backup codec v14, weather WMO mapping, and watchdog logic.
- `SecurityAndConcurrencyAuditTest.kt` — `SafeRootRulePlanner` safety invariants, forbidden Netfilter patterns, UID bypass rules, concurrency checks.
- `FptnUiInteractionRobolectricTest.kt` — Compose UI interaction & navigation journeys via Robolectric.
- `ExampleRobolectricTest.kt` — Robolectric resource & application smoke verification.

---

## 7. Building Multi-ABI APKs & Signed Releases

`app/build.gradle.kts` supports building either a universal APK containing all 3 bundled ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) or lean per-ABI APKs via `-PtargetAbi=<abi>`:

### Release Signing Environment Variables
Configure signing credentials via environment variables (never commit `.jks` or `.keystore` files to Git):

```bash
export KEYSTORE_PATH=/path/to/your-release-key.jks
export STORE_PASSWORD="your-keystore-password"
export KEY_PASSWORD="your-key-password"
# Key alias expected by app/build.gradle.kts: "upload"
```

### Building Per-Architecture Release APKs & Universal APK

```bash
# 1. ARM 64-bit (recommended for modern Android phones & tablets)
gradle :app:assembleRelease -PtargetAbi=arm64-v8a
cp app/build/outputs/apk/release/app-release.apk FPTN-Flow-v0.1-arm64-v8a.apk

# 2. ARM 32-bit (legacy Android devices)
gradle :app:assembleRelease -PtargetAbi=armeabi-v7a
cp app/build/outputs/apk/release/app-release.apk FPTN-Flow-v0.1-armeabi-v7a.apk

# 3. x86_64 (emulators & Chromebooks)
gradle :app:assembleRelease -PtargetAbi=x86_64
cp app/build/outputs/apk/release/app-release.apk FPTN-Flow-v0.1-x86_64.apk

# 4. Universal APK (all three ABIs bundled)
gradle :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk FPTN-Flow-v0.1-universal.apk

# 5. Android App Bundle (.aab)
gradle :app:bundleRelease
```

Attach the generated APK files (plus SHA-256 checksums via `sha256sum FPTN-Flow-v0.1-*.apk > SHA256SUMS.txt`) to your GitHub Release, or push a version tag (`git tag v0.1 && git push origin v0.1`) so `.github/workflows/release.yml` builds and attaches all four APKs automatically.


