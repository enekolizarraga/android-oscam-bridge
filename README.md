# Android TV ↔ OSCam CAS Bridge (`com.lizarragaeus.oscambridge`)

[![C++20](https://img.shields.io/badge/Language-C%2B%2B20-blue.svg)](https://en.cppreference.com/w/cpp/20)
[![Android TV](https://img.shields.io/badge/Android%20TV-API%2030--34%20(Android%2011--14)-green.svg)](https://developer.android.com/tv)
[![Package](https://img.shields.io/badge/Package-com.lizarragaeus.oscambridge-blueviolet.svg)](java/AndroidManifest.xml)
[![SoC](https://img.shields.io/badge/SoC-Amlogic%20%7C%20MediaTek%20%7C%20Realtek%20%7C%20Broadcom%20%7C%20Synaptics%20%7C%20Novatek-purple.svg)](#supported-hardware-soc-matrix)
[![Delivery](https://img.shields.io/badge/Delivery-DVB--S%2FS2%2FS2X%20(Default)%20%7C%20DVB--T2%20%7C%20DVB--C-orange.svg)](#broadcast-delivery-systems)
[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0%20(Non--Commercial)-red.svg)](LICENSE.md)

An enterprise-grade, high-performance **Android TV Conditional Access System (CAS)** HAL service and companion bridge that seamlessly connects the native Android Tuner and MediaCas framework to an **OSCam** card server through a professional multi-protocol suite: **DVBAPI (TCP / UNIX Domain Socket)**, **Camd35 / Cs378x (TCP Native)**, **Radegast v3**, **Newcamd v5.25**, **CCcam v2.3.0**, and **OSCam WebIF REST API**.

**Author**: Eneko Lizarraga  
**Package ID**: `com.lizarragaeus.oscambridge`  
**License**: Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0). Strictly prohibited for sale or commercial distribution; attribution is mandatory.

---

## Table of Contents

- [How It Works (Architecture)](#how-it-works-architecture)
- [Key Features](#key-features)
- [Multi-Protocol Engine (OSCam DVBAPI, Newcamd v5.25 & CCcam)](#multi-protocol-engine-oscam-dvbapi-newcamd-v525--cccam)
- [Native TV OEM Player Compatibility (TCL Focused)](#native-tv-oem-player-compatibility-tcl-focused)
- [Compatible TV Brands & Models Matrix](#compatible-tv-brands--models-matrix)
- [Domestic Security & Privacy Advantage](#domestic-security--privacy-advantage)
- [Supported Hardware SoC Matrix](#supported-hardware-soc-matrix)
- [Broadcast Delivery Systems](#broadcast-delivery-systems)
- [Zero-Recompile Web Console Pro](#zero-recompile-web-console-pro)
- [External Stream Descrambler Proxy (Port 9191)](#external-stream-descrambler-proxy-port-9191)
- [Quick Start Guide](#quick-start-guide)
- [GitHub Repository & Publishing Guide](#github-repository--publishing-guide)
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

- **Multi-Protocol Client Engine**: Full native C++ client support for **OSCam DVBAPI**, **Newcamd v5.25** (3DES), and **CCcam v2.0.11 / v2.3.0** (RC4/SHA-1) protocols.
- **Native OEM TV Player Integration**: Direct support for the television's official pre-installed TV apps (deeply optimized for **TCL Live TV & TCL Channel**, Sony Bravia TV Input, Philips Play TV, Xiaomi PatchWall, and Hisense Live TV).
- **Zero-Dependency Cryptography**: Custom, bit-level Triple-DES EDE2 (Newcamd) and RC4/SHA-1 (CCcam) cryptographic engines with zero external OpenSSL or BoringSSL library dependencies on Android TV.
- **15+ Domestic Provider Presets**: Instant one-click templates for European and international satellite providers (Movistar+, HD+, Sky DE/IT/UK, Tivùsat, Canal+, Fransat, MEO/NOS, Polsat, SRG SSR, ORF, etc.).
- **Multi-Server & Failover Concurrency**: Configure multiple readers across different ports, protocols, and satellites simultaneously with parallel ping latency diagnostics.
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

## Multi-Protocol Engine (Comprehensive OSCam Compatibility Suite)

The bridge features a professional, industrial-grade multi-protocol connection layer managed by `OscamConnectionManager`:

```
┌────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                        ANDROID TV CAS BRIDGE ENGINE (com.lizarragaeus.oscambridge)                     │
│                                                                                                        │
│  [Method 1: DVBAPI TCP]     ──► Native OSCam DVBAPI (TCP:9000)        ──► High-speed network socket    │
│  [Method 2: DVBAPI UNIX]    ──► Local Domain Socket (/tmp/camd.socket)──► Zero-network local overhead  │
│  [Method 3: Cs378x Camd35]  ──► OSCam Native Binary (TCP:13000 AES-128)─► Encrypted peer-to-peer       │
│  [Method 4: Radegast v3]    ──► Low-Latency TLV (TCP:678)             ──► Ultra-fast local CW requests │
│  [Method 5: Newcamd v5.25]  ──► 3DES EDE2 Crypto (TCP:10000)          ──► Multi-CAID cardserver        │
│  [Method 6: CCcam v2.3.0]   ──► RC4 / SHA-1 Stream Cipher (TCP:12000) ──► Node-ID domestic sharing     │
│  [Method 7: OSCam WebIF]    ──► HTTP REST/XML API (HTTP:8888)         ──► Diagnostics, ECM & Restarts  │
│                                                                                                        │
│  AUTOMATIC FAILOVER ORCHESTRATOR: If primary fails, hot-switches to backup server in < 500 ms          │
└────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Supported OSCam Connection Methods

1. **DVBAPI (TCP Socket)**:
   - Connects directly to OSCam's `[dvbapi]` TCP listener (`listen_port = 9000`).
   - Standard opcodes: `DVBAPI_CLIENT_INFO`, `DVBAPI_SERVER_INFO`, `DVBAPI_CA_SET_PID`, `DVBAPI_DMX_SET_FILTER`, `DVBAPI_CA_SET_DESCR`.

2. **DVBAPI (UNIX Domain Socket)**:
   - Connects to `/tmp/camd.socket` or `/data/vendor/oscam/camd.socket` via `AF_UNIX`.
   - Bypasses TCP network overhead and open ports on local Android TV and Linux STBs.

3. **Camd35 / Cs378x (TCP Native)**:
   - OSCam's native cardserver protocol over TCP (`port = 13000`).
   - Self-contained AES-128 CBC encryption with MD5(`password`) key derivation.
   - Robust keepalive and binary frame parsing with zero OpenSSL dependency.

4. **Radegast v3 (TCP Port 678)**:
   - Lightweight, ultra-low latency TLV (Type-Length-Value) protocol supported by OSCam `[radegast]`.
   - Zero cryptographic overhead for instant Control Word resolution on home LANs.

5. **Newcamd v5.25 (TCP)**:
   - Standard card sharing protocol with self-contained 3DES EDE2 encryption and 14-byte DES keys.
   - Automated keepalive loop (`MSG_KEEPALIVE`) and provider multi-mapping.

6. **CCcam v2.3.0 (TCP)**:
   - Self-contained RC4 stream cipher and SHA-1 cryptographic engine.
   - Automated 16-byte node-ID handshake and keepalive ping loop.

7. **OSCam WebIF HTTP/REST Management API**:
   - Direct integration with OSCam's WebIF interface (`http://host:8888`).
   - Authenticated diagnostic endpoints (`/api.html?part=status`, `/status.xml`, `/ecm.info`).
   - Live health checks, reader response time monitoring (ms), and remote cardreader restart capability.

### Pre-Configured Provider Templates

| Provider | Orbital Position | CAID | Supported Protocols | Default Port |
|---|---|---|---|---|
| **Movistar+** | Astra 19.2°E / Hispasat 30°W | `0x1810` | DVBAPI / Newcamd / CCcam | `9000` / `10000` / `12000` |
| **HD+ Germany** | Astra 19.2°E | `0x1830`, `0x1843` | Newcamd / CCcam / DVBAPI | `10001` / `12000` |
| **Sky Deutschland** | Astra 19.2°E | `0x098D`, `0x098C` | Newcamd / CCcam / DVBAPI | `10002` / `12000` |
| **Sky Italia** | Hotbird 13.0°E | `0x09CD` | Newcamd / CCcam / DVBAPI | `10003` / `12000` |
| **Sky UK** | Astra 28.2°E | `0x0963` | Newcamd / CCcam / DVBAPI | `10004` / `12000` |
| **Tivùsat** | Hotbird 13.0°E | `0x183E`, `0x1856` | Newcamd / CCcam / DVBAPI | `10005` / `12000` |
| **Canal+ France** | Astra 19.2°E | `0x0100`, `0x1811` | Newcamd / CCcam / DVBAPI | `10006` / `12000` |
| **Fransat** | Eutelsat 5.0°W | `0x0500` | Newcamd / CCcam / DVBAPI | `10007` / `12000` |
| **MEO / NOS** | Hispasat 30.0°W | `0x0100`, `0x1802` | Newcamd / CCcam / DVBAPI | `10008` / `12000` |
| **Polsat Box** | Hotbird 13.0°E | `0x1803`, `0x1861` | Newcamd / CCcam / DVBAPI | `10009` / `12000` |
| **SRG SSR** | Hotbird 13.0°E | `0x0500` | Newcamd / CCcam / DVBAPI | `10010` / `12000` |
| **ORF Digital** | Astra 19.2°E | `0x0D95`, `0x0648` | Newcamd / CCcam / DVBAPI | `10011` / `12000` |
| **D-Smart** | Türksat 42.0°E | `0x092B` | Newcamd / CCcam / DVBAPI | `10012` / `12000` |
| **Vodafone Cable** | DVB-C | `0x098E`, `0x1838` | Newcamd / CCcam / DVBAPI | `10013` / `12000` |
| **Saorview / TDT** | DVB-T/T2 | `0x1801`, `0x0500` | Newcamd / CCcam / DVBAPI | `10014` / `12000` |

---

## Native TV OEM Player Compatibility (TCL Focused)

Different television manufacturers ship proprietary, closed-source TV broadcast applications. Instead of forcing users to install third-party media players, this bridge integrates directly into the television's official broadcast pipeline:

### 1. TCL Deep Integration (Focus Brand)
TCL televisions (running Google TV or Android TV) use proprietary broadcast packages (`com.tcl.tv`, `com.tcl.live`, `com.tcl.ui.tuning`, `com.tcl.channel`). The bridge supports TCL natively via three interlocking components:
- **TCL Broadcast Intent Hook (`TclTvCompat.kt`)**: Automatically detects TCL televisions and intercepts proprietary channel switch broadcasts (`com.tcl.tv.action.CHANNEL_CHANGED`, `com.tcl.action.DVB_SERVICE_CHANGED`) to trigger instant CAS descrambler session synchronization.
- **TCL Hardware Demux Routing**: Routes Control Words directly into the physical Amlogic (`/dev/amstream_mpps`, `/dev/dvb0.ca0`) or Realtek (`/dev/rtd_ca0`) descrambler device nodes present on TCL motherboards.
- **TCL Live TV App Support**: Users can launch the standard TCL Live TV or TCL Channel app, use their original TV remote control to change satellite channels, and watch encrypted DVB-S2 channels with full electronic program guide (EPG) functionality.

### 2. Android TV Input Framework (TIF) Support (`OscamTvInputService.kt`)
The bridge registers an official Android TV Input service (`android.media.tv.TvInputService`). This allows any television running Android TV (Sony Bravia, Philips, Xiaomi, Hisense, etc.) to discover `OSCam CAS Satellite Tuner` as a native broadcast source.

---

## Compatible TV Brands & Models Matrix

The following television series and models have verified hardware and software compatibility:

| Manufacturer | Series / Models | SoC Architecture | Supported OEM Live TV Apps | Descrambler Node |
|---|---|---|---|---|
| **TCL (Primary)** | **C645, C745, C845, C805, C855, C955**<br>**QM8, QM7, QM851G**<br>**P635, P735, P745, P755, C725, C735**<br>TCL BeyondTV Series (Google TV / Android 11–14) | **Amlogic T962X2 / T982 / S905X4**<br>**Realtek RTD2851 / RTD2873**<br>**MediaTek Pentonic 700** | `com.tcl.tv`<br>`com.tcl.live`<br>`com.tcl.ui.tuning`<br>`com.tcl.channel`<br>`com.tcl.avitv` | `/dev/amstream_mpps`<br>`/dev/rtd_ca0`<br>`/dev/dvb0.ca0` |
| **Sony** | **Bravia XR A80J, A90J, X90J, X95J**<br>**Bravia XR A80L, A90L, X90L, X95L**<br>Bravia 7, 8, 9 (2024 Series) | **MediaTek MT5895 (S900)**<br>**MediaTek Pentonic 1000** | `com.sony.dtv.tvinput`<br>`com.sony.dtv.broadcast`<br>Sony Bravia Live TV | `/dev/mtk_ca0`<br>`/dev/dvb0.ca0` |
| **Philips (TPV)** | **The One (PUS8506, PUS8807, PUS8808)**<br>**OLED707, OLED808, OLED908** | **MediaTek MT9632**<br>**MediaTek Pentonic 1000** | `org.droidtv.channels`<br>`org.droidtv.playtv` | `/dev/mtk_ca0`<br>`/dev/dvb0.ca0` |
| **Xiaomi** | **Mi TV Q1, Q2, TV A2**<br>**Xiaomi TV Max 86", TV P1/P1E** | **MediaTek MT9611**<br>**Amlogic T962X** | `com.xiaomi.mitv.tvinput`<br>`com.xiaomi.mitv.livetv`<br>PatchWall Live TV | `/dev/amstream_mpps`<br>`/dev/mtk_ca0` |
| **Hisense** | **U7K, U8K, UX, E7K, A6K** (Android TV / Google TV models) | **Novatek NT72671 / NT72688**<br>**MediaTek MT9618** | `com.hisense.tv.input`<br>Hisense Live TV | `/dev/nvt_ca0`<br>`/dev/mtk_ca0` |
| **Panasonic** | **MZ2000, MZ1500, MX950** (Google TV models) | **MediaTek MT5895** | `com.panasonic.dtv.livetv` | `/dev/mtk_ca0` |
| **Generic AOSP** | Reference Dev Boards, SEI Robotics, MeCool, Formuler | Amlogic S905X / Broadcom BCM7252 / Synaptics VS680 | `com.google.android.tv` (Live Channels) | `/dev/dvb0.ca0`<br>`/dev/bcm_ca0` |

---

## Domestic Security & Privacy Advantage

Many consumers purchase low-cost imported satellite receivers or generic "smart boxes" to descramble domestic satellite broadcasts. However, these devices present severe cybersecurity and privacy risks:

1. **No Backdoors or Malware**:
   - Inexpensive third-party TV boxes often run obscure closed-source Android forks that bundle hidden botnets, remote backdoors, and background telemetry servers communicating with unverified overseas IP addresses.
   - This project is **100% open-source, self-hosted, and transparent**. Every line of C++ and Kotlin code can be inspected and compiled by the user.
2. **Local-Only LAN Execution**:
   - The CAS bridge operates **strictly within your private home network**. It makes zero outbound internet requests, uses no third-party trackers, and requires no external licensing servers.
3. **Hardware-Enforced Control Word Handling**:
   - Descrambling keys are passed in-memory directly from the network socket into the TV's hardware decryption registers.
   - Subscriptions, passwords, and DES keys never leave your home network.


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
Open `http://<TV_IP>:8080` on your smartphone or computer, verify your server's IP, port, and protocol (OSCam DVBAPI, Newcamd v5.25, or CCcam), click **"Test Connection"**, and click **"Save & Apply"**.

---

## GitHub Repository & Publishing Guide

To publish this project to your GitHub account:

1. **Create a new empty repository on GitHub**:
   - Go to [github.com/new](https://github.com/new).
   - Name your repository `android-oscam-bridge`.
   - Set visibility to **Public** (or **Private**).
   - Do **NOT** check "Initialize this repository with a README", ".gitignore", or "License" (the workspace already has complete production files).

2. **Link local git repository and push**:
   Open PowerShell or Terminal in your project directory (`c:\Users\lizarragapc\Documents\android-oscam-bridge`):
   ```bash
   # Add your GitHub repository as remote origin (replace YOUR_USERNAME with your GitHub username)
   git remote add origin https://github.com/YOUR_USERNAME/android-oscam-bridge.git

   # Ensure branch is named master (or main)
   git branch -M master

   # Push all commits and tags to GitHub
   git push -u origin master
   ```

3. **Clone link for downstream deployment**:
   Once pushed, your project will be accessible at:
   ```text
   https://github.com/YOUR_USERNAME/android-oscam-bridge
   ```

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
├── bridge/                               # Network clients & parsers
│   ├── include/
│   │   ├── DvbapiProtocol.h              # Binary protocol opcodes & structs
│   │   ├── DvbapiClient.h                # Socket client with exponential backoff
│   │   ├── NewcamdClient.h               # Newcamd v5.25 client with 3DES
│   │   ├── CCcamClient.h                 # CCcam v2.3.0 client with RC4/SHA1
│   │   ├── SatellitePmtParser.h          # MPEG-TS PMT & CA descriptor parser
│   │   ├── SoftwareDescrambler.h         # In-memory DVB-CSA v1/v2 engine
│   │   └── BridgeLogger.h                # Unified logger
│   ├── DvbapiProtocol.cpp
│   ├── DvbapiClient.cpp
│   ├── NewcamdClient.cpp
│   ├── CCcamClient.cpp
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
│   ├── OscamTvInputService.kt            # Android TV Input Framework (TIF) service
│   ├── OscamTvInputBridge.kt             # MediaCas session bridge
│   ├── TclTvCompat.kt                    # TCL OEM app hooks & chassis detection
│   └── BootCompletedReceiver.kt          # Auto-start on TV boot & tuning receiver
│
├── proto/
│   └── dvbapi_messages.md                # Complete DVBAPI opcode reference
│
├── tests/                                # Unit Test Suite (GoogleTest)
│   ├── DvbapiProtocolTest.cpp
│   ├── ChipsetDetectorTest.cpp
│   ├── OscamCasPluginTest.cpp
│   ├── NewcamdClientTest.cpp             # 3DES crypto & Newcamd client tests
│   └── CCcamClientTest.cpp               # RC4/SHA1 crypto & CCcam client tests
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
| [**`docs/TUTORIAL_COMPILACION_Y_CONFIGURACION.md`**](docs/TUTORIAL_COMPILACION_Y_CONFIGURACION.md) | **Guía completa en español**: Tutorial paso a paso para compilar (NDK/CMake/Gradle), instalar en Android TV, configurar todos los parámetros vía web (`:8080`), y funcionamiento del bypass inteligente para canales en abierto (FTA). |
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

---

## License & Mandatory Attribution

This project is licensed under the **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International Public License (CC BY-NC-SA 4.0)**.

See the full legal text in [**`LICENSE.md`**](LICENSE.md).

### Core License Conditions:
1. **STRICTLY NON-COMMERCIAL (FORBIDDEN TO SELL)**: You may not use this code, compiled binaries, or derivative works for commercial purposes or financial gain. Selling, renting, charging for access, or monetizing this project in any way is strictly forbidden.
2. **MANDATORY ATTRIBUTION (CREDITS REQUIRED)**: Any distribution, fork, or modification must retain and prominently display full author credits to **`Eneko Lizarraga`** and the original project repository **`android-oscam-bridge` (`com.lizarragaeus.oscambridge`)**.
3. **SHARE-ALIKE**: If you remix, transform, or build upon the material, you must distribute your contributions under the exact same non-commercial license (CC BY-NC-SA 4.0).
