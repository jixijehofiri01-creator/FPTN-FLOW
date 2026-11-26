# Contributing to FPTN Flow (Android Fork)

Thank you for your interest in contributing. This project is an independent Android client fork built on top of the official [FPTN Project](https://github.com/fptn-project) protocol and native `libfptn` core.

---

## Core Engineering Invariants

Before opening a pull request, verify that your changes respect these non-negotiable invariants:

1. **Never move or rename JNI bridge classes (`org.fptn.vpn.services.*`):**
   `FptnNativeLoader`, `NativeHttpsClientImpl`, `NativeWebSocketClientImpl`, `NativeResponse`, and `SniChecker` bind directly to `Java_org_fptn_vpn_*` symbols inside `libfptn_native_lib.so`. Renaming packages, classes, or `native` signatures will break runtime loading.
2. **Preserve RU / FPTN server protection:**
   `BypassRegion.NONE` remains locked in UI, and `SmartRfRouter` / `ProtectedRuApps` keep domestic Russian subnets and banking/gov apps off FPTN exit nodes while ensuring Telegram DC subnets traverse the VPN tunnel.
3. **Follow Root Stealth Netfilter safety rules (`SafeRootRulePlanner`):**
   - Only use isolated `FPTN_*` chains, `table 198`, `pref 12600`, and `fwmark 0x46000000/0xFF000000`.
   - Never touch `ip6tables`, `rp_filter`, `netd`, `oem_out`, `bw_*`, `st_*`, or `wlan+`.
   - Always pass generated commands through `SafeRootRulePlanner.validateSafety()`.
   - Always bypass `uid 0` (`AID_ROOT`), `uid 1073` (`AID_NETWORK_STACK`), and the application UID.
4. **Keep Compose UI bilingual (English + Russian):**
   - Add new XML strings to both `app/src/main/res/values/strings.xml` (English default) and `app/src/main/res/values-ru/strings.xml` (Russian).
   - For Compose-level dynamic copy, use `tr(ru, en)` (or `val isRu = LocalIsRussian.current` + `tr(isRu, ru, en)` inside non-composable lambdas) and domain enum helpers in `com.example.core.ui.localization.AppLocalization.kt`.
5. **Preserve UI test tags & accessibility:**
   Keep existing `Modifier.testTag(...)` identifiers intact and add `contentDescription` to all interactive icons/buttons with at least `48.dp` touch targets.
6. **Do not block the Main thread or Service lifecycle:**
   Use Kotlin Coroutines (`Dispatchers.IO`) for network/root/database work, keep `FptnTileService` non-blocking, and ensure `FptnVpnService.onStartCommand` returns `START_NOT_STICKY`.

---

## Development Workflow

1. Fork the repository and create a descriptive feature or fix branch.
2. Make focused, modular edits following the layered architecture (`feature/*` → `domain/*` / `data/*` / `vpn/*`; see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)).
3. Add or update unit/Robolectric tests in `app/src/test/java/com/example/` for any domain logic, security rules, or UI flows you change.
4. Verify the build and test suite locally before submitting:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
gradle :app:lintDebug
```

5. Open a pull request explaining **what** changed, **why** it is needed, and how it was tested.

---

## Upstream Protocol & Core Issues

Issues related to the FPTN server, C++ `libfptn` core, official tokens, or desktop clients belong in the upstream [fptn-project](https://github.com/fptn-project) repositories.
