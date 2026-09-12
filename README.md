# Android TV ↔ OSCam CAS Bridge

[![C++20](https://img.shields.io/badge/Language-C%2B%2B20-blue.svg)](https://en.cppreference.com/w/cpp/20)
[![Android TV](https://img.shields.io/badge/Android%20TV-API%2030--34%20(Android%2011--14)-green.svg)](https://developer.android.com/tv)
[![SoC](https://img.shields.io/badge/SoC-Amlogic%20%7C%20MediaTek%20%7C%20Realtek%20%7C%20Broadcom%20%7C%20Synaptics%20%7C%20Novatek-purple.svg)](#supported-hardware-soc-matrix)
[![Delivery](https://img.shields.io/badge/Delivery-DVB--S%2FS2%2FS2X%20(Default)%20%7C%20DVB--T2%20%7C%20DVB--C-orange.svg)](#broadcast-delivery-systems)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

An enterprise-grade, high-performance **Android TV Conditional Access System (CAS)** HAL service and companion bridge that seamlessly connects the native Android Tuner and MediaCas framework to an **OSCam** card server via the network `dvbapi` protocol.

Designed for personal domestic research, home lab environments, and legal interoperability, this bridge allows owners of legitimate smartcard subscriptions housed in a domestic OSCam receiver to descramble and watch authorized satellite (**DVB-S / DVB-S2 / DVB-S2X**), terrestrial (**DVB-T / DVB-T2**), and cable (**DVB-C**) broadcast channels directly on rooted Android TV devices using the television's official native apps.

---

## Table of Contents

- [How It Works (Architecture)](#how-it-works-architecture)
- [Key Features](#key-features)
- [Supported Hardware SoC Matrix](#supported-hardware-soc-matrix)
- [Broadcast Delivery Systems](#broadcast-delivery-systems)
- [Zero-Recompile Web Console Pro](#zero-recompile-web-console-pro)
- [External Stream Descrambler Proxy (Port 9191)](#external-stream-descrambler-proxy-port-9191)
- [Quick Start Guide](#quick-start-guide)
- [Repository Structure](#repository-structure)
- [Documentation Index](#documentation-index)
- [Legal & Educational Disclaimer](#legal--educational-disclaimer)

---

## How It Works (Architecture)

Standard Android TV implementations use the **Android TV Input Framework (TIF)**, **Tuner HAL**, and **MediaCas** to handle scrambled digital television broadcasts. When tuning an encrypted channel, the TV demux encounters CA (Conditional Access) descriptors inside the MPEG-TS Program Map Table (PMT) and queries the system CAS plugin.

This project implements a vendor AIDL CAS plugin (`vendor.oscam.cas.IOscamCasService`) and bridges it to OSCam:

```
                                  ANDROID TV
 ┌────────────────────────────────────────────────────────────────────────────┐
 │                                                                            │
 │   [Native TV OEM Apps]                  [External Media Players]           │
 │   (Google Live Channels, TCL, Sony)     (VLC, Kodi, Nova, ExoPlayer)       │
 │            │                                         │                     │
 │            ▼                                         ▼                     │
 │     [TIF / MediaCas]                     [Stream Descrambler (9191)]       │
 │            │                                         │                     │
 │            ▼                                         ▼                     │
 │   [OscamCasService / Plugin]            [Software DVB-CSA v1/v2 Engine]    │
 │            │                                         ▲                     │
 │            ▼                                         │ (Fallback / Proxy)  │
 │   [Chipset Abstraction Layer]                        │                     │
 │   (Amlogic, MTK, Realtek, BCM, Syna, NVT)            │                     │
 │            │                                         │                     │
 │            ├──► Injects Control Words (CW)           │                     │
 │            │    into Hardware SoC Demux              │                     │
 │            │                                         │                     │
 │            ▼                                         │                     │
 │     [DvbapiClient] ◄─────────────────────────────────┘                     │
 │            │                                                               │
 └────────────┼───────────────────────────────────────────────────────────────┘
              │ TCP Socket (dvbapi network protocol)
              ▼
   ┌───────────────────────┐
   │ OSCam Server (Local)  │
   │  - [dvbapi] module    │
   │  - Physical Smartcard │
   │  - Port 9000          │
   └───────────────────────┘
```

### Dual Descrambling Engine

1. **Hardware Path (Native Broadcast Tuners)**:
   - Evaluates PMT sections, extracts ECM (Entitlement Control Message) PIDs matching configured CAIDs.
   - Forwards ECM packets over TCP to OSCam via `DVBAPI_ECM_INFO`.
   - Receives resolved Control Words (CW even/odd keys).
   - Injects CWs straight into the hardware SoC descrambler registers/device nodes (`/dev/amstream_mpps`, `/dev/mtk_ca0`, `/dev/rtd_ca0`, etc.).
   - Zero CPU overhead: Video/Audio decoding is performed at full 4K UHD 60fps by the TV's hardware VPU.

2. **Software Path (External Streams & Recordings)**:
   - For content **not tuned via the physical TV RF tuner** (e.g. encrypted `.ts` recordings on USB/NAS, SAT>IP IP-tuners, or network MPEG-TS feeds).
   - Built-in HTTP proxy at `http://127.0.0.1:9191/play?url=...` or `?file=...`.
   - Uses an in-memory optimized **DVB-CSA v1/v2 bit-slice descrambler engine** to serve clear video in real-time to any Android player.

---

## Key Features

- **Multi-Brand Hardware Abstraction**: Automated SoC architecture detection at boot with specialized descrambler drivers for **Amlogic**, **MediaTek**, **Realtek**, **Broadcom**, **Synaptics**, and **Novatek**.
- **Satellite DVB-S2 Default**: Built specifically with European/North American satellite broadcasts in mind, pre-configured with active CAIDs for Astra 19.2°E, Hispasat 30°W, Hotbird 13°E, and Eutelsat 5W.
- **Zero-Recompilation Workflow**: Modify server IP, port, credentials, CAIDs, and delivery standards on the fly:
  - **Embedded Web Console Pro (`http://<TV_IP>:8080`)**: Clean, reactive web interface accessible from your PC or phone.
  - **Leanback Remote Settings UI**: Full Android TV settings menu navigable via TV remote D-Pad.
  - **Inotify Live Watcher**: The native C++ daemon observes `/data/vendor/oscam/config.json` and hot-reloads within 500ms without restarting.
- **Web Console Pro**:
  - Dual real-time SVG sparklines for Control Word latency (ms) and ECM packet throughput.
  - Satellite Channel & Transponder Database Manager with direct M3U playlist and Enigma2 `lamedb` export.
  - Integrated HTML5 video player for stream validation.
  - Interactive ECM packet inspector and Control Word parity diagnostic lab.
  - Wake-on-LAN (WoL) multi-device magic packet transmitter.
  - Complete JSON configuration backup and one-click restore.
- **High-Speed In-Memory CW Cache**: Eliminates redundant network round-trips for channels sharing ECMs or duplicate keys.
- **Failover & Multi-Server Matrix**: Define primary and backup OSCam servers with parallel ping benchmarking (`/api/test_all`).
- **SELinux & VINTF Ready**: Fully compatible with Android's `@VintfStability` requirements and includes production `.te` and `.cil` SELinux policies.

---

## Supported Hardware SoC Matrix

The Chipset Abstraction Layer (`IChipsetAdapter`) dynamically queries Android system properties (`ro.board.platform`, `ro.hardware`, `ro.product.board`) to select the optimal hardware descrambler driver:

| Vendor | Chipset Families | Example Brands & Devices | Descrambler Interface |
|---|---|---|---|
| **Amlogic** | S905D, S905X, S905X4, S928X, T962 | TCL, Xiaomi Mi TV, MeCool, Formuler, Homatics | `/dev/amstream_mpps`, `/dev/dvb0.ca0` |
| **MediaTek** | MT5895, MT9632, Pentonic 700/1000 | Sony Bravia, Philips, TCL, Panasonic, Hisense | `/dev/mtk_ca0`, `/dev/dvb0.ca0` |
| **Realtek** | RTD2851, RTD1319, RTD2873 | Chiq, Strong, Thomson, Nokia, Metz Blue | `/dev/rtd_ca0`, `/dev/dvb0.ca0` |
| **Broadcom** | BCM7252, BCM72604, BCM72180 | Technicolor, Humax, Bouygues Telecom, Swisscom | `/dev/bcm_ca0`, `/dev/dvb0.ca0` |
| **Synaptics** | VS680, Berlin BG4CT | Google TV Reference Devices, Canal+, Bbox | `/dev/galois_ca0` |
| **Novatek** | NT72671, NT72688, NT72690 | Hisense, Toshiba, Sharp, Skyworth | `/dev/nvt_ca0` |

---

## Broadcast Delivery Systems

The delivery system can be switched in real time without recompilation:

| Delivery Standard | Mode Flag | Active By Default? | Default Pre-Loaded CAIDs |
|---|---|---|---|
| **Satellite (DVB-S / S2 / S2X)** | `DVBS` | **YES (Default)** | `0x1810` (Movistar+), `0x1830`/`0x1843` (HD+), `0x0100` (Seca/Canal+), `0x0500` (Viaccess/Fransat), `0x0B00` (Conax), `0x0604` (Irdeto), `0x09CD`/`0x098C` (Sky VideoGuard) |
| **Terrestrial (DVB-T / T2)** | `DVBT` | Optional | `0x1801` (Nagra Terrestrial), `0x0604` (Irdeto), `0x0B00` (Conax), `0x0500` (Viaccess) |
| **Digital Cable (DVB-C / C2)** | `DVBC` | Optional | `0x1801` (Nagravision Cable), `0x0604` (Irdeto), `0x0B00` (Conax), `0x098C` (VideoGuard) |
| **Hybrid / Multi-Tuner** | `HYBRID` | Optional | All Satellite, Terrestrial, and Cable CAIDs combined |

---

## Zero-Recompile Web Console Pro

When the TV boots, an embedded HTTP management console starts on port `8080` (`http://<TV_IP>:8080`).

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Ω  Android TV OSCam CAS Bridge Master Console           [⚡ Ping All] [CONNECTED] │
├─────────────────────────────────────────────────────────────────────────────┤
│  RESOLVED CWs: 4,821    PROCESSED ECMs: 4,830    LATENCY: 52 ms    SoC: Amlogic  │
├─────────────────────────────────────────────────────────────────────────────┤
│  [📊 Dashboard] [📡 Servers] [🛰️ Channels] [⚙️ Tuner] [📺 Player] [🔬 ECM Lab]   │
│                                                                             │
│  CW Latency (ms): ▄▆█▄▃  52 ms             ECM Processing Rate: ▅▆▇▆  Active │
│                                                                             │
│  Satellite Channel Database:                                                │
│  • Movistar+ Estrenos HD | Astra 19.2°E | 10729 V 22000 | CAID: 0x1810     │
│  • HD+ RTL UHD           | Astra 19.2°E | 11214 H 22000 | CAID: 0x1830     │
│  • Canal+ Sport HD       | Astra 19.2°E | 12012 V 29700 | CAID: 0x0100     │
│                                                                             │
│  [⬇ Export M3U Playlist]   [⬇ Export Enigma2 lamedb]   [💾 Backup Config]    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Highlights:
- **Zero Internet Requirement**: 100% self-contained, no external CDN or web fonts; works strictly on your isolated home LAN.
- **Dynamic Endpoints**:
  - `GET /api/status`: Live telemetry (CW counts, ECM flow, reconnects, latency).
  - `POST /api/test_all`: Concurrently pings all configured OSCam servers and reports response times.
  - `GET /playlist.m3u`: Dynamically generates an M3U playlist with stream descrambler links for VLC and Kodi.
  - `GET /lamedb`: Generates standard Enigma2/Neutrino channel database files.
  - `POST /api/ecm_decode`: Analyzes raw ECM hex packets (Table ID, Parity, Section Length).
  - `POST /api/wol`: Transmits UDP Wake-on-LAN magic packets to sleeping servers.

---

## External Stream Descrambler Proxy (Port 9191)

If you have encrypted `.ts` recordings or SAT>IP streams not handled by the physical tuner, use the built-in HTTP proxy:

- **SAT>IP / Network Stream**:
  ```
  http://<TV_IP>:9191/play?url=http://192.168.1.50/stream?freq=11000&pol=v&sr=22000
  ```
- **Local Encrypted `.ts` Recording**:
  ```
  http://127.0.0.1:9191/play?file=/sdcard/Movies/recording.ts
  ```

Compatible with **VLC for Android**, **Kodi**, **Nova Video Player**, and **ExoPlayer**.

---

## Quick Start Guide

### 1. Configure OSCam Server (`oscam.conf`)
Ensure your domestic OSCam server listens for network `dvbapi`:

```ini
[dvbapi]
enabled                       = 1
au                            = 1
pmt_mode                      = 0
request_mode                  = 0
listen_port                   = 9000
user                          = android_tv
boxtype                       = pc
```

### 2. Install on Android TV
Detailed guides for both **Magisk Module** and **Direct ADB Root** installations are available in [`docs/INSTALLATION_AND_CONFIGURATION.md`](docs/INSTALLATION_AND_CONFIGURATION.md).

Quick ADB injection:
```bash
adb connect <TV_IP>:5555
adb root && adb remount
adb push build_android/vendor.oscam.cas-service /vendor/bin/hw/
adb push hal/manifest/cas_config.xml /vendor/etc/
adb push hal/manifest/android.hardware.cas.xml /vendor/etc/vintf/manifest/
adb install -r OscamCasSettings.apk
adb reboot
```

### 3. Configure via Web Browser
Open `http://<TV_IP>:8080` on your smartphone or computer, verify your server's IP and port, click **"Test Connection"**, and click **"Save & Apply"**.

---

## Repository Structure

```
android-oscam-bridge/
├── .gitignore                            # Enterprise-grade ignore rules
├── CMakeLists.txt                        # Top-level multi-platform build file
├── README.md                             # Primary documentation & overview
│
├── docs/                                 # Detailed documentation
│   ├── INSTALLATION_AND_CONFIGURATION.md # Step-by-step installation & deployment
│   └── ARCHITECTURE.md                   # Deep-dive internals & protocol specification
│
├── bridge/                               # Dvbapi network client & parsers
│   ├── include/
│   │   ├── DvbapiProtocol.h              # Binary protocol opcodes & structs
│   │   ├── DvbapiClient.h                # Socket client with exponential backoff
│   │   ├── SatellitePmtParser.h          # MPEG-TS PMT & CA descriptor parser
│   │   ├── SoftwareDescrambler.h         # In-memory DVB-CSA v1/v2 engine
│   │   └── BridgeLogger.h                # Unified logger
│   ├── DvbapiProtocol.cpp
│   ├── DvbapiClient.cpp
│   ├── SatellitePmtParser.cpp
│   ├── SoftwareDescrambler.cpp
│   ├── BridgeLogger.cpp
│   └── oscam_bridge_test_main.cpp        # CLI diagnostic executable
│
├── chipset/                              # Multi-Vendor Hardware Abstraction Layer
│   ├── include/
│   │   ├── IChipsetAdapter.h             # Abstract SoC interface
│   │   ├── ChipsetDetector.h             # Automatic runtime SoC detector
│   │   ├── AmlogicAdapter.h              # Amlogic S905/S928X driver
│   │   ├── MediaTekAdapter.h             # MediaTek MT5895/Pentonic driver
│   │   ├── RealtekAdapter.h              # Realtek RTD2851/RTD2873 driver
│   │   ├── BroadcomAdapter.h             # Broadcom BCM7xxx driver
│   │   ├── SynapticsAdapter.h            # Synaptics VS680 driver
│   │   └── NovatekAdapter.h              # Novatek NT72xxx driver
│   ├── ChipsetDetector.cpp
│   ├── AmlogicAdapter.cpp
│   ├── MediaTekAdapter.cpp
│   ├── RealtekAdapter.cpp
│   ├── BroadcomAdapter.cpp
│   ├── SynapticsAdapter.cpp
│   └── NovatekAdapter.cpp
│
├── hal/                                  # Android CAS HAL (AIDL)
│   ├── aidl/vendor/oscam/cas/
│   │   ├── IOscamCasService.aidl         # Factory & CAID query interface
│   │   ├── IOscamCas.aidl                # Session lifecycle & ECM/EMM dispatch
│   │   └── IOscamCasListener.aidl        # Event listener callback
│   ├── native/
│   │   ├── include/
│   │   │   ├── OscamTypes.h              # Status codes & types
│   │   │   ├── OscamCasPlugin.h          # Hardware-agnostic CAS plugin
│   │   │   ├── OscamCasService.h         # Root HAL service daemon
│   │   │   └── ConfigWatcher.h           # inotify dynamic config watcher
│   │   ├── OscamCasPlugin.cpp
│   │   ├── OscamCasService.cpp
│   │   ├── ConfigWatcher.cpp
│   │   └── main.cpp                      # Daemon entrypoint
│   ├── manifest/
│   │   ├── cas_config.xml                # Registered CAID table
│   │   └── android.hardware.cas.xml      # VINTF fragment
│   └── sepolicy/
│       ├── oscamcas.te                   # SELinux type enforcement rules
│       └── file_contexts                 # File security contexts
│
├── jni/                                  # JNI Bridge for Kotlin App
│   ├── NativeBridge.h
│   └── com_oscam_cas_OscamCasPlugin.cpp
│
├── java/com/oscam/cas/                   # Android TV Companion Application
│   ├── OscamCasBinderService.kt          # Persistent foreground service
│   ├── OscamLocalConfigWebServer.kt      # Embedded Web Console Pro (port 8080)
│   ├── StreamDescramblerServer.kt        # HTTP stream proxy (port 9191)
│   ├── OscamConfigRepository.kt          # DataStore & JSON sync repository
│   ├── OscamCasSettingsActivity.kt       # Leanback D-Pad settings activity
│   ├── OscamNativeBridge.kt              # Strongly-typed JNI wrapper
│   ├── OscamTvInputBridge.kt             # Android TIF connector
│   └── BootCompletedReceiver.kt          # Auto-start on TV boot
│
├── proto/
│   └── dvbapi_messages.md                # Complete DVBAPI opcode reference
│
├── tests/                                # Unit Test Suite (GoogleTest)
│   ├── DvbapiProtocolTest.cpp
│   ├── ChipsetDetectorTest.cpp
│   └── OscamCasPluginTest.cpp
│
└── build/                                # Build Configurations
    ├── CMakeLists.txt
    ├── Android.bp                        # AOSP Soong build recipe
    ├── device.mk                         # Vendor ROM inclusion makefile
    └── vendor.oscam.cas-service.rc       # Init daemon startup script
```

---

## Documentation Index

| Document | Purpose |
|---|---|
| [**`docs/INSTALLATION_AND_CONFIGURATION.md`**](docs/INSTALLATION_AND_CONFIGURATION.md) | Comprehensive installation guide (Magisk, ADB, AOSP), compilation steps (4 methods), Web Console Pro guide, OSCam setup, and troubleshooting FAQ. |
| [**`docs/ARCHITECTURE.md`**](docs/ARCHITECTURE.md) | In-depth engineering design, MediaCas framework interaction, DVBAPI protocol internals, hardware key injection mechanics, and software DVB-CSA design. |
| [**`proto/dvbapi_messages.md`**](proto/dvbapi_messages.md) | Detailed binary packet structures, opcodes, and sequence diagrams for OSCam network DVBAPI communication. |

---

## Legal & Educational Disclaimer

This software is developed and distributed strictly for **personal research, academic education, and domestic interoperability testing** on devices owned by the user.

- It is designed exclusively to enable legitimate paying subscribers to access their own paid television services on Android TV hardware within their own private household network using their own legitimate subscription smartcard.
- This project does **not** include smartcard keys, commercial decryption keys, or subscription access codes.
- The developers do not condone, promote, or support commercial piracy, unauthorized redistribution of broadcast streams, or cardsharing outside of legal domestic use.
- Users are solely responsible for ensuring compliance with local laws and their broadcast service agreement terms.
