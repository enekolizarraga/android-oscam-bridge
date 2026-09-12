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

## 3. Server Configuration (OSCam DVBAPI & Newcamd v5.25)

The bridge natively supports two protocols: **OSCam DVBAPI** (clear TCP socket) and **Newcamd v5.25** (3DES-encrypted TCP socket). You can configure multiple servers and readers across both protocols simultaneously.

### Option A: OSCam DVBAPI Protocol (`oscam.conf`)
Ensure the `[dvbapi]` section is enabled with network listen mode on your Linux/Raspberry Pi cardserver:

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

### Option B: Newcamd v5.25 Protocol (`oscam.conf`)
Newcamd uses 3DES encryption over a 14-byte DES key. Configure individual TCP ports for each domestic card/CAID:

```ini
[newcamd]
port                          = 10000@1810:000000,004106;10001@1830:000000,003411;10002@183E:000000
key                           = 0102030405060708091011121314
allowed                       = 192.168.0.0-192.168.255.255
keepalive                     = 1
mgclient                      = 0
```

### Option C: CCcam v2.0.11 / v2.3.0 Protocol (`oscam.conf [cccam]` or native CCcam server)
CCcam uses RC4 stream cipher and SHA-1 node challenge handshakes over a single TCP port (typically 12000):

```ini
[cccam]
port                          = 12000
version                       = 2.3.0
reshare                       = 1
stealth                       = 1
nodeid                        = 0102030405060708
```

Or on an original CCcam standalone server (`CCcam.cfg`):
```text
F: android_tv android_tv 2 0 1 { 0:0:2 }
```

### Matching User Account (`oscam.user`)
Create the matching user account in `oscam.user`:

```ini
[account]
user                          = android_tv
pwd                           = android_tv
group                         = 1,2,3
au                            = 1
```

---

### Domestic Multi-Provider Matrix & Quick Templates

The bridge includes built-in one-click configurations for 15 European and international domestic subscription cards:

| Provider Name | Country | Satellite / Orbit | Primary CAID | Default Port | Protocol |
|---|---|---|---|---|---|
| **Movistar+ Satellite** | Spain | Astra 19.2°E / Hispasat 30°W | `0x1810` (Nagra) | `9000` / `10000` | DVBAPI / Newcamd |
| **HD+ Germany** | Germany | Astra 19.2°E | `0x1830`, `0x1843` | `10001` | Newcamd |
| **Sky Deutschland** | Germany / Austria | Astra 19.2°E | `0x098D`, `0x098C` | `10002` | Newcamd |
| **Sky Italia** | Italy | Hotbird 13.0°E | `0x09CD` | `10003` | Newcamd |
| **Sky UK** | UK / Ireland | Astra 28.2°E | `0x0963` | `10004` | Newcamd |
| **Tivùsat** | Italy | Hotbird 13.0°E | `0x183E`, `0x1856` | `10005` | Newcamd |
| **Canal+ France** | France | Astra 19.2°E | `0x0100`, `0x1811` | `10006` | Newcamd |
| **Fransat** | France | Eutelsat 5.0°W | `0x0500` (Viaccess) | `10007` | Newcamd |
| **MEO / NOS** | Portugal | Hispasat 30.0°W | `0x0100`, `0x1802` | `10008` | Newcamd |
| **Polsat Box** | Poland | Hotbird 13.0°E | `0x1803`, `0x1861` | `10009` | Newcamd |
| **SRG SSR** | Switzerland | Hotbird 13.0°E | `0x0500` (Viaccess) | `10010` | Newcamd |
| **ORF Digital** | Austria | Astra 19.2°E | `0x0D95`, `0x0648` | `10011` | Newcamd |
| **D-Smart** | Turkey | Türksat 42.0°E | `0x092B` | `10012` | Newcamd |
| **Vodafone / Unitymedia** | Germany | DVB-C Cable | `0x098E`, `0x1838` | `10013` | Newcamd |
| **Saorview / TDT** | Ireland / Spain | DVB-T/T2 Terrestrial | `0x1801`, `0x0500` | `10014` | Newcamd |

In **Web Console Pro (`http://<TV_IP>:8080`)**, simply click any provider badge in the **"Fast Provider Templates"** toolbar to auto-fill the CAID, port, and default credentials, or add multiple servers across different providers for seamless multi-satellite failover.

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

---

## 11. Troubleshooting & Frequently Asked Questions (FAQ)

### Diagnostic Checklist
Before troubleshooting specific issues, run through this 5-point verification checklist:

1. **Verify Network Reachability**:
   ```bash
   adb shell ping -c 3 <OSCAM_SERVER_IP>
   ```
2. **Verify OSCam dvbapi Listening Port**:
   On your OSCam server:
   ```bash
   netstat -tlpn | grep 9000
   # Expected: tcp 0 0 0.0.0.0:9000 0.0.0.0:* LISTEN
   ```
3. **Verify CAS HAL Daemon Process**:
   ```bash
   adb shell ps -A | grep oscam
   # Expected: vendor.oscam.cas-service
   ```
4. **Verify Shared Configuration Directory**:
   ```bash
   adb shell ls -la /data/vendor/oscam/config.json
   ```
5. **Check SELinux Status**:
   ```bash
   adb shell getenforce
   ```

---

### Common Issues and Solutions

#### Q1: Web Console or App shows `DISCONNECTED (Connection Refused)`
- **Cause**: OSCam server is not listening on TCP, firewall is blocking port 9000, or `oscam.conf` has `boxtype` misconfigured.
- **Solution**:
  1. Open `oscam.conf` on your server and confirm:
     ```ini
     [dvbapi]
     enabled     = 1
     listen_port = 9000
     boxtype     = pc
     ```
  2. If using UFW/iptables, open port 9000: `sudo ufw allow 9000/tcp`.
  3. Use the **"Ping"** button in Web Console Pro (`http://<TV_IP>:8080`) to test network reachability.

#### Q2: Video remains scrambled on a specific satellite channel
- **Cause**: The satellite transponder PMT contains a CAID that is not in your active CAID list.
- **Solution**:
  1. Open `adb logcat -s OscamCasBridge` and tune the channel.
  2. Look for the PMT log entry:
     ```text
     I OscamCasBridge: PMT Satellite Prog=30001: CA Descriptor -> CAID=0x1810, ECM_PID=0x0400
     ```
  3. If the CAID is missing from your configuration, open Web Console Pro (`http://<TV_IP>:8080`), click the corresponding **CAID Preset** button (e.g. `+ Movistar+ (0x1810)`), and click **"Save & Apply"**.

#### Q3: `ioctl failed: Permission denied` in logcat
- **Cause**: SELinux in `Enforcing` mode is preventing the HAL daemon from accessing the hardware descrambler character device (e.g. `/dev/amstream_mpps` on Amlogic or `/dev/mtk_ca0` on MediaTek).
- **Solution**:
  1. Test temporarily with `adb shell setenforce 0`. If video immediately decrypts, apply the SELinux policy from [Section 8](#8-selinux-policy-sepolicy-checklist).
  2. For Magisk users, place the rules in `/data/adb/modules/oscam-cas/sepolicy.rule`.

#### Q4: Screen displays video smoothly, but audio is silent or encrypted
- **Cause**: Some DVB-S2 broadcasters scramble audio with a different ECM PID than the video PID.
- **Solution**:
  The bridge automatically parses all Elementary Stream (ES) descriptors in the PMT loop. Ensure your OSCam user account has `au = 1` and `group` permissions to descramble secondary streams.

#### Q5: External Stream Proxy (`:9191`) drops packets or stutters in VLC
- **Cause**: Network buffer underrun from the SAT>IP receiver or insufficient read timeout.
- **Solution**:
  In VLC or Kodi, increase the network cache buffer to 1000ms:
  `vlc --network-caching=1000 "http://<TV_IP>:9191/play?url=..."`.

---

## 12. Native TV Playback Apps & TCL Integration Guide

For the best user experience, users do not need to install VLC or Kodi for broadcast viewing. The bridge enables descrambling directly within the **official TV app pre-installed by the manufacturer**.

### Step-by-Step Setup on TCL Televisions (Google TV / Android TV)

Verified on **TCL C645, C745, C845, C805, QM8, QM7, P635, P735, C725, C735**:

1. **Connect Satellite LNB**:
   - Plug your satellite dish coaxial cable into the **"ANT/CABLE IN (SATELLITE)"** F-connector on the back of your TCL television.
2. **Perform Channel Scan in Native TCL TV Settings**:
   - Open TCL Settings ➔ **Channels & Inputs** ➔ **Channels** ➔ **Channel Scan**.
   - Select **Satellite (DVB-S/S2)**.
   - Select your satellite orbital position (e.g. `Astra 19.2°E` or `Hispasat 30°W`).
   - Run Full or Fast Scan. All FTA and encrypted transponders are saved into the TCL channel database.
3. **Configure the CAS Bridge Server Profile**:
   - Open `http://<TV_IP>:8080` from your phone or PC.
   - Click the provider preset button (e.g. `Movistar+` or `HD+`).
   - Choose your server protocol:
     - **OSCam (dvbapi)**: Port 9000
     - **Newcamd v5.25**: Port 10000+, DES key `0102030405060708091011121314`
     - **CCcam 2.3.0**: Port 12000, user & password
   - Click **"Test Connection"** to verify ping and handshake latency.
   - Click **"Save & Apply Changes"**.
4. **Watch Encrypted Satellite Channels in TCL Live TV**:
   - Launch the standard **TCL Live TV** or **TCL Channel** app from the TV launcher.
   - Select any encrypted channel from your satellite lineup.
   - The bridge intercepts the tuning event (`com.tcl.tv.action.CHANNEL_CHANGED`), retrieves the resolved Control Words from your cardserver, and writes them straight into `/dev/amstream_mpps` (or `/dev/rtd_ca0`).
   - Picture and sound unlock instantly without any lag, maintaining full 4K HDR/Dolby Vision video quality and native TV remote control navigation.



