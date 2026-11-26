# Fork Additions & Relationship to Upstream FPTN

<p>
  <strong>English</strong> &nbsp;|&nbsp; <a href="#отличия-форка-и-связь-с-оригинальным-fptn-ru">Русский</a>
</p>

This document summarizes what this repository preserves directly from the official [FptnClient-Android](https://github.com/fptn-project/FptnClient-Android) client and [fptn-project/fptn](https://github.com/fptn-project/fptn) (`libfptn`), alongside the client-side extensions added in this independent fork.

---

## Inherited Upstream Foundation (Preserved Intact)

- **Native Protocol & Cryptography (`libfptn_native_lib.so`):** Official BoringSSL-based native library for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, invoked through unmodified JNI entry points in `org.fptn.vpn.services.*`.
- **Token Compatibility:** Full decoding of official `fptnb:` (Brotli + Base64), `fptn:`, `fptn://`, and JSON tokens including `censored_zone_servers` and per-server `md5_fingerprint` pinning.
- **Protocol Handshake & Tunneling:** HTTPS `/api/v1/login` and `/api/v1/dns` negotiation, X.509 MD5 certificate verification, and binary WebSocket IP packet encapsulation.
- **Upstream Bypass Primitives:** Native `SNI / Reality` browser fingerprint strategies, `TLS Obfuscation`, native `SniChecker`, and `Rolling` / `Dual` / `Triple` sliding tunnel strategies.
- **Official Ecosystem Links:** Direct attribution and links to `fptn.org`, `github.com/fptn-project`, and `@fptn_bot` for official token issuance and project sponsors (`SponsorsFetcher`).

---

## Client-Side Additions in This Fork

### 1. Jetpack Compose & Material 3 Architecture
- Rebuilt presentation layer in Kotlin + Jetpack Compose (`Material 3`, Edge-to-Edge, `Navigation Compose`) with reactive `StateFlow` state management (`FptnViewModel`, `VpnStateHolder`).
- Interactive connection hero (`MinimalistShieldHero`) with smooth cross-fades across 6 connection states, pull-to-connect gesture track (`PullToConnectTrack`), and live throughput telemetry graph (`SpeedTelemetryChart`).
- Procedural vector flags (`CountryFlagCanvas`, `FlagMatrixTransitionCanvas` across 197 countries, 59 official coats of arms), optional AGSL water-wave shader (`WaterWaveModifier`, 5 physics presets), 3D volumetric raymarched fog/clouds & 13 weather modes (`WeatherVolumetricRenderer`, `WeatherAtmosphereOverlay`, `SolarAtmosphereEngine`), device-tier performance guard (`DevicePerformanceProfile`), and tactile feedback engine (`HapticEngine`).

### 2. Multi-Token Storage, Presets & Manual Locks
- Room-backed multi-token manager (`FptnDatabase`, `TokenDao`) supporting multiple active/inactive token profiles, custom token names, automatic ping sorting, and backup server failover.
- Four built-in network presets (`Mobile 4G/5G`, `Wi-Fi / Home`, `DPI Maximum`, `Games & Calls`) plus custom presets with automatic Wi-Fi ↔ cellular switching.
- Dedicated `ManualOverridesScreen` tracking 10 lockable parameters (`ManualOverrideKey`) so manually chosen settings stay pinned until explicitly returned to automatic control.

### 3. DNS, Smart Routing & Censorship Resilience
- In-tunnel DoH proxy and live Split-DNS (`TunnelDohResolver`) with 4 DNS modes (`Auto FPTN`, `Smart DNS DoH`, `AdGuard DoH`, `Custom IPv4`), 17 built-in catalog resolvers, unlimited user-defined DoH/DoT/IPv4 entries, and direct physical-network lookup + `AAAA` suppression for bypassed domains.
- Permanently locked Russian server protection (cannot be disabled even with Root): **82 domestic Russian ASN CIDRs** (`SmartRfRouter`) and **507 official protected RU app rules** (+ 4 exceptions from `always_excluded_apps.xml`) paired with mandatory VPN routing for Telegram DC subnets, plus selectable regional profiles (Russia, China, Iran, Vietnam).
- Per-app split tunneling (`All`, `Exclude selected`, `Include selected`) and custom direct/VPN domain lists with `www.` variant pre-resolution.
- Dual-probe `StallWatchdogEvaluator` (UDP:53 DNS + ICMP) for silent 0 B/s tunnel freezes, CGNAT keepalive, and automatic SNI domain scanner/rotator (`SniAutoScanner`, `russia.sni` 997 + `global.sni` 282 domains).

### 4. Optional Root Stealth Mode (`fptn0`)
- Kernel TUN driver integration (`RootStealthTunOpener`, `RootShellManager`, `SafeRootRulePlanner`, `AdaptiveRootBoostController`) for rooted devices (Magisk / KernelSU / APatch) without using Android's single `VpnService` slot.
- Isolated Netfilter chains (`FPTN_*`), routing `table 198`, priority `pref 12600`, upper-byte `fwmark 0x46000000/0xFF000000`, `AID_ROOT`/`AID_NETWORK_STACK` bypass, dynamic `/32` Split-DNS bypass injection, adaptive CPU/thermal yielding to games, and `protectedFromVpn` (`0x20000/0x20000`) support for chaining with a second non-root VPN app.

### 5. Localization, Backup & Diagnostics
- Instant in-app English/Russian language switching (`AppLanguage`: System / Русский / English) without recreating the Activity or dropping the tunnel (`AppLocalization.kt`, `values/`, `values-ru/`).
- Versioned backup codec (`BackupCodec` v14) supporting full settings export, official FPTN-compatible format, and raw tokens via Android Storage Access Framework (SAF) or clipboard.
- Built-in ring-buffer log viewer (`AppLogStore`, `LogsScreen`), non-blocking Quick Settings Tile (`FptnTileService`), and 4 JVM/Robolectric unit test suites covering domain logic, concurrency, Netfilter safety, and UI flows.

---

<a id="отличия-форка-и-связь-с-оригинальным-fptn-ru"></a>
# Отличия форка и связь с оригинальным FPTN (RU)

Этот раздел кратко перечисляет базовые компоненты, сохранённые напрямую из официального [FptnClient-Android](https://github.com/fptn-project/FptnClient-Android) и [fptn-project/fptn](https://github.com/fptn-project/fptn) (`libfptn`), а также клиентские расширения данного независимого форка.

### Базовая основа оригинального FPTN (без изменений протокола)
- Нативное C++ ядро `libfptn_native_lib.so` (`arm64-v8a`, `armeabi-v7a`, `x86_64`) и нетронутый JNI-слой `org.fptn.vpn.services.*`.
- Полная совместимость с форматами токенов `fptnb:`, `fptn:`, `fptn://`, JSON, `censored_zone_servers` и проверкой MD5-отпечатков сертификатов.
- Нативные методы обхода DPI: `SNI / Reality` (отпечатки браузеров BoringSSL), `TLS Obfuscation`, `SniChecker` и скользящие туннели (`Rolling` / `Dual` / `Triple`).

### Расширения клиентского форка
- **Интерфейс и визуальная система:** переработанный UI на Jetpack Compose (Material 3, Edge-to-Edge), стеклянный щит состояния (`MinimalistShieldHero`), трек подключения свайпом, график скорости, процедурные флаги (197 стран, 59 гербов), опциональный AGSL-шейдер волны, объёмный 3D-туман и 13 режимов погоды (`WeatherVolumetricRenderer`), авто-профиль производительности и движок тактильного отклика (`HapticEngine`).
- **Мульти-токены, пресеты и ручные фиксации:** хранение нескольких токенов в Room, автовыбор по пингу, 4 встроенных + пользовательские сетевые пресеты с автопереключением Wi-Fi ↔ 4G/5G и экран ручных фиксаций (`ManualOverridesScreen`).
- **DNS, маршрутизация и устойчивость:** встроенный DoH-прокси и живой Split-DNS (`TunnelDohResolver`) с каталогом из 17+ серверов, постоянная защита РФ без возможности отключения даже в Root (82 РФ CIDR + 507 официальных правил защищённых приложений и 4 исключения) с обязательным маршрутом Telegram через VPN, раздельное туннелирование по приложениям и доменам (с предразрешением `www.`), сторож зависаний `0 Б/с` (UDP:53 + ICMP) и автосканер SNI (`russia.sni` 997 + `global.sni` 282).
- **Опциональный Root Stealth (`fptn0`):** ядерный TUN на Magisk/KernelSU/APatch с изолированными цепочками `FPTN_*`, `table 198`, `pref 12600`, `fwmark 0x46000000/0xFF000000`, исключением системных UID (`0`, `1073`), динамическим `/32`-обходом от Split-DNS, адаптивным ускорением с уступкой играм и поддержкой цепочки со вторым обычным `VpnService`.
- **Локализация, бэкапы и диагностика:** мгновенное переключение RU/EN без пересоздания Activity, экспорт/импорт резервных копий (v14, официальный формат FPTN, сырые токены), встроенный журнал диагностики, плитка QS и 4 набора JVM/Robolectric тестов.
