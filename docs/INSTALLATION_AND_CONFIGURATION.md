# Android TV ↔ OSCam CAS Bridge: Installation & Configuration Guide

Professional reference documentation for deploying, configuring, and operating the Android TV Conditional Access System (CAS) bridge for DVB-S2, DVB-T2, and external network streams.

---

## 1. Architectural Overview

The **Android OSCam CAS Bridge** translates low-level Android Tuner HAL and MediaCas requests into standard OSCam `dvbapi` network protocol messages over local TCP sockets.

```
                    ┌────────────────────────────────────────────────────────┐
                    │                      Android TV                        │
                    │                                                        │
                    │  [Native TV App]         [External Media Player]       │
                    │  (Live Channels, TCL)     (VLC, Kodi, ExoPlayer)       │
                    │         │                             │                │
                    │         ▼                             ▼                │
                    │   [MediaCas / TIF]       [StreamDescrambler (9191)]    │
                    │         │                             │                │
                    │         ▼                             ▼                │
                    │  [OscamCasPlugin] ◄──────► [Software DVB-CSA Engine]   │
                    │         │                                              │
                    │         ▼                                              │
                    │  [IChipsetAdapter] ──► Hardware Descrambler (SoC)       │
                    │         │                                              │
                    │         ▼                                              │
                    │  [DvbapiClient] ────┐                                  │
                    └─────────────────────┼──────────────────────────────────┘
                                          │ TCP (dvbapi protocol)
                                          ▼
                             ┌─────────────────────────┐
                             │  OSCam Server (Local)   │
                             │   - oscam.conf [dvbapi] │
                             │   - Physical Smartcard  │
                             └─────────────────────────┘
```

### Dual-Path Descrambling Architecture

1. **Hardware Path (Native Broadcast Tuners)**:
   - Targets **DVB-S / DVB-S2 / DVB-S2X** (Satellite) by default.
   - Supports **DVB-T / DVB-T2** (Terrestrial) and **DVB-C** (Cable) on demand.
   - Injects resolved Control Words (CWs) directly into SoC hardware descramblers (DVB-CSA2, DVB-CSA3, AES-128).
   - Compatible with native manufacturer TV apps without replacing or modifying them.

2. **Software Path (External Streams & Recordings)**:
   - For content **not received directly by the TV tuner** (e.g. encrypted `.ts` recordings, SAT>IP streams, network IPTV).
   - Built-in HTTP proxy at `http://127.0.0.1:9191/play?url=...` that decrypts MPEG-TS packets on-the-fly using software DVB-CSA.

---

## 2. Supported Hardware & SoC Matrix

The bridge features a hardware abstraction layer (`IChipsetAdapter`) with automatic runtime detection (`ChipsetDetector`):

| SoC Vendor | Chipset Family | Example Brands / Devices | Device Node |
|---|---|---|---|
| **Amlogic** | S905D, S905X, S905X4, S928X | TCL, Xiaomi Mi TV, SEI Robotics, MeCool | `/dev/amstream_mpps`, `/dev/dvb0.ca0` |
| **MediaTek** | MT5895, MT9632, Pentonic 700/1000 | Sony Bravia, Philips, TCL, Hisense, Panasonic | `/dev/mtk_ca0`, `/dev/dvb0.ca0` |
| **Realtek** | RTD2851, RTD1319, RTD2873 | Chiq, Strong, Thomson, Nokia | `/dev/rtd_ca0`, `/dev/dvb0.ca0` |
| **Broadcom** | BCM7252, BCM72604, BCM72180 | Technicolor, Humax, Bouygues, Swisscom | `/dev/bcm_ca0`, `/dev/dvb0.ca0` |
| **Synaptics** | VS680, Berlin BG4CT | Google TV Reference Boxes, Canal+, Bbox | `/dev/galois_ca0` |
| **Novatek** | NT72671, NT72688 | Hisense, Skyworth, Toshiba, Sharp | `/dev/nvt_ca0` |

---

## 3. OSCam Server Prerequisites

On your OSCam server (the Linux receiver or PC hosting your legitimate card), configure the network `dvbapi` module.

### `oscam.conf`
Ensure the `[dvbapi]` section is enabled with network listen mode:

```ini
[global]
logfile                       = /var/log/oscam.log
clienttimeout                 = 4000
fallbacktimeout               = 2000
clientmaxidle                 = 120

[dvbapi]
enabled                       = 1
au                            = 1
pmt_mode                      = 0
request_mode                  = 0
listen_port                   = 9000
user                          = android_tv
boxtype                       = pc
```

### `oscam.user`
Create the matching user account:

```ini
[account]
user                          = android_tv
pwd                           = 
group                         = 1
au                            = 1
```

---

## 4. Installation Methods on Android TV

### Method A: Magisk Module (Recommended)

If your Android TV has root via Magisk:

1. Package the vendor files into a zip module:
   ```
   oscam-cas-magisk/
   ├── module.prop
   ├── system/vendor/bin/hw/vendor.oscam.cas-service
   ├── system/vendor/etc/cas_config.xml
   ├── system/vendor/etc/vintf/manifest/android.hardware.cas.xml
   └── system/vendor/lib64/liboscam_jni.so
   ```
2. Install via Magisk App or ADB:
   ```bash
   adb push oscam-cas-magisk.zip /sdcard/
   adb shell su -c "magisk --install-module /sdcard/oscam-cas-magisk.zip"
   ```
3. Install the Companion Settings App:
   ```bash
   adb install -r OscamCasSettings.apk
   ```
4. Reboot the TV:
   ```bash
   adb reboot
   ```

### Method B: Manual ADB Root Installation

If your TV allows `/vendor` remounting as read-write:

```bash
# 1. Connect via ADB
adb connect <TV_IP>:5555
adb root
adb remount

# 2. Push HAL binary and manifests
adb push build_android/vendor.oscam.cas-service /vendor/bin/hw/
adb shell chmod 755 /vendor/bin/hw/vendor.oscam.cas-service

adb push hal/manifest/cas_config.xml /vendor/etc/
adb push hal/manifest/android.hardware.cas.xml /vendor/etc/vintf/manifest/

# 3. Create shared vendor data directory
adb shell mkdir -p /data/vendor/oscam
adb shell chmod 777 /data/vendor/oscam

# 4. Install APK and reboot
adb install -r OscamCasSettings.apk
adb reboot
```

---

## 5. Configuration (Zero-Recompile Workflow)

You **never need to recompile the project** when changing servers, ports, or smartcard CAIDs. Three interactive methods are provided:

### 1. Embedded Web Management Console Pro (Smartphone / PC) — *Recommended*
1. When the TV starts, open your phone, tablet, or PC browser and visit:
   ```
   http://<TV_IP>:8080
   ```
2. **Dashboard & Telemetry**:
   - Live status pill (Connected / Connecting / Disconnected).
   - Dual SVG sparkline charts displaying real-time Control Word latency (ms) and ECM packet flow rate.
   - Quick "Ping All" button to benchmark all configured servers simultaneously.
3. **OSCam Servers & Failover**:
   - Add multiple OSCam server profiles with custom ports, credentials, and priority/failover order.
   - Built-in multi-device **Wake-on-LAN (WoL)**: transmit UDP magic packets to wake sleeping Linux receivers or Docker hosts.
4. **Satellite & DVB Channel Database**:
   - Manage satellite transponders (Frequency, Polarization, Symbol Rate, SID, PMT PID, CAID).
   - Click "Play" to test instant decryption through the Stream Descrambler.
   - Export dynamic M3U playlists (`/playlist.m3u`) and Enigma2 `lamedb` channel bouquets with one click.
5. **Tuner & CAID Presets**:
   - One-click CAID buttons for Movistar+ (0x1810), HD+ Astra (0x1830, 0x1843), Canal+ (0x0100), Viaccess/Fransat (0x0500), Conax (0x0B00), Irdeto (0x0604), Sky (0x09CD, 0x098C), and Nagra (0x1801).
   - In-memory Control Word Cache toggle and cache flush tool.
6. **Stream Proxy & Embedded Video Player**:
   - Test decrypted playback directly inside the browser using HTML5 `<video>` hooked to the port 9191 stream descrambler proxy.
7. **ECM Diagnostic Laboratory**:
   - Paste raw ECM hex strings (e.g. `80 70 51 ...`) to analyze Table ID, parity (Even/Odd), and section lengths.
8. **Hardware & SoC Introspection**:
   - Live readout of detected chipset (Amlogic, MediaTek, Realtek, Broadcom, Synaptics, Novatek), kernel architecture, memory, and VINTF compatibility.
9. **Live Logcat Terminal**:
   - Color-coded logs (green for CW, cyan for ECM, yellow for warnings, red for errors).
   - Auto-scroll toggle and single-click raw log export (`.log` download).
10. **Backup & Instant Hot-Restore**:
    - Download complete JSON snapshots or drag-and-drop a previous backup file to restore without restarting.

### 2. Android TV Settings Menu (D-Pad Remote)
1. Navigate to **Android TV Settings ➔ Apps ➔ OSCam CAS Bridge ➔ Settings** (or launch from the TV app drawer).
2. Adjust the IP, Port, Delivery System, and CAID list using the remote control.
3. Click **"Test Connection"** for immediate diagnostic feedback on screen.
4. Click **"Save & Apply"**.

### 3. Direct JSON File (For Automation / Scripts)
Edit `/data/vendor/oscam/config.json`:
```json
{
    "server_host": "192.168.1.150",
    "server_port": 9000,
    "username": "android_tv",
    "delivery_system": "DVBS",
    "timeout_ms": 4000,
    "reconnect_interval_ms": 2000,
    "caids": [6160, 6192, 6211, 256, 1280, 2816, 1540, 2509]
}
```

---

## 6. Tuner Delivery System Selection

| Mode | Standard | Default CAIDs | Typical Use Case |
|---|---|---|---|
| **`DVBS`** (Default) | DVB-S, DVB-S2, DVB-S2X | `0x1810, 0x1830, 0x1843, 0x0100, 0x0500, 0x0B00, 0x0604, 0x09CD` | Satellite dishes (Astra 19.2°E, Hotbird 13°E, Hispasat 30°W) |
| **`DVBT`** | DVB-T, DVB-T2 | `0x1801, 0x0604, 0x0B00, 0x0500` | Terrestrial antenna Pay-TV |
| **`DVBC`** | DVB-C, DVB-C2 | `0x1801, 0x0604, 0x0B00, 0x098C` | Digital Cable providers |
| **`HYBRID`** | All DVB standards | All listed CAIDs | Multi-tuner TVs with dual feeds |

---

## 7. Playing External Encrypted Streams & Recordings

If you have encrypted `.ts` recordings or SAT>IP streams not handled by the physical tuner:

1. Ensure the bridge service is running.
2. Open any video player on Android TV (**VLC**, **ExoPlayer**, **Kodi**, **Nova Video Player**).
3. Play the stream via the local descrambler proxy:
   - **For Network / SAT>IP streams**:
     ```
     http://127.0.0.1:9191/play?url=http://192.168.1.200:8080/stream.ts
     ```
   - **For local encrypted `.ts` recordings**:
     ```
     http://127.0.0.1:9191/play?file=/sdcard/Movies/satellite_record.ts
     ```
4. The proxy decrypts MPEG-TS packets in memory using DVB-CSA and serves clean video in real-time.

---

## 8. SELinux Policy (`sepolicy`) Checklist

If your Android TV operates in `Enforcing` mode (`adb shell getenforce` returns `Enforcing`), allow outbound socket communication:

Create `/vendor/etc/selinux/vendor_sepolicy.cil` (or add to Magisk `sepolicy.rule`):

```cil
;; Allow OSCam CAS HAL daemon network communication
(allow hal_cas_default self (tcp_socket (create connect write read getattr setopt getopt)))
(allow hal_cas_default node_type (tcp_socket (node_bind)))
(allow hal_cas_default port_type (tcp_socket (name_connect)))

;; Allow reading and watching shared configuration directory
(allow hal_cas_default vendor_data_file (dir (create read write open watch add_name search)))
(allow hal_cas_default vendor_data_file (file (create read write open getattr lock watch)))

;; Allow access to hardware descrambler device nodes
(allow hal_cas_default video_device (chr_file (read write open ioctl)))
```

---

## 9. Verification and Diagnostics

### Live Logcat Stream
```bash
adb logcat -s OscamCasBridge
```

Expected healthy output:
```text
I OscamCasBridge: ChipsetDetector: Detected Amlogic SoC (platform=meson, hardware=amlogic)
I OscamCasBridge: NativeBridge::initialize -> Host: 192.168.1.150, Port: 9000, CAIDs: 8
I OscamCasBridge: DvbapiClient connected to OSCam server!
I OscamCasBridge: Handshake complete: ServerInfo version=11724
I OscamCasBridge: PMT Satellite Prog=30005: CA Descriptor -> CAID=0x1810, ECM_PID=0x0600
I OscamCasBridge: CW received for index 0 (parity: 0, length: 8) -> Injected to SoC descrambler
```

### Checking OSCam Web Interface
In your computer browser, open `http://<OSCAM_SERVER_IP>:8888`:
- Under **Users**, you should see `android_tv` with state **CONNECTED** and client protocol `dvbapi`.
- Under **Status**, you will see ECM request logs answering with valid CWs.

---

## 10. Comprehensive Compilation Guide

### Method A: Standalone Compilation with Android NDK & CMake (Host PC)

For compiling the native libraries (`liboscam_bridge.a`, `liboscam_chipset.a`, `liboscam_jni.so`, and `oscam_bridge_test`) on Linux, macOS, or Windows using the Android NDK:

1. **Set Environment Variables**:
   ```bash
   export ANDROID_NDK_HOME=/path/to/android-ndk-r25c  # Adjust to your NDK path
   ```

2. **Run CMake Cross-Compilation**:
   ```bash
   cmake -B build_android \
       -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
       -DANDROID_ABI=arm64-v8a \
       -DANDROID_PLATFORM=android-31 \
       -DCMAKE_BUILD_TYPE=Release \
       -DANDROID_STL=c++_shared

   cmake --build build_android -j$(nproc)
   ```

3. **Output Artifacts**:
   - `build_android/liboscam_jni.so`: Place in `app/src/main/jniLibs/arm64-v8a/` or push to `/vendor/lib64/`.
   - `build_android/oscam_bridge_test`: Standalone CLI diagnostic tool.

### Method B: AOSP Vendor Build Integration (`Android.bp` / `device.mk`)

If building as part of a custom Android TV ROM or vendor image:

1. Copy repository to your AOSP tree:
   ```bash
   mkdir -p vendor/oscam/cas
   cp -r . vendor/oscam/cas/
   ```

2. Include `build/device.mk` in your device's Makefile (e.g. `device/tcl/beyondtv/device.mk`):
   ```makefile
   $(call inherit-product, vendor/oscam/cas/build/device.mk)
   ```

3. Build the vendor daemon:
   ```bash
   source build/envsetup.sh
   lunch your_target-userdebug
   m vendor.oscam.cas-service
   ```

### Method C: Compiling the Android Companion APK (Gradle)

1. Open project in **Android Studio** or compile via command line:
   ```bash
   ./gradlew assembleRelease
   ```
2. The APK will be generated at `app/build/outputs/apk/release/app-release.apk`.
3. Install on TV:
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

### Method D: Host PC Unit Testing (GoogleTest)

To compile and run all unit tests on your PC (Linux / macOS / Windows):

```bash
cmake -B build_host -DCMAKE_BUILD_TYPE=Debug
cmake --build build_host -j$(nproc)
cd build_host && ctest --output-on-failure
```

