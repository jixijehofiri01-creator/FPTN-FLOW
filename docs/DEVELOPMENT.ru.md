<p align="center">
  <a href="DEVELOPMENT.md">English</a> &nbsp;·&nbsp; <strong>Русский</strong> &nbsp;·&nbsp; <a href="../README.ru.md">README</a> &nbsp;·&nbsp; <a href="USER_GUIDE.ru.md">Руководство пользователя</a> &nbsp;·&nbsp; <a href="../ARCHITECTURE.md">Архитектура</a>
</p>

---

# Руководство разработчика — FPTN Flow (Android-форк)

Документ описывает архитектуру проекта, контракт JNI-моста к нативному ядру, жизненный цикл VPN-сервиса, инварианты безопасности режима Root Stealth, систему мгновенной локализации RU/EN и локальный контур тестирования.

---

## 1. Структура модулей и пакетов

Весь код приложения расположен в модуле `:app` (`namespace = "com.example"`, `applicationId = "org.fptnflow.vpn"`):

```text
app/src/main/
├── jniLibs/
│   ├── arm64-v8a/libfptn_native_lib.so
│   ├── armeabi-v7a/libfptn_native_lib.so
│   └── x86_64/libfptn_native_lib.so
├── res/
│   ├── raw/
│   │   ├── russia.sni                  # 997 российских SNI-доменов
│   │   └── global.sni                  # 282 глобальных SNI-домена
│   ├── values/
│   │   ├── strings.xml                 # Английские строки по умолчанию
│   │   ├── always_excluded_apps.xml    # 507 официальных правил защищённых РФ-приложений
│   │   └── app_exclusion_exceptions.xml# 4 официальных исключения
│   └── values-ru/strings.xml           # Русские строковые ресурсы
└── java/
    ├── org/fptn/vpn/services/          # НЕИЗМЕНЯЕМЫЙ JNI-мост к libfptn_native_lib.so
    │   ├── snichecker/SniChecker.java
    │   └── websocket/
    │       ├── FptnNativeLoader.java
    │       ├── NativeHttpsClientImpl.java
    │       ├── NativeResponse.java
    │       └── NativeWebSocketClientImpl.java
    └── com/example/
        ├── FptnApplication.kt          # Ручная сборка графа зависимостей (Room, DataStore, Repo, Haptics)
        ├── MainActivity.kt             # Edge-to-Edge, deep-link (fptn:/fptnb:), обёртка локали
        ├── navigation/FptnNavGraph.kt  # Маршруты Navigation Compose
        ├── core/
        │   ├── clipboard/              # ClipboardHelper
        │   ├── config/                 # FptnConstants (официальные ссылки и таймауты проб)
        │   ├── haptics/                # HapticEngine
        │   ├── logging/                # AppLogStore (кольцевой буфер логов в памяти)
        │   ├── performance/            # DevicePerformanceProfile (адаптация эффектов под железо)
        │   └── ui/
        │       ├── components/         # SettingsComponents, SmoothSettingsSlider, ClockAngleDialControl, AppIconCache
        │       ├── effects/            # WaterWaveModifier (AGSL), CrackedGlassEffects
        │       ├── flags/              # CountryFlagCanvas, FlagMatrixTransitionCanvas, PirateFlagRenderer
        │       ├── localization/       # AppLocalization.kt (LocalIsRussian, tr(), расширения localized*)
        │       └── theme/              # Color.kt, Theme.kt, Type.kt (палитра Oceanic Material 3)
        ├── domain/                     # Чистая Kotlin-логика (проверяется JVM-тестами без эмулятора)
        │   ├── atmosphere/             # SolarAtmosphereEngine
        │   ├── dns/                    # DnsEndpointParser
        │   ├── model/                  # VpnSettings, TokenModels, RoutingAndDnsModels, NetworkPresetModels, AppearanceModels
        │   ├── root/                   # SafeRootRulePlanner
        │   ├── routing/                # SmartRfRouter (82 РФ CIDR), ProtectedRuApps (507 правил + 4 исключения)
        │   ├── token/                  # FptnTokenParser
        │   └── vpn/                    # StallWatchdogEvaluator, VpnErrorClassifier
        ├── data/
        │   ├── local/                  # FptnDatabase (Room), TokenDao, TokenEntities, SettingsDataStore
        │   ├── remote/                 # WeatherRepository, CoatOfArmsRepository (59 гербов)
        │   └── repository/             # FptnRepository, BackupCodec (v14), SponsorsFetcher
        ├── vpn/
        │   ├── dns/                    # TunnelDohResolver (DoH-прокси + живой Split-DNS)
        │   ├── root/                   # RootShellManager, RootStealthTunOpener, RootTunFdHelper, AdaptiveRootBoostController
        │   ├── service/                # FptnVpnService, FptnTileService, VpnStateHolder
        │   ├── sni/                    # SniAutoScanner
        │   └── tunnel/                 # FptnProtocolClient, TunPacketLoop
        └── feature/                    # Экраны Jetpack Compose (включая WeatherVolumetricRenderer) и FptnViewModel / FptnUiState
```

**Направление зависимостей:** `feature` → `domain` / `data` / `vpn` → `org.fptn.vpn.services`. Слой `domain` не зависит от Android UI, что позволяет быстро проверять бизнес-логику модульными JVM-тестами.

---

## 2. Контракт нативного JNI-моста

Предсобранная библиотека `libfptn_native_lib.so` (собрана из оригинального C++ ядра `libfptn` + BoringSSL) экспортирует JNI-символы, жёстко привязанные к именам пакетов, классов и сигнатурам `native`-методов:

- `org.fptn.vpn.services.websocket.FptnNativeLoader` — однократная безопасная загрузка `fptn_native_lib` с сохранением диагностической ошибки при сбое.
- `org.fptn.vpn.services.websocket.NativeHttpsClientImpl` — нативный HTTPS-клиент на BoringSSL для `/api/v1/login` и `/api/v1/dns` с проверкой MD5-отпечатка сертификата и передачей стратегии обхода.
- `org.fptn.vpn.services.websocket.NativeWebSocketClientImpl` — управляет нативным дескриптором под `ReentrantReadWriteLock` (`nativeCreate`, `nativeRun`, `nativeStop`, `nativeSend`, `nativeIsStarted`, `nativeDestroy`, геттеры IPv4/IPv6) и принимает обратные вызовы `onOpenImpl`, `onMessageImpl`, `onFailureImpl` (вынесен на отдельный daemon-executor во избежание дедлоков из нативных потоков) и `onSocketOpenedImpl(int fd)` (для вызова `VpnService.protect(fd)`).
- `org.fptn.vpn.services.snichecker.SniChecker` — нативная проверка кандидатов SNI напрямую против выбранного сервера с активной стратегией обхода.

> **Запрещено перемещать, переименовывать или обфусцировать пакет `org.fptn.vpn.services.*`.** Также учтите, что в `SniSpoofingMode.nativeStrategyName()` профили `SNI_REALITY_CHROME_148` и `SNI_REALITY_CHROME_147` сопоставлены со встроенными в бинарник `SNI-REALITY-CHROME-149` и `SNI-REALITY-CHROME-146`, чтобы избежать незаметного отката C++ ядра к базовому `SNI`.

---

## 3. Жизненный цикл VPN, туннелирование и Watchdog

- **`FptnVpnService`** (`foregroundServiceType="specialUse"`, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="vpn_tunnel"`):
  - `onStartCommand` всегда возвращает `START_NOT_STICKY` и вызывает `stopSelf(startId)` при получении пустого или неизвестного Intent без активного туннеля.
  - Публикует состояние подключения и телеметрию скорости через `VpnStateHolder` (`StateFlow`), на который подписаны `FptnViewModel` и `FptnTileService`.
  - При остановке или ошибке гарантированно закрывает TUN-дескриптор, освобождает нативные ресурсы и выполняет идемпотентный откат Root-правил.
- **`TunPacketLoop` и `TunnelDohResolver`:**
  - Читает IPv4/IPv6 пакеты из TUN, выполняет живой Split-DNS для прямых (`DIRECT_RF`) доменов (подавляет `AAAA`, пересылает `A`-запросы через защищённый `DatagramSocket` в физическую сеть и в Root-режиме динамически добавляет `/32 RETURN` до ответа), разрешает остальные DNS-запросы через внутритуннельный DoH при активном Smart DNS и отправляет трафик через нативный WebSocket-туннель.
  - **`StallWatchdogEvaluator`** отслеживает пропускную способность; при простое `0 Б/с` дольше порога отправляет двойные пробы (UDP:53 DNS + ICMP) и инициирует мягкое переподключение, если ответ не приходит за время `watchdogStallSec`.
- **`FptnTileService`:**
  - Выполняет все операции в `tileScope` (`SupervisorJob() + Dispatchers.IO`), отменяемом в `onDestroy()`, без `runBlocking` на главном потоке.

---

## 4. Режим Root Stealth и безопасность Netfilter (`SafeRootRulePlanner`)

Root Stealth открывает ядерный интерфейс TUN (`fptn0`, валидируется через `SafeRootRulePlanner.sanitizeTunName` с защитой от пересечения с системными `tun0..tun9`) через `libsu` (`RootShellManager` + `RootStealthTunOpener`) без использования системного слота `VpnService`.

Чтобы исключить петли маршрутизации, поломку Google Play Services и ложный восклицательный знак «Нет интернета» на AOSP/Pixel Android 10–16, `SafeRootRulePlanner` соблюдает строгие инварианты (проверяются в `SecurityAndConcurrencyAuditTest`):

1. **Только изолированные цепочки:** `FPTN_OUTPUT`, `FPTN_PREROUTING`, `FPTN_POSTROUTING`, `FPTN_MSS` и `FPTN_DNS_NAT`.
2. **Фильтр запрещённых команд (`validateSafety()`):** блокирует любые правила, затрагивающие `rp_filter`, `ip6tables`, `oem_out`, `bw_`, `st_`, `netd` и `wlan+`.
3. **Старший байт метки (`0x46000000/0xFF000000`):** не перезаписывает младшие 24 бита AOSP fwmark (`netId`, `explicitlySelected`, `protectedFromVpn`, биты разрешений).
4. **Таблица маршрутизации и приоритет:** `table 198` с приоритетом `pref 12600` (строго после системных `RULE_PRIORITY_SECURE_VPN = 12000` и `RULE_PRIORITY_PROHIBIT_NON_VPN = 12500`, но до правил сети по умолчанию).
5. **Обязательные исключения UID:** всегда исключены `appUid`, `AID_ROOT` (`0`), `AID_NETWORK_STACK` (`1073`), IP сервера VPN, loopback и локальные подсети RFC1918.
6. **Цепочка со вторым VPN:** сокеты FPTN получают бит `0x20000/0x20000` (`protectedFromVpn`), что позволяет схеме `Приложения → Другой VpnService (tun0) → FPTN Root Stealth (fptn0) → Интернет` работать без взаимных блокировок.
7. **Сериализация операций:** `RootShellManager` защищает изменения Netfilter через `ReentrantLock`, вынося `suspend`-вызовы за пределы блокировки.

---

## 5. Система двуязычной локализации (RU / EN)

Клиент поддерживает три режима (`AppLanguage.SYSTEM`, `AppLanguage.RU`, `AppLanguage.EN`), сохраняемых в `SettingsDataStore` с мгновенным применением без пересоздания `MainActivity`:

- **XML-ресурсы:** `app/src/main/res/values/strings.xml` (английский по умолчанию) и `app/src/main/res/values-ru/strings.xml` (русский).
- **Слой `CompositionLocal` (`AppLocalization.kt`):**
  - `rememberLocalizedEnvironment()` формирует локализованную `Configuration`, оборачивает контекст Activity через `LocalizedActivityContextWrapper` (сохраняя делегирование `startActivity()` и системных сервисов) и предоставляет `LocalContext`, `LocalConfiguration` и `LocalIsRussian`.
  - В `@Composable`-функциях используйте `tr(ru, en)`.
  - Внутри не-composable лямбд (`onClick`, колбэки сообщений, форматтеры ползунков) захватывайте `val isRu = LocalIsRussian.current` в родительском composable и вызывайте `tr(isRu, ru, en)`.
  - Для доменных enum-ов и пресетов используйте расширения `.localizedTitle()`, `.localizedShortTitle()`, `.localizedSubtitle()` и `.localizedName()` из `AppLocalization.kt`.

---

## 6. Сборка, проверка и тестирование

### Версии зависимостей (`gradle/libs.versions.toml`)
- AGP `9.1.1` · Kotlin `2.4.21` · KSP `2.3.5` · Compose BOM `2024.09.00`
- Room `2.7.0` · DataStore `1.1.7` · Navigation Compose `2.8.9`
- OkHttp `4.10.0` · Brotli `0.1.2` · ZXing `3.5.3` · libsu `6.0.0` · JNA `5.17.0`
- Robolectric `4.16.1` · Coroutines Test `1.10.2`

### Команды

```bash
# Сборка Debug APK (все архитектуры: arm64-v8a, armeabi-v7a, x86_64)
gradle :app:assembleDebug

# Быстрая сборка под одну архитектуру
gradle :app:assembleDebug -PtargetAbi=arm64-v8a

# Запуск всех 4 наборов JVM и Robolectric тестов
gradle :app:testDebugUnitTest

# Статический анализ кода
gradle :app:lintDebug
```

### Существующие наборы тестов (`app/src/test/java/com/example/`)
- `FptnDomainUnitTest.kt` — парсер токенов (`fptnb:`, `fptn:`, JSON), валидатор DNS, Smart RU-роутинг (82 CIDR, 507 правил, необратимая блокировка РФ, расширение `www.`), разбор Split-DNS пакетов, сетевые пресеты, кодек бэкапов v14, маппинг погоды WMO и логика Watchdog.
- `SecurityAndConcurrencyAuditTest.kt` — проверка инвариантов `SafeRootRulePlanner`, запрещённых команд Netfilter, исключений UID и конкурентности.
- `FptnUiInteractionRobolectricTest.kt` — проверка пользовательских сценариев Compose UI под Robolectric.
- `ExampleRobolectricTest.kt` — дымовая проверка ресурсов приложения под Robolectric.

---

## 7. Сборка APK под разные архитектуры и подписанных релизов

`app/build.gradle.kts` поддерживает сборку как универсального APK сразу со всеми тремя ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`), так и компактных сборок под каждую архитектуру через параметр `-PtargetAbi=<abi>`:

### Переменные окружения для подписи Release
Передайте параметры ключа подписи через переменные окружения (никогда не добавляйте `.jks` и `.keystore` в Git):

```bash
export KEYSTORE_PATH=/path/to/your-release-key.jks
export STORE_PASSWORD="your-keystore-password"
export KEY_PASSWORD="your-key-password"
# Псевдоним ключа в app/build.gradle.kts: "upload"
```

### Сборка APK под все платформы и универсального APK

```bash
# 1. ARM 64-bit (основная сборка для современных смартфонов и планшетов)
gradle :app:assembleRelease -PtargetAbi=arm64-v8a
cp app/build/outputs/apk/release/app-release.apk FPTN-Flow-v0.1-arm64-v8a.apk

# 2. ARM 32-bit (для старых Android-устройств)
gradle :app:assembleRelease -PtargetAbi=armeabi-v7a
cp app/build/outputs/apk/release/app-release.apk FPTN-Flow-v0.1-armeabi-v7a.apk

# 3. x86_64 (эмуляторы и Chromebook)
gradle :app:assembleRelease -PtargetAbi=x86_64
cp app/build/outputs/apk/release/app-release.apk FPTN-Flow-v0.1-x86_64.apk

# 4. Универсальный APK (все три архитектуры внутри одного файла)
gradle :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk FPTN-Flow-v0.1-universal.apk

# 5. Android App Bundle (.aab)
gradle :app:bundleRelease
```

Прикрепите собранные APK-файлы (и контрольные суммы `sha256sum FPTN-Flow-v0.1-*.apk > SHA256SUMS.txt`) к вашему релизу в GitHub Releases, либо отправьте тег (`git tag v0.1 && git push origin v0.1`), чтобы workflow `.github/workflows/release.yml` автоматически собрал и прикрепил все четыре APK.


