# Android TV ↔ OSCam CAS Bridge

High-performance Android TV Conditional Access System (CAS) HAL plugin and bridge service that connects the Android Tuner/MediaCas framework to an OSCam server via the `dvbapi` network protocol.

Designed for personal domestic use with a legitimate subscription card in your own OSCam receiver to view authorized DVB-S2, DVB-T2, and DVB-C channels on rooted Android TV devices.

> **Important**: This project is strictly intended for personal domestic research and interoperability with legitimate subscription cards in a private local network.

---

## Key Features

- **Multi-Brand SoC Abstraction**: Automated hardware detection and direct descrambler key injection for **Amlogic**, **MediaTek**, **Realtek**, **Broadcom**, **Synaptics**, and **Novatek**.
- **Full DVB Standards Support**:
  - **DVB-S / DVB-S2 / DVB-S2X (Satellite)** — *Enabled by default*
  - **DVB-T / DVB-T2 (Terrestrial)** — *Selectable in settings*
  - **DVB-C / DVB-C2 (Cable)** — *Selectable in settings*
  - **Hybrid / Multi-Tuner Mode**
- **Zero-Recompilation Configuration & Web Console Pro (`http://<TV_IP>:8080`)**:
  - **Live Telemetry Dashboard**: Dual SVG sparkline graphs for real-time Control Word latency (ms) and ECM throughput flow.
  - **Multi-Server Failover Matrix**: Add primary and fallback OSCam servers with parallel ping benchmarking (`/api/test_all`).
  - **Satellite Channel & Transponder Database**: Manage channels (Freq, Pol, SR, SID, PMT, CAID) with instant M3U and Enigma2 `lamedb` export.
  - **Integrated HTML5 Video Player**: Test descrambled streams in the browser without TV tuner hardware.
  - **ECM & CW Diagnostic Lab**: Decode raw ECM hex bytes, verify parity, table ID, and check section length.
  - **Wake-on-LAN (WoL) Activator**: Broadcast UDP magic packets to wake sleeping Linux receivers or Docker hosts.
  - **Hardware SoC Introspection**: Real-time display of SoC demux driver, RAM, and VINTF status.
  - **Live Log Terminal & One-Click Backup/Restore**: Color-coded log streaming with auto-scroll and instant JSON backup import.
- **External Stream & Recording Descrambler (Port 9191)**:
  - Built-in software DVB-CSA engine and local HTTP proxy (`http://127.0.0.1:9191/play?url=...` or `?file=...`) allowing **VLC**, **Kodi**, and **ExoPlayer** to play encrypted `.ts` recordings or SAT>IP streams without native TV tuner hardware.
- **Native TV App Compatibility**: Integrates seamlessly with official Android TV OEM apps (Google Live Channels, TCL Channel App, Sony Bravia, Philips Live TV) via standard `android.hardware.cas` AIDL.

---

## Repository Structure

```
android-oscam-bridge/
├── docs/
│   └── INSTALLATION_AND_CONFIGURATION.md # Full deployment & setup guide
│
├── bridge/                               # Dvbapi client & stream descramblers
│   ├── include/
│   │   ├── DvbapiProtocol.h              # Opcodes serialization & parsing
│   │   ├── DvbapiClient.h                # TCP socket with exponential backoff
│   │   ├── SatellitePmtParser.h          # DVB-S/S2/S2X PMT & CA descriptor parser
│   │   ├── SoftwareDescrambler.h         # Software DVB-CSA v1/v2 engine
│   │   └── BridgeLogger.h                # Unified logger (logcat / stderr)
│   ├── DvbapiProtocol.cpp
│   ├── DvbapiClient.cpp
│   ├── SatellitePmtParser.cpp
│   ├── SoftwareDescrambler.cpp
│   ├── BridgeLogger.cpp
│   └── oscam_bridge_test_main.cpp        # Standalone test binary
│
├── chipset/                              # SoC Hardware Abstraction Layer
│   ├── include/
│   │   ├── IChipsetAdapter.h             # Abstract SoC interface
│   │   ├── AmlogicAdapter.h              # S905, S905X, S928X (TCL, Xiaomi, MeCool)
│   │   ├── MediaTekAdapter.h             # MT5895, MT9632, Pentonic (Sony, Philips)
│   │   ├── RealtekAdapter.h              # RTD2851, RTD1319 (Chiq, Strong, Nokia)
│   │   ├── BroadcomAdapter.h             # BCM7xxx (Technicolor, Humax)
│   │   ├── SynapticsAdapter.h            # VS680, Berlin BG4CT (Canal+, Google TV)
│   │   ├── NovatekAdapter.h              # NT72xxx (Hisense, Sharp, Toshiba)
│   │   └── ChipsetDetector.h             # Automatic runtime SoC detector
│   ├── AmlogicAdapter.cpp
│   ├── MediaTekAdapter.cpp
│   ├── RealtekAdapter.cpp
│   ├── BroadcomAdapter.cpp
│   ├── SynapticsAdapter.cpp
│   ├── NovatekAdapter.cpp
│   └── ChipsetDetector.cpp
│
├── hal/                                  # Android CAS HAL (AIDL)
│   ├── aidl/vendor/oscam/cas/
│   │   ├── IOscamCasService.aidl         # Plugin factory & CAID queries
│   │   ├── IOscamCas.aidl                # Session lifecycle, ECM/EMM dispatch
│   │   └── IOscamCasListener.aidl        # CW resolved & error event callbacks
│   ├── native/
│   │   ├── include/
│   │   │   ├── OscamTypes.h              # Shared data structs & status codes
│   │   │   ├── OscamCasPlugin.h          # Hardware-agnostic CAS plugin
│   │   │   ├── OscamCasService.h         # Root HAL service implementation
│   │   │   └── ConfigWatcher.h           # inotify real-time configuration watcher
│   │   ├── OscamCasPlugin.cpp
│   │   ├── OscamCasService.cpp
│   │   └── ConfigWatcher.cpp
│   └── manifest/
│       ├── cas_config.xml                # System CAID registration
│       └── android.hardware.cas.xml      # VINTF HAL manifest entry
│
├── jni/                                  # C++ / JVM JNI bridge
│   ├── NativeBridge.h                    # Native singleton manager
│   └── com_oscam_cas_OscamCasPlugin.cpp  # JNI export methods & callbacks
│
├── java/com/oscam/cas/                   # Android TV Application Layer
│   ├── OscamNativeBridge.kt              # Typed JNI wrapper
│   ├── OscamConfigRepository.kt          # DataStore & JSON synchronization
│   ├── OscamCasBinderService.kt          # Persistent foreground service
│   ├── OscamCasSettingsActivity.kt       # D-Pad remote control settings UI
│   ├── OscamLocalConfigWebServer.kt      # Embedded Web UI (port 8080)
│   ├── StreamDescramblerServer.kt        # HTTP Stream proxy (port 9191)
│   ├── OscamTvInputBridge.kt             # TV Input Framework (TIF) connector
│   └── BootCompletedReceiver.kt          # Auto-start on TV boot
│
├── proto/dvbapi_messages.md              # Reference of dvbapi opcodes
└── build/CMakeLists.txt                  # CMake build configuration
```

---

## Getting Started

Please read the complete deployment guide in [**`docs/INSTALLATION_AND_CONFIGURATION.md`**](file:///c:/Users/lizarragapc/Documents/android-oscam-bridge/docs/INSTALLATION_AND_CONFIGURATION.md) for step-by-step instructions on:
1. Configuring `oscam.conf` and `oscam.user`.
2. Installing via Magisk module or direct ADB root injection.
3. Using the Web UI or Android TV Settings.
4. Streaming external encrypted recordings with VLC or Kodi.
