<div align="center">

<img src="https://raw.githubusercontent.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/main/.github/assets/icon.png" width="128" alt="NoLockQS icon">

# The NoLockQS Project 🔐🚫

**A locked phone should stay locked.**<br>
NoLockQS hides Quick Settings and the power menu on the lock screen, and tells you when someone tries. 🕵️

[![Latest release](https://img.shields.io/badge/release-v2.9-DD0E1C)](https://github.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/releases/latest)
[![Android 15+](https://img.shields.io/badge/Android-15%2B-3E5C7B?logo=android&logoColor=white)](#-requirements)
[![Languages](https://img.shields.io/badge/languages-EN%20%C2%B7%20AR%20%C2%B7%20ZH-3E5C7B)](#-what-you-get)
[![License](https://img.shields.io/badge/license-Proprietary-3E5C7B)](https://github.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/blob/main/LICENSE)

<img src="https://raw.githubusercontent.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/main/.github/assets/screenshots/home-dark.png" width="200" alt="Home screen, dark">&nbsp;
<img src="https://raw.githubusercontent.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/main/.github/assets/screenshots/history-dark.png" width="200" alt="Blocked attempts, each with its own icon">&nbsp;
<img src="https://raw.githubusercontent.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/main/.github/assets/screenshots/home-light.png" width="200" alt="Home screen, light">&nbsp;
<img src="https://raw.githubusercontent.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/main/.github/assets/screenshots/history-ar.png" width="200" alt="Arabic, right to left">

</div>

## 🤔 Why

Thieves grab a locked phone and go straight for the pull-down: airplane mode on, data off, power off, gone. NoLockQS shuts those doors while it's locked, and leaves everything else alone.

## ✨ What you get

| | |
|---|---|
| 🧱 **No Quick Settings when locked** | No tiles, no toggles. Notifications still open and expand. |
| ⏻ **No power menu when locked** | The phone can't be switched off or restarted from the lock screen. |
| ⚡ **Instant unlock** | Everything's back the moment you unlock, and locks again just as fast. |
| 🔔 **Alerts and history** | See every blocked try with its time. A silent alert waits until *you* unlock, so the thief is never warned. |
| 🕵️ **Failed unlocks too** | Wrong PIN 🔢, pattern, password 🔑, fingerprint 👆 or face 🙂, each with its own colored icon. Only the time and kind are saved, never what was typed. |
| 🫧 **Glass design** | Frosted see-through cards over soft glowing colors. ✨ |
| 🌓 **Light, dark or same as device** | One tap, applies right away. |
| 🌍 **English · العربية · 简体中文** | Full right-to-left Arabic, plus Simplified Chinese. |
| 🩺 **One-tap diagnostics** | A ready-to-share report when something's off. |
| 🔒 **Private** | No internet permission, no tracking. Everything stays on your phone. |

## 📋 Requirements

- 📱 A rooted phone on Android 15 or newer (tested on Android 15-17): a Pixel, or a ROM with near-stock (AOSP) System UI
- 🚀 Vector 2.2 or newer

> [!IMPORTANT]
> **Android 17** needs Magisk 31.0+ and a Vector canary (3112 or newer). Magisk 30.7 and Vector 2.2 stable don't load modules on Android 17.

### 🧩 Other ROMs

NoLockQS hooks stock (AOSP) System UI, so the closer a ROM is to stock, the better.

| Status | ROMs | Notes |
|:---:|---|---|
| ✅<br>**Tested** | ![Pixel](https://img.shields.io/badge/Pixel-4285F4?style=for-the-badge&logo=google&logoColor=white) | A Pixel on Android 17, and Android 15, 16 and 17 emulators |
| 🟡<br>**Should work** | ![LineageOS](https://img.shields.io/badge/LineageOS-167C80?style=for-the-badge&logo=lineageos&logoColor=white) ![crDroid](https://img.shields.io/badge/crDroid-3DDC84?style=for-the-badge&logo=android&logoColor=white) ![Evolution X](https://img.shields.io/badge/Evolution_X-8E24AA?style=for-the-badge&logo=android&logoColor=white) ![Other AOSP ROMs](https://img.shields.io/badge/Other_AOSP_ROMs-5F6368?style=for-the-badge&logo=android&logoColor=white) | Same System UI code as stock Android, not yet tested on a real device |
| ❌<br>**Not supported** | ![Samsung One UI](https://img.shields.io/badge/Samsung_One_UI-1428A0?style=for-the-badge&logo=samsung&logoColor=white) ![Xiaomi HyperOS / MIUI](https://img.shields.io/badge/Xiaomi_HyperOS_%2F_MIUI-FF6900?style=for-the-badge&logo=xiaomi&logoColor=white) ![OPPO ColorOS](https://img.shields.io/badge/OPPO_ColorOS-2D683D?style=for-the-badge&logo=oppo&logoColor=white) ![OnePlus OxygenOS](https://img.shields.io/badge/OnePlus_OxygenOS-F5010C?style=for-the-badge&logo=oneplus&logoColor=white) ![vivo OriginOS](https://img.shields.io/badge/vivo_OriginOS-415FFF?style=for-the-badge&logo=vivo&logoColor=white) | They replace the Quick Settings panel with their own, which NoLockQS likely doesn't reach |

Not on a Pixel? Lock the phone and try the pull-down. If it opens, send the **Diagnostics report**.

## 🚀 Installation

1. Download the APK from the [latest release](https://github.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/releases/latest) and install it.
2. Open Vector, enable **NoLockQS**, and tick the scope it asks for: **System UI** and **System Framework**.
3. Reboot. The app shows **Module active** and the protection is on. If it says **Scope incomplete**, tap the card to add what's missing.
4. Allow notifications when the app asks, so the alerts can reach you.

> [!TIP]
> Something not working? Open the app, tap **Troubleshooting → Diagnostics report** and share it with your report.

## 📜 License

© 2026 Faisal AlDossary. All rights reserved.

NoLockQS is proprietary software: you may install and use it on your own devices, but you may not redistribute, modify or repackage it. See the [license](https://github.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/blob/main/LICENSE) for the full terms.

<div align="center">

### ❤️ You can show your love on [Patreon](https://www.patreon.com/c/Dossary) or [Ko-fi](https://ko-fi.com/Q8G429G7HX)!

[![Support me on Patreon](https://img.shields.io/badge/Support_me_on_Patreon-FF424D?style=for-the-badge&logo=patreon&logoColor=white)](https://www.patreon.com/c/Dossary)
[![Support me on Ko-fi](https://img.shields.io/badge/Support_me_on_Ko--fi-72A4F2?style=for-the-badge&logo=kofi&logoColor=white)](https://ko-fi.com/Q8G429G7HX)

<a href="https://github.com/2B-4G10"><img src="https://raw.githubusercontent.com/Xposed-Modules-Repo/io.github.i2B4G10.NoLockQS/main/app/src/main/res/drawable-nodpi/developer_logo.png" width="64" alt="2B-4G10"></a>

**Made in Saudi 🇸🇦**

</div>
