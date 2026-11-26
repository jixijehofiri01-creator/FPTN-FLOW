<div align="center">

# FPTN Flow

**Переработанный Android-клиент для открытого протокола [FPTN](https://fptn.org/) в стиле Material 3**

[English](README.md) &nbsp;·&nbsp; **Русский**

[![Version](https://img.shields.io/badge/version-v0.1-00667D?style=flat-square)](https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square)](LICENSE)

[**Скачать APK (Релизы)**](https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest) &nbsp;&nbsp;|&nbsp;&nbsp; [**Получить токен (@fptn_bot)**](https://t.me/fptn_bot) &nbsp;&nbsp;|&nbsp;&nbsp; [**Руководство пользователя**](docs/USER_GUIDE.ru.md)

</div>

---

> [!NOTE]
> **Личный проект.** FPTN Flow изначально создавался под себя и развивается одним человеком — это глубоко переработанный Android-клиент поверх открытого протокола и нативного ядра FPTN. Если приложение вам понравится и пригодится в повседневной работе, мне будет очень приятно — буду благодарен за звёздочку ⭐, отзывы и баг-репорты в [Issues](https://github.com/jixijehofiri01-creator/FPTN-FLOW/issues).

---

## Что умеет FPTN Flow

Приложение полностью совместимо с официальными токенами из **[@fptn_bot](https://t.me/fptn_bot)** и собственными серверами `fptn`, но переосмыслено с нуля под удобное ежедневное использование на телефонах и планшетах:

- **Современный интерфейс Material 3 (телефоны и планшеты)** — динамические цвета, живой график скорости, настраиваемые быстрые плитки, мгновенное переключение языка (**Русский / English**) и двухколоночная адаптация под планшеты.
- **Несколько токенов и защищённое хранение** — добавляйте официальные и личные токены под своими названиями; пароли и сырые токены шифруются на устройстве через Android Keystore (AES-256-GCM), а облачный бэкап секретов отключён.
- **Российские банки и сервисы работают без отключения VPN** — защищённые приложения и локальные сети автоматически идут напрямую, чтобы не дёргать VPN туда-сюда и не подставлять серверы под блокировки.
- **Стабильная работа на мобильной сети** — автопереключение профилей Wi-Fi ↔ 4G/5G, восстановление при «замирании» 0 Б/с, встроенный **Smart DNS** (17 серверов DoH / DoT / IPv4) и свои списки доменов.
- **Проверка обновлений и бэкапы** — встроенная проверка новых релизов на GitHub под процессор вашего устройства и удобный экспорт/импорт настроек.
- **Опциональный Root Stealth-режим** — скрытый туннель `fptn0` для устройств с root-правами (Magisk / KernelSU / APatch) с изолированными правилами `iptables`.

> [!TIP]
> **Про необычные визуальные эффекты:** объёмный 3D-туман и погода, флаги стран, водная волна при подключении, солнечное освещение и тактильная отдача (Haptics) — это своего рода забавная песочница, раскрывающая потенциал Material 3 и Compose Canvas. Если нужен строгий минимализм — **любой из этих эффектов (или все сразу) можно полностью отключить в «Настройки → Оформление»**. Приблизительная геолокация (`ACCESS_COARSE_LOCATION`) не запрашивается, пока вы сами не выберете авто-погоду по локации.

---

## Быстрый старт

1. Откройте **[GitHub Releases](https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest)** и скачайте APK (для почти всех современных Android-смартфонов и планшетов берите **`arm64-v8a`**).
2. Получите токен в официальном боте **[@fptn_bot](https://t.me/fptn_bot)** или используйте токен своего сервера FPTN.
3. Откройте **Настройки → Токены**, вставьте токен (`fptnb:`, `fptn:` или JSON), вернитесь на **Главную** и потяните переключатель.

<details>
<summary><strong>Какой именно APK скачать?</strong></summary>

| Файл | Для каких устройств |
| :--- | :--- |
| `FPTN-Flow-v0.1-arm64-v8a.apk` | **Рекомендуется** — современные 64-бит Android-смартфоны и планшеты |
| `FPTN-Flow-v0.1-armeabi-v7a.apk` | Старые 32-бит ARM-устройства |
| `FPTN-Flow-v0.1-x86_64.apk` | Планшеты на Intel/AMD, Chromebook и эмуляторы |
| `FPTN-Flow-v0.1-universal.apk` | Универсальный файл для любого Android 7.0+ (все 3 архитектуры) |
| `SHA256SUMS.txt` | Контрольные суммы SHA-256 для проверки целостности |

</details>

---

## Документация

| Документ | Для кого | О чём внутри |
| :--- | :--- | :--- |
| [Руководство пользователя (RU)](docs/USER_GUIDE.ru.md) · [EN](docs/USER_GUIDE.md) | Пользователям | Установка, советы по обходу ТСПУ, Smart DNS, `.ru`-сайты в браузере, FAQ |
| [Руководство разработчика (RU)](docs/DEVELOPMENT.ru.md) · [EN](docs/DEVELOPMENT.md) | Разработчикам | Устройство кода, JNI-контракт ядра, тесты и сборка под все ABI |
| [Архитектура](ARCHITECTURE.md) | Разработчикам | Модули, пакетный цикл TUN, многопоточность и безопасность |
| [Отличия от оригинала](DIFFERENCES.md) | Всем | Подробно о том, что взято из ядра FPTN и что переработано |
| [Участие в разработке](CONTRIBUTING.md) | Контрибьюторам | Правила баг-репортов и pull request |

---

<details>
<summary><strong>Благодарности оригинальному FPTN, проверка нативных библиотек и лицензия GPL-3.0</strong></summary>

FPTN Flow построен поверх открытого протокола **FPTN** и нативной библиотеки (`libfptn_native_lib.so`) от команды **[FPTN Project](https://github.com/fptn-project)**.

- Официальный сайт протокола: [fptn.org](https://fptn.org/)
- Официальный Android-клиент: [fptn-project/FptnClient-Android](https://github.com/fptn-project/FptnClient-Android)
- Исходный код C++-ядра: [fptn-project/fptn](https://github.com/fptn-project/fptn)
- Контрольные суммы `libfptn_native_lib.so`: [app/src/main/jniLibs/SHA256SUMS.txt](app/src/main/jniLibs/SHA256SUMS.txt)

Распространяется по лицензии **GNU General Public License v3.0 (GPL-3.0)** — см. [LICENSE](LICENSE).

</details>
