# Архитектура FPTN Flow (Android Fork)

## 1. Поток данных и слои

```text
feature/* (Jetpack Compose экраны)
      │
      ▼
feature/shared/FptnViewModel  ◄────  vpn/service/VpnStateHolder (StateFlow)
      │                                     ▲
      ▼                                     │
domain/* (чистые Kotlin-модели, парсеры, планировщики, классификаторы)
      ▲                                     │
      │                                     ▼
data/* (Room, DataStore, репозитории)   vpn/* (VpnService, TUN, Root, DoH, SNI)
                                            │
                                            ▼
                                org.fptn.vpn.services.* (JNI -> libfptn_native_lib.so)
```

- Зависимости направлены сверху вниз: `feature` не содержит сетевой и Root-логики, `domain` не зависит от Android UI, `core` не зависит от `feature`.
- Состояние интерфейса агрегируется в `FptnUiState` (`feature/shared/FptnUiState.kt`) и поступает в Compose через `StateFlow`.

---

## 2. Структура пакетов (`app/src/main/java/`)

```text
com/example/
├── FptnApplication.kt               — инициализация Room, DataStore, репозиториев и HapticEngine
├── MainActivity.kt                  — точка входа, Edge-to-Edge, жизненный цикл авто-пинга
├── navigation/
│   └── FptnNavGraph.kt              — граф навигации Compose и маршруты экранов
│
├── core/                            — общая инфраструктура без бизнес-логики фич
│   ├── clipboard/                   — безопасная работа с буфером обмена (ClipboardHelper)
│   ├── config/                      — константы URL, таймаутов и портов (FptnConstants)
│   ├── haptics/                     — адаптивный движок тактильного отклика (HapticEngine)
│   ├── logging/                     — кольцевой буфер диагностических логов (AppLogStore)
│   ├── performance/                 — профиль производительности устройства (DevicePerformanceProfile)
│   └── ui/
│       ├── components/              — переиспользуемые контролы (SmoothSettingsSlider, ClockAngleDialControl, SettingsComponents, AppIconCache)
│       ├── effects/                 — AGSL-волна (WaterWaveModifier) и трещины/осколки (CrackedGlassEffects)
│       ├── flags/                   — процедурный рендер флагов, 3D-матрица и кэш текстур (CountryFlagCanvas, FlagMatrixTransitionCanvas, PirateFlagRenderer)
│       ├── localization/            — мгновенная двуязычная локализация RU/EN без пересоздания Activity (AppLocalization)
│       └── theme/                   — палитры Material 3, типографика и тема (Color, Theme, Type)
│
├── domain/                          — чистые Kotlin-модели и логика (покрывается JVM-тестами)
│   ├── atmosphere/                  — расчёт циркадного освещения и сезонов (SolarAtmosphereEngine)
│   ├── dns/                         — парсер и валидатор DNS/DoH/DoT эндпоинтов (DnsEndpointParser)
│   ├── model/                       — модели токенов, роутинга, пресетов, оформления и VpnSettings
│   ├── root/                        — генератор и валидатор безопасных iptables/ip-rule команд (SafeRootRulePlanner)
│   ├── routing/                     — постоянная защита РФ (ProtectedRuApps: 507 правил, SmartRfRouter: 82 CIDR) и региональный роутинг
│   ├── token/                       — парсер токенов fptnb:/fptn:/fptn:// и JSON (FptnTokenParser)
│   └── vpn/                         — детектор зависаний и классификатор нативных ошибок (StallWatchdogEvaluator, VpnErrorClassifier)
│
├── data/                            — локальное хранилище, внешние источники и репозитории
│   ├── local/                       — Room (FptnDatabase, TokenDao, TokenEntities) и SettingsDataStore
│   ├── remote/                      — загрузка гербов и погоды (CoatOfArmsRepository, WeatherRepository)
│   └── repository/                  — FptnRepository (токены, пинги), BackupCodec, SponsorsFetcher
│
├── vpn/                             — системные сервисы и сетевой движок
│   ├── dns/                         — DoH-прокси и живой Split-DNS поверх туннеля (TunnelDohResolver)
│   ├── root/                        — RootShellManager, RootStealthTunOpener, RootTunFdHelper, AdaptiveRootBoostController
│   ├── service/                     — FptnVpnService, FptnTileService, VpnStateHolder
│   ├── sni/                         — автосканер рабочих SNI-доменов (SniAutoScanner)
│   └── tunnel/                      — клиент протокола FPTN и цикл пакетов TUN (FptnProtocolClient, TunPacketLoop)
│
└── feature/                         — экраны и локальные компоненты по фичам
    ├── appearance/                  — AppearanceSettingsScreen (темы, флаги, погода, отклик)
    ├── backup/                      — BackupScreen (экспорт/импорт SAF и буфер обмена)
    ├── bypass/                      — BypassSettingsScreen (методы обхода, SNI, Sliding Tunnel)
    ├── dns/                         — DnsSettingsScreen (Smart DNS, DoH/DoT, каталог серверов)
    ├── home/                        — HomeScreen, HomeCards, MinimalistShieldHero, PullToConnectTrack, ServerPickerSheet, SpeedTelemetryChart, WeatherAtmosphereOverlay, WeatherVolumetricRenderer
    ├── logs/                        — LogsScreen (просмотр, копирование и очистка журнала)
    ├── presets/                     — PresetsSettingsScreen, ManualOverridesScreen
    ├── root/                        — RootSettingsScreen (Root Stealth fptn0, диагностика)
    ├── routing/                     — TunnelingSettingsScreen (домены, регионы, приложения)
    ├── settings/                    — SettingsScreen, StabilitySettingsScreen, PermissionsScreen
    ├── share/                       — ShareScreen (QR-код и ссылки на официальный проект FPTN)
    ├── shared/                      — FptnViewModel, FptnUiState
    └── tokens/                      — TokenManagerScreen (мульти-токены, добавление/переименование)

org/fptn/vpn/services/               — JNI-мост к libfptn_native_lib.so (ПЕРЕМЕЩЕНИЕ ЗАПРЕЩЕНО)
├── snichecker/
│   └── SniChecker.java              — нативная проверка SNI (C++ JNI)
└── websocket/
    ├── FptnNativeLoader.java        — загрузка нативной библиотеки
    ├── NativeHttpsClientImpl.java   — нативный HTTPS-клиент (BoringSSL)
    ├── NativeResponse.java          — структура ответа JNI
    └── NativeWebSocketClientImpl.java — нативный WebSocket-клиент туннеля
```

---

## 3. Ключевые точки входа

| Сценарий | Точка входа |
|---|---|
| Запуск приложения и DI-граф | `com.example.FptnApplication` -> `com.example.MainActivity` -> `FptnNavGraph` |
| Старт/остановка VPN и Always-On | `com.example.vpn.service.FptnVpnService` (`onStartCommand`, `TunPacketLoop`, `FptnProtocolClient`) |
| Быстрая плитка Android (QS Tile) | `com.example.vpn.service.FptnTileService` |
| Парсинг и валидация токенов | `com.example.domain.token.FptnTokenParser` -> `com.example.data.repository.FptnRepository` |
| Root Stealth (fptn0, iptables, ip rule) | `com.example.domain.root.SafeRootRulePlanner` -> `com.example.vpn.root.RootShellManager` & `RootStealthTunOpener` |
| Резервное копирование и миграции | `com.example.data.repository.BackupCodec` -> `com.example.feature.backup.BackupScreen` |
| Визуальные эффекты и AGSL-шейдер | `com.example.core.ui.effects.WaterWaveModifier`, `com.example.core.ui.flags.CountryFlagCanvas` |
| Тактильный отклик | `com.example.core.haptics.HapticEngine` |

---

## 4. Критические инварианты стабильности

1. **JNI-контракт (`org.fptn.vpn.services.*`):** имена пакетов, классов и `native`-методов жёстко связаны с символами `Java_org_fptn_vpn_*` в `libfptn_native_lib.so`. Перенос этих классов без пересборки C++ сломает приложение.
2. **Жизненный цикл `FptnVpnService`:** `onStartCommand` всегда возвращает `START_NOT_STICKY` и вызывает `stopSelf(startId)` на пустой/неизвестный Intent без активного туннеля. Остановка освобождает дескриптор TUN и откатывает Root-правила.
3. **Неблокирующий `FptnTileService`:** все операции выполняются в `tileScope` (`SupervisorJob + Dispatchers.IO`), отменяемом в `onDestroy()`, без `runBlocking` на главном потоке.
4. **Безопасность Root Stealth (`SafeRootRulePlanner` + `RootShellManager`):**
   - команды проверяются через `SafeRootRulePlanner.validateSafety()`;
   - системные UID (`AID_ROOT = 0`, `AID_NETWORK_STACK = 1073`, `appUid`) всегда исключены из перенаправления;
   - используются изолированная таблица маршрутизации `table 198`, приоритет `pref 12600` и метка `fwmark 0x46000000/0xFF000000`;
   - операции Netfilter сериализуются через `ReentrantLock`, при этом `suspend`-вызовы вынесены за пределы блокировки.
5. **Отменяемые сокеты и корутины:** блокирующие сокеты закрываются в `Job.invokeOnCompletion`, а `CancellationException` всегда пробрасывается выше.
6. **Производительность UI:** `DevicePerformanceProfile` отключает тяжёлые AGSL-шейдеры на слабых устройствах; процедурные текстуры флагов кэшируются в `FlagTextureRenderer`; ползунки (`SmoothSettingsSlider`) пишут в DataStore однократно при отпускании.
