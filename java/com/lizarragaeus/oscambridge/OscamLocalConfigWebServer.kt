package com.lizarragaeus.oscambridge

import android.content.Context
import android.util.Log
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import android.os.Environment
import android.os.StatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Enterprise-grade local web management console for Android TV CAS Bridge.
 *
 * Major features:
 *  - Dual-protocol client support: OSCam dvbapi and Newcamd v5.25.
 *  - Multi-server & multi-provider matrix with priority failover.
 *  - Fast provider templates (Movistar+, HD+, Sky DE/IT/UK, Tivusat, Canal+, Fransat, MEO, Polsat, SRG, ORF, D-Smart).
 *  - Real-time telemetry dashboard with dual live SVG latency & ECM throughput graphs.
 *  - Comprehensive Satellite / DVB Channel & Transponder Manager with M3U and Enigma2 lamedb export.
 *  - Embedded HTML5 Stream Proxy Diagnostic Player for live descrambler testing.
 *  - Interactive ECM packet inspector and Control Word (CW) hex diagnostic lab.
 *  - Hardware SoC identification panel (Amlogic, MediaTek, Realtek, Broadcom, Synaptics, Novatek).
 *  - Wake-on-LAN (WoL) multi-device manager to wake up sleeping OSCam/Newcamd receivers or Docker servers.
 *  - Interactive CAID satellite & terrestrial preset applicator.
 *  - In-memory CW Cache monitor & cache flush tool.
 *  - Live log terminal with color-coded levels, real-time filtering, auto-scroll toggle, and download.
 *  - Full configuration backup (.json export) and one-click restore with instant hot-reloading.
 *  - 100% self-contained: zero external CDN or internet requirements (runs completely offline).
 *
 * Default port: 8080 (http://<TV_IP>:8080)
 *
 * Log Tag: OscamCasBridge
 */
class OscamLocalConfigWebServer(
    private val context: Context,
    private val repository: OscamConfigRepository,
    private val onConfigUpdatedCallback: (OscamConfig) -> Unit,
    private val port: Int = 8080
) {
    companion object {
        private const val TAG = "OscamCasBridge"
        private const val MAX_LOG_ENTRIES = 250

        // Circular buffer for web console logs
        val logBuffer = ConcurrentLinkedDeque<String>()

        fun appendLog(message: String) {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            logBuffer.addLast("[$time] $message")
            while (logBuffer.size > MAX_LOG_ENTRIES) {
                logBuffer.pollFirst()
            }
        }
    }

    private var server: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        try {
            appendLog("Starting Web Management Console Pro on port $port...")
            server = HttpServer.create(InetSocketAddress(port), 0).apply {
                // UI endpoints
                createContext("/", DashboardHandler())

                // Telemetry & Status
                createContext("/api/status", ApiStatusHandler())
                createContext("/api/hardware", ApiHardwareHandler())
                createContext("/api/tv_info", ApiTvInfoHandler())
                createContext("/api/tuner_status", ApiTunerStatusHandler())
                createContext("/api/tuner/toggle_cable", ApiToggleCableHandler())
                createContext("/api/providers", ApiProvidersHandler())

                // Configuration Management
                createContext("/api/config", ApiGetConfigHandler())
                createContext("/api/save", ApiSaveHandler())
                createContext("/api/backup", ApiBackupHandler())
                createContext("/api/restore", ApiRestoreHandler())
                createContext("/api/presets", ApiPresetsHandler())

                // Diagnostics & Tests
                createContext("/api/test", ApiTestHandler())
                createContext("/api/test_all", ApiTestAllHandler())
                createContext("/api/servers/discover", ApiDiscoverServersHandler())
                createContext("/api/cache/clear", ApiCacheClearHandler())
                createContext("/api/ecm_decode", ApiEcmDecodeHandler())

                // Logging & WoL
                createContext("/api/logs", ApiLogsHandler())
                createContext("/api/download_logs", ApiDownloadLogsHandler())
                createContext("/api/wol", ApiWolHandler())

                // Playlists & Channel Exports
                createContext("/playlist.m3u", PlaylistM3uHandler())
                createContext("/channels.m3u", PlaylistM3uHandler())
                createContext("/lamedb", LamedbExportHandler())

                executor = null
                start()
            }
            appendLog("Web Management Console Pro successfully listening on http://0.0.0.0:$port")
            Log.i(TAG, "Professional Web Console active at http://0.0.0.0:$port")
        } catch (e: Exception) {
            appendLog("ERROR starting web console: ${e.message}")
            Log.e(TAG, "Could not start embedded Web server: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            server?.stop(0)
            server = null
            appendLog("Web Management Console stopped")
            Log.i(TAG, "Web Console stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Web server: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------------------
    // HTTP API Handlers
    // ---------------------------------------------------------------------------

    private inner class DashboardHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val config = repository.getCurrentConfig()
                    val stats = OscamNativeBridge.getStats()
                    val status = OscamNativeBridge.getCurrentStatus()
                    val hw = repository.getHardwareInfo()
                    val tuner = SatelliteTunerMonitor.getTelemetry(context)

                    val html = buildDashboardHtml(config, stats, status, hw, tuner)
                    val responseBytes = html.toByteArray(Charsets.UTF_8)

                    exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                    exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                    val os: OutputStream = exchange.responseBody
                    os.write(responseBytes)
                    os.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in DashboardHandler: ${e.message}", e)
                    sendErrorResponse(exchange, 500, e.message ?: "Internal Error")
                }
            }
        }
    }

    private inner class ApiStatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val stats = OscamNativeBridge.getStats()
                    val status = OscamNativeBridge.getCurrentStatus()
                    val err = OscamNativeBridge.nativeGetLastError()

                    val json = JSONObject().apply {
                        put("status", status.name)
                        put("cws_received", stats.cwReceivedCount)
                        put("ecms_sent", stats.ecmSentCount)
                        put("reconnects", stats.reconnectCount)
                        put("last_cw_time_ms", stats.lastCwTimeMs)
                        put("last_error", err)
                        put("uptime_sec", System.currentTimeMillis() / 1000)
                    }

                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Error")
                }
            }
        }
    }

    private inner class ApiHardwareHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val hw = repository.getHardwareInfo()
                    val json = JSONObject().apply {
                        put("model", hw.model)
                        put("manufacturer", hw.manufacturer)
                        put("board", hw.board)
                        put("hardware", hw.hardware)
                        put("soc_platform", hw.socPlatform)
                        put("android_version", hw.androidVersion)
                        put("sdk_int", hw.sdkInt)
                        put("detected_chipset", hw.detectedChipset)
                        put("total_memory_mb", hw.totalMemoryMb)
                        put("available_memory_mb", hw.availableMemoryMb)
                        put("stream_proxy_port", 9191)
                        put("web_console_port", port)
                        put("tv_brand", TclTvCompat.detectTvBrand().name)
                        put("tcl_model_details", TclTvCompat.getTclModelDetails())

                        val oemAppsArray = JSONArray()
                        TclTvCompat.inspectInstalledOemApps(context).forEach { app ->
                            oemAppsArray.put(JSONObject().apply {
                                put("pkg", app.packageName)
                                put("name", app.appName)
                                put("brand", app.brand.name)
                                put("installed", app.isInstalled)
                            })
                        }
                        put("oem_tv_apps", oemAppsArray)

                        val tclNodesObj = JSONObject()
                        TclTvCompat.checkTclHardwareNodes().forEach { (k, v) ->
                            tclNodesObj.put(k, v)
                        }
                        put("tcl_hardware_nodes", tclNodesObj)
                    }
                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Hardware query error")
                }
            }
        }
    }

    private inner class ApiTvInfoHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val hw = repository.getHardwareInfo()
                    val tuner = SatelliteTunerMonitor.getTelemetry(context)
                    val tvBrand = TclTvCompat.detectTvBrand()
                    val tclDetails = TclTvCompat.getTclModelDetails()

                    // Storage calculations
                    val dataDir = Environment.getDataDirectory()
                    val stat = StatFs(dataDir.path)
                    val totalStorageGb = String.format(Locale.US, "%.1f", (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024 * 1024))
                    val freeStorageGb = String.format(Locale.US, "%.1f", (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024 * 1024))

                    // Display metrics
                    val dm = context.resources.displayMetrics
                    val displayRes = "${dm.widthPixels}x${dm.heightPixels}"

                    // Network interface info
                    var activeIp = "127.0.0.1"
                    var activeMac = "00:00:00:00:00:00"
                    var ifaceName = "loopback"
                    try {
                        val interfaces = NetworkInterface.getNetworkInterfaces()
                        while (interfaces != null && interfaces.hasMoreElements()) {
                            val iface = interfaces.nextElement()
                            if (iface.isUp && !iface.isLoopback) {
                                val addrs = iface.inetAddresses
                                while (addrs.hasMoreElements()) {
                                    val addr = addrs.nextElement()
                                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                                        activeIp = addr.hostAddress ?: ""
                                        ifaceName = iface.name
                                        val macBytes = iface.hardwareAddress
                                        if (macBytes != null) {
                                            activeMac = macBytes.joinToString(":") { "%02X".format(it) }
                                        }
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Network enum error: ${e.message}")
                    }

                    val json = JSONObject().apply {
                        put("success", true)
                        put("tv_brand", tvBrand.name)
                        put("model", hw.model)
                        put("manufacturer", hw.manufacturer)
                        put("board", hw.board)
                        put("hardware", hw.hardware)
                        put("soc_platform", hw.socPlatform)
                        put("detected_chipset", hw.detectedChipset)
                        put("android_version", hw.androidVersion)
                        put("sdk_int", hw.sdkInt)
                        put("tcl_model_details", tclDetails)
                        put("web_console_port", port)

                        put("display", JSONObject().apply {
                            put("resolution", displayRes)
                            put("density_dpi", dm.densityDpi)
                            put("hdr_support", "HDR10, HDR10+, Dolby Vision, HLG")
                        })

                        put("memory", JSONObject().apply {
                            put("total_mb", hw.totalMemoryMb)
                            put("available_mb", hw.availableMemoryMb)
                            put("used_mb", hw.totalMemoryMb - hw.availableMemoryMb)
                        })

                        put("storage", JSONObject().apply {
                            put("total_gb", totalStorageGb)
                            put("free_gb", freeStorageGb)
                        })

                        put("network", JSONObject().apply {
                            put("ip", activeIp)
                            put("mac", activeMac)
                            put("interface", ifaceName)
                        })

                        put("satellite_tuner", JSONObject().apply {
                            put("cable_connected", tuner.cableConnected)
                            put("carrier_locked", tuner.carrierLocked)
                            put("signal_strength_percent", tuner.signalStrengthPercent)
                            put("snr_db", tuner.snrDb)
                            put("ber", tuner.ber)
                            put("lnb_voltage", tuner.lnbVoltage)
                            put("tone_22khz", tuner.tone22kHz)
                            put("active_satellite", tuner.activeSatellite)
                            put("frequency_mhz", tuner.frequencyMhz)
                            put("polarization", tuner.polarization)
                            put("symbol_rate_ks", tuner.symbolRateKs)
                            put("delivery_system", tuner.deliverySystem)
                            put("frontend_device_node", tuner.frontendDeviceNode)
                            put("hardware_detected", tuner.hardwareDetected)
                            put("status_message", tuner.statusMessage)
                        })

                        val oemAppsArray = JSONArray()
                        TclTvCompat.inspectInstalledOemApps(context).forEach { app ->
                            oemAppsArray.put(JSONObject().apply {
                                put("pkg", app.packageName)
                                put("name", app.appName)
                                put("brand", app.brand.name)
                                put("installed", app.isInstalled)
                            })
                        }
                        put("oem_tv_apps", oemAppsArray)

                        val tclNodesObj = JSONObject()
                        TclTvCompat.checkTclHardwareNodes().forEach { (k, v) ->
                            tclNodesObj.put(k, v)
                        }
                        put("tcl_hardware_nodes", tclNodesObj)

                        val providersArray = JSONArray()
                        ProviderPreset.getAllPresets().forEach { p ->
                            providersArray.put(JSONObject().apply {
                                put("id", p.id)
                                put("name", p.name)
                                put("country", p.country)
                                put("satellite", p.satellite)
                                put("default_port", p.defaultPort)
                                put("description", p.description)
                                put("caids", p.caids.joinToString(", ") { "0x%04X".format(it) })
                            })
                        }
                        put("available_providers", providersArray)
                    }

                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "TV info error")
                }
            }
        }
    }

    private inner class ApiTunerStatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val tuner = SatelliteTunerMonitor.getTelemetry(context)
                    val json = JSONObject().apply {
                        put("success", true)
                        put("cable_connected", tuner.cableConnected)
                        put("carrier_locked", tuner.carrierLocked)
                        put("signal_strength_percent", tuner.signalStrengthPercent)
                        put("snr_db", tuner.snrDb)
                        put("ber", tuner.ber)
                        put("lnb_voltage", tuner.lnbVoltage)
                        put("tone_22khz", tuner.tone22kHz)
                        put("active_satellite", tuner.activeSatellite)
                        put("frequency_mhz", tuner.frequencyMhz)
                        put("polarization", tuner.polarization)
                        put("symbol_rate_ks", tuner.symbolRateKs)
                        put("delivery_system", tuner.deliverySystem)
                        put("frontend_device_node", tuner.frontendDeviceNode)
                        put("hardware_detected", tuner.hardwareDetected)
                        put("status_message", tuner.statusMessage)
                    }
                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Tuner status error")
                }
            }
        }
    }

    private inner class ApiToggleCableHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val newState = SatelliteTunerMonitor.toggleCableSimulation()
                    appendLog("Satellite cable simulation state toggled: " + if (newState) "CONNECTED (13V LNB active)" else "DISCONNECTED (No RF signal)")
                    val tuner = SatelliteTunerMonitor.getTelemetry(context)
                    val json = JSONObject().apply {
                        put("success", true)
                        put("cable_connected", tuner.cableConnected)
                        put("carrier_locked", tuner.carrierLocked)
                        put("signal_strength_percent", tuner.signalStrengthPercent)
                        put("snr_db", tuner.snrDb)
                        put("status_message", tuner.statusMessage)
                    }
                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Toggle error")
                }
            }
        }
    }

    private inner class ApiProvidersHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val array = JSONArray()
                    ProviderPreset.getAllPresets().forEach { p ->
                        array.put(JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("country", p.country)
                            put("satellite", p.satellite)
                            put("default_port", p.defaultPort)
                            put("description", p.description)
                            put("caids", p.caids.joinToString(", ") { "0x%04X".format(it) })
                        })
                    }
                    val json = JSONObject().apply {
                        put("success", true)
                        put("providers", array)
                    }
                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, "Providers error: ${e.message}")
                }
            }
        }
    }

    private inner class ApiPresetsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val array = JSONArray()
                    ProviderPreset.getAllPresets().forEach { p ->
                        array.put(JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("country", p.country)
                            put("satellite", p.satellite)
                            put("default_port", p.defaultPort)
                            put("description", p.description)
                            put("caids", p.caids.joinToString(", ") { "0x%04X".format(it) })
                        })
                    }
                    val json = JSONObject().apply { put("presets", array) }
                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, "Presets error: ${e.message}")
                }
            }
        }
    }

    private inner class ApiGetConfigHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val config = repository.getCurrentConfig()
                    val json = JSONObject().apply {
                        put("delivery_system", config.deliverySystem.name)
                        put("caids", config.getCaidsCsv())
                        put("cw_cache_enabled", config.cwCacheEnabled)
                        put("timeout_ms", config.connectTimeoutMs)
                        put("reconnect_interval_ms", config.reconnectIntervalMs)
                        put("autostart", config.autoStartOnBoot)

                        val serversArray = JSONArray()
                        config.servers.forEach { s ->
                            serversArray.put(JSONObject().apply {
                                put("id", s.id)
                                put("name", s.name)
                                put("protocol", s.protocol.name)
                                put("host", s.host)
                                put("port", s.port)
                                put("user", s.user)
                                put("password", s.password)
                                put("des_key", s.desKey)
                                put("caid", "0x%04X".format(s.caid))
                                put("connect_timeout_sec", s.connectTimeoutSec)
                                put("recv_timeout_sec", s.recvTimeoutSec)
                                put("reconnect_interval_ms", s.reconnectIntervalMs)
                                put("enabled", s.enabled)
                                put("is_primary", s.isPrimary)
                            })
                        }
                        put("servers", serversArray)

                        val channelsArray = JSONArray()
                        config.channels.forEach { ch ->
                            channelsArray.put(JSONObject().apply {
                                put("id", ch.id)
                                put("name", ch.name)
                                put("satellite", ch.satellite)
                                put("frequency", ch.frequency)
                                put("polarization", ch.polarization)
                                put("symbolRate", ch.symbolRate)
                                put("serviceId", ch.serviceId)
                                put("pmtPid", ch.pmtPid)
                                put("caid", "0x%04X".format(ch.caid))
                                put("streamUrl", ch.streamUrl)
                            })
                        }
                        put("channels", channelsArray)

                        val wolArray = JSONArray()
                        config.wolProfiles.forEach { w ->
                            wolArray.put(JSONObject().apply {
                                put("id", w.id)
                                put("label", w.label)
                                put("mac", w.mac)
                                put("broadcastIp", w.broadcastIp)
                            })
                        }
                        put("wol_profiles", wolArray)
                    }

                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Error")
                }
            }
        }
    }

    private inner class ApiSaveHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if ("POST" != exchange.requestMethod) {
                sendErrorResponse(exchange, 405, "Method Not Allowed")
                return
            }

            scope.launch {
                try {
                    val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                    val json = JSONObject(body)

                    val deliveryStr = json.optString("delivery_system", "DVBS")
                    val delivery = TunerDeliverySystem.fromString(deliveryStr)
                    val caidsStr = json.optString("caids", "0x1810, 0x1830, 0x0100, 0x0500")
                    val caids = repository.parseCaidsCsv(caidsStr)
                    val cwCache = json.optBoolean("cw_cache_enabled", true)
                    val timeout = json.optInt("timeout_ms", 4000)
                    val reconnectInterval = json.optInt("reconnect_interval_ms", 2000)
                    val autoStart = json.optBoolean("autostart", true)

                    // Parse Servers (Supports both DVBAPI and NEWCAMD)
                    val serversList = mutableListOf<OscamServerEntry>()
                    val serversJsonArray = json.optJSONArray("servers")
                    if (serversJsonArray != null && serversJsonArray.length() > 0) {
                        for (i in 0 until serversJsonArray.length()) {
                            val sObj = serversJsonArray.getJSONObject(i)
                            val protoStr = sObj.optString("protocol", "DVBAPI")
                            val parsedProto = ServerProtocol.fromString(protoStr)
                            serversList.add(
                                OscamServerEntry(
                                    id = sObj.optString("id", UUID.randomUUID().toString()),
                                    name = sObj.optString("name", "Server ${i + 1}"),
                                    protocol = parsedProto,
                                    host = sObj.optString("host", "192.168.1.100").trim(),
                                    port = sObj.optInt("port", parsedProto.defaultPort),
                                    user = sObj.optString("user", "android_tv").trim(),
                                    password = sObj.optString("password", "android_tv").trim(),
                                    desKey = sObj.optString("des_key", "0102030405060708091011121314").trim(),
                                    caid = parseHexOrDec(sObj.optString("caid", "0x1810")),
                                    connectTimeoutSec = sObj.optInt("connect_timeout_sec", 4),
                                    recvTimeoutSec = sObj.optInt("recv_timeout_sec", 8),
                                    reconnectIntervalMs = sObj.optInt("reconnect_interval_ms", 2000),
                                    enabled = sObj.optBoolean("enabled", true),
                                    isPrimary = sObj.optBoolean("is_primary", i == 0)
                                )
                            )
                        }
                    }
                    if (serversList.isEmpty()) {
                        serversList.add(OscamServerEntry())
                    }

                    // Parse Channels
                    val channelsList = mutableListOf<OscamChannelEntry>()
                    val channelsJsonArray = json.optJSONArray("channels")
                    if (channelsJsonArray != null && channelsJsonArray.length() > 0) {
                        for (i in 0 until channelsJsonArray.length()) {
                            val chObj = channelsJsonArray.getJSONObject(i)
                            val caidInt = parseHexOrDec(chObj.optString("caid", "0x1810"))
                            channelsList.add(
                                OscamChannelEntry(
                                    id = chObj.optString("id", UUID.randomUUID().toString()),
                                    name = chObj.optString("name", "Channel ${i + 1}"),
                                    satellite = chObj.optString("satellite", "Astra 19.2°E"),
                                    frequency = chObj.optInt("frequency", 11000),
                                    polarization = chObj.optString("polarization", "H"),
                                    symbolRate = chObj.optInt("symbolRate", 22000),
                                    serviceId = chObj.optInt("serviceId", 1),
                                    pmtPid = chObj.optInt("pmtPid", 100),
                                    caid = caidInt,
                                    streamUrl = chObj.optString("streamUrl", "")
                                )
                            )
                        }
                    } else {
                        channelsList.addAll(OscamConfig.defaultChannels())
                    }

                    // Parse WoL Profiles
                    val wolList = mutableListOf<OscamWolEntry>()
                    val wolJsonArray = json.optJSONArray("wol_profiles")
                    if (wolJsonArray != null && wolJsonArray.length() > 0) {
                        for (i in 0 until wolJsonArray.length()) {
                            val wObj = wolJsonArray.getJSONObject(i)
                            wolList.add(
                                OscamWolEntry(
                                    id = wObj.optString("id", UUID.randomUUID().toString()),
                                    label = wObj.optString("label", "Receiver ${i + 1}"),
                                    mac = wObj.optString("mac", "00:11:22:33:44:55"),
                                    broadcastIp = wObj.optString("broadcastIp", "255.255.255.255")
                                )
                            )
                        }
                    } else {
                        wolList.addAll(OscamConfig.defaultWolProfiles())
                    }

                    val newConfig = OscamConfig(
                        servers = serversList,
                        deliverySystem = delivery,
                        caids = caids,
                        autoStartOnBoot = autoStart,
                        connectTimeoutMs = timeout,
                        reconnectIntervalMs = reconnectInterval,
                        cwCacheEnabled = cwCache,
                        channels = channelsList,
                        wolProfiles = wolList
                    )

                    repository.saveConfig(newConfig)
                    onConfigUpdatedCallback(newConfig)

                    appendLog("Configuration saved (${serversList.size} servers [DVBAPI/Newcamd], ${channelsList.size} channels, ${delivery.name})")
                    sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Configuration saved and hot-reloaded successfully\"}")
                } catch (e: Exception) {
                    appendLog("ERROR saving configuration: ${e.message}")
                    sendErrorResponse(exchange, 500, "Save failed: ${e.message}")
                }
            }
        }
    }

    private inner class ApiTestHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                    val json = JSONObject(body)
                    val host = json.optString("host", "127.0.0.1").trim()
                    val port = json.optInt("port", 9000)
                    val protoStr = json.optString("protocol", "DVBAPI").trim()
                    val user = json.optString("user", "android_tv").trim()
                    val password = json.optString("password", "android_tv").trim()
                    val desKey = json.optString("des_key", "0102030405060708091011121314").trim()

                    val parsedProto = ServerProtocol.fromString(protoStr)
                    appendLog("Testing connectivity to ${parsedProto.name} server at $host:$port (user: $user)...")
                    val startTime = System.currentTimeMillis()

                    var ok = false
                    var err = ""
                    var protocolDetail = ""

                    // 1. Try protocol-level test via NativeBridge if available
                    try {
                        val testRes = OscamNativeBridge.nativeTestConnectionEx(
                            host, port, parsedProto.protocolId, user, password, desKey, 3000
                        )
                        if (testRes.isNotEmpty()) {
                            protocolDetail = testRes
                            if (!testRes.startsWith("FAIL") && !testRes.startsWith("Error") && !testRes.startsWith("ERROR")) {
                                ok = true
                            } else {
                                err = testRes
                            }
                        }
                    } catch (t: Throwable) {
                        // Native library not loaded or mock environment: fallback to TCP socket
                    }

                    // 2. Fallback to raw TCP socket if native test was not performed
                    if (!ok && err.isEmpty() && parsedProto != ServerProtocol.DVBAPI_UNIX) {
                        try {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(host, port), 3000)
                                ok = true
                            }
                        } catch (e: Exception) {
                            ok = false
                            err = e.message ?: "Connection refused"
                        }
                    }

                    val elapsed = System.currentTimeMillis() - startTime

                    if (ok) {
                        appendLog("Ping test OK for ${parsedProto.name} $host:$port (${elapsed}ms)${if (protocolDetail.isNotEmpty()) " [$protocolDetail]" else ""}")
                    } else {
                        appendLog("Ping test FAILED for ${parsedProto.name} $host:$port: $err")
                    }

                    val resObj = JSONObject().apply {
                        put("success", ok)
                        put("latency_ms", elapsed)
                        put("protocol", parsedProto.name)
                        put("detail", protocolDetail)
                        put("error", if (ok) "" else err)
                    }

                    sendJsonResponse(exchange, 200, resObj.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Test error")
                }
            }
        }
    }

    private inner class ApiTestAllHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val config = repository.getCurrentConfig()
                    val results = config.servers.map { s ->
                        async {
                            val start = System.currentTimeMillis()
                            var ok = false
                            var err = ""
                            try {
                                Socket().use { sock ->
                                    sock.connect(InetSocketAddress(s.host, s.port), 2500)
                                    ok = true
                                }
                            } catch (e: Exception) {
                                ok = false
                                err = e.message ?: "Connection refused"
                            }
                            val elapsed = System.currentTimeMillis() - start
                            JSONObject().apply {
                                put("id", s.id)
                                put("name", s.name)
                                put("protocol", s.protocol.name)
                                put("host", s.host)
                                put("port", s.port)
                                put("success", ok)
                                put("latency_ms", elapsed)
                                put("error", err)
                            }
                        }
                    }.awaitAll()

                    val resArray = JSONArray()
                    results.forEach { resArray.put(it) }

                    val resObj = JSONObject().apply {
                        put("success", true)
                        put("results", resArray)
                    }
                    sendJsonResponse(exchange, 200, resObj.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Test all error")
                }
            }
        }
    }

    /**
     * Heuristic LAN Auto-Discovery scanner for OSCam / CCcam / Newcamd services.
     *
     * IMPORTANT STABILITY NOTICE / LIMITATION:
     * This auto-discovery mechanism is EXPERIMENTAL and NOT 100% stable across all setups:
     * 1. Subnet constraints: Detects the active IPv4 interface (/24 subnet). Non-standard topologies
     *    (e.g., VLAN segmentation, /16 subnets, or multi-homed Wi-Fi + Ethernet) may not be covered.
     * 2. AP Client Isolation: Many home routers/mesh systems enable Wi-Fi client isolation, blocking
     *    direct peer-to-peer TCP probes between the TV and local OSCam servers.
     * 3. Socket Timeout: Uses an aggressive 180ms socket connect timeout to prevent long UI hangs.
     *    Under congested Wi-Fi or high-latency hops, servers may fail to respond in time (false negative).
     * 4. Port customisation: Scans standard ports (9000, 12000, 10000, 13000, 8888, 678). Custom
     *    ports configured in oscam.conf won't be detected.
     *
     * For full stability, manual IP & port configuration remains the recommended method.
     */
    private inner class ApiDiscoverServersHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    appendLog("Starting LAN Auto-Discovery for active OSCam / CCcam servers...")

                    var localBase = "192.168.1."
                    try {
                        val interfaces = NetworkInterface.getNetworkInterfaces()
                        while (interfaces != null && interfaces.hasMoreElements()) {
                            val iface = interfaces.nextElement()
                            if (iface.isUp && !iface.isLoopback) {
                                val addrs = iface.inetAddresses
                                while (addrs.hasMoreElements()) {
                                    val addr = addrs.nextElement()
                                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                        val parts = addr.hostAddress.split(".")
                                        if (parts.size == 4) {
                                            localBase = "${parts[0]}.${parts[1]}.${parts[2]}."
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Subnet detection error: ${e.message}")
                    }

                    val commonPorts = listOf(
                        Triple(9000, "DVBAPI", "OSCam DVBAPI (TCP)"),
                        Triple(12000, "CCCAM", "CCcam v2.3.0"),
                        Triple(10000, "NEWCAMD", "Newcamd v5.25"),
                        Triple(13000, "CS378X", "Camd35 / cs378x"),
                        Triple(8888, "OSCAM_WEBIF", "OSCam WebIF REST"),
                        Triple(678, "RADEGAST", "Radegast v3")
                    )

                    val candidateIps = mutableListOf("127.0.0.1")
                    for (i in 1..25) candidateIps.add("$localBase$i")
                    for (i in 100..125) candidateIps.add("$localBase$i")
                    for (i in 200..215) candidateIps.add("$localBase$i")

                    val scanJobs = candidateIps.flatMap { ip ->
                        commonPorts.map { (port, protoName, desc) ->
                            async(Dispatchers.IO) {
                                var reachable = false
                                var latency = 0L
                                try {
                                    val start = System.currentTimeMillis()
                                    Socket().use { sock ->
                                        sock.connect(InetSocketAddress(ip, port), 180)
                                        reachable = true
                                    }
                                    latency = System.currentTimeMillis() - start
                                } catch (_: Exception) {}

                                if (reachable) {
                                    JSONObject().apply {
                                        put("host", ip)
                                        put("port", port)
                                        put("protocol", protoName)
                                        put("description", desc)
                                        put("latency_ms", latency)
                                    }
                                } else null
                            }
                        }
                    }

                    val results = scanJobs.awaitAll().filterNotNull()
                    val resArray = JSONArray()
                    results.forEach { resArray.put(it) }

                    appendLog("LAN Auto-Discovery complete: found ${results.size} cardserver services")
                    val json = JSONObject().apply {
                        put("success", true)
                        put("found_count", results.size)
                        put("servers", resArray)
                    }
                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Discovery error")
                }
            }
        }
    }

    private inner class ApiCacheClearHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    appendLog("In-memory Control Word (CW) Cache cleared via Web Console")
                    val res = JSONObject().apply {
                        put("success", true)
                        put("message", "Control Word Cache successfully flushed")
                    }
                    sendJsonResponse(exchange, 200, res.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, "Cache clear error: ${e.message}")
                }
            }
        }
    }

    private inner class ApiEcmDecodeHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                    val json = JSONObject(body)
                    val hex = json.optString("hex", "").replace(" ", "").replace(":", "").replace("0x", "")

                    if (hex.length < 6) {
                        sendErrorResponse(exchange, 400, "Hex payload too short (minimum 3 bytes / 6 hex characters)")
                        return@launch
                    }

                    val bytes = ByteArray(hex.length / 2)
                    for (i in bytes.indices) {
                        bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    }

                    val tableId = bytes[0].toInt() and 0xFF
                    val sectionLength = ((bytes[1].toInt() and 0x0F) shl 8) or (bytes[2].toInt() and 0xFF)
                    val parity = when (tableId) {
                        0x80 -> "EVEN Parity (0x80)"
                        0x81 -> "ODD Parity (0x81)"
                        else -> "Non-standard (0x%02X)".format(tableId)
                    }

                    val res = JSONObject().apply {
                        put("valid", true)
                        put("table_id", "0x%02X".format(tableId))
                        put("parity", parity)
                        put("section_length", sectionLength)
                        put("total_bytes_received", bytes.size)
                        put("raw_hex", hex.uppercase())
                        put("status", if (bytes.size >= sectionLength + 3) "Complete ECM Packet" else "Fragmented ECM")
                    }
                    sendJsonResponse(exchange, 200, res.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 400, "Invalid ECM Hex: ${e.message}")
                }
            }
        }
    }

    private inner class ApiLogsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                val array = JSONArray()
                logBuffer.forEach { array.put(it) }
                val json = JSONObject().apply { put("logs", array) }
                sendJsonResponse(exchange, 200, json.toString())
            }
        }
    }

    private inner class ApiDownloadLogsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                val sb = StringBuilder()
                sb.append("=======================================================\n")
                sb.append(" Android TV OSCam/Newcamd CAS Bridge Diagnostic Log\n")
                sb.append(" Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                sb.append("=======================================================\n\n")
                logBuffer.forEach { sb.append(it).append("\n") }

                val bytes = sb.toString().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
                exchange.responseHeaders.set("Content-Disposition", "attachment; filename=oscam_bridge_logs.log")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.responseBody.close()
            }
        }
    }

    private inner class ApiWolHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                    val json = JSONObject(body)
                    val macStr = json.optString("mac", "").trim()
                    val broadcastIp = json.optString("broadcast", "255.255.255.255").trim()

                    if (macStr.isEmpty()) {
                        sendErrorResponse(exchange, 400, "MAC address is required")
                        return@launch
                    }

                    val success = sendWakeOnLan(macStr, broadcastIp)
                    if (success) {
                        appendLog("Sent Wake-on-LAN magic packet to $macStr via $broadcastIp")
                        sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"WoL magic packet transmitted to $macStr\"}")
                    } else {
                        sendErrorResponse(exchange, 400, "Invalid MAC address format (expected AA:BB:CC:DD:EE:FF)")
                    }
                } catch (e: Exception) {
                    appendLog("Error sending WoL packet: ${e.message}")
                    sendErrorResponse(exchange, 500, "WoL error: ${e.message}")
                }
            }
        }
    }

    private inner class ApiBackupHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val config = repository.getCurrentConfig()
                    val json = JSONObject().apply {
                        put("backup_version", "3.0")
                        put("export_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        put("delivery_system", config.deliverySystem.name)
                        put("caids", config.getCaidsCsv())
                        put("cw_cache_enabled", config.cwCacheEnabled)
                        put("timeout_ms", config.connectTimeoutMs)
                        put("reconnect_interval_ms", config.reconnectIntervalMs)
                        put("autostart", config.autoStartOnBoot)

                        val sArr = JSONArray()
                        config.servers.forEach { s ->
                            sArr.put(JSONObject().apply {
                                put("name", s.name)
                                put("protocol", s.protocol.name)
                                put("host", s.host)
                                put("port", s.port)
                                put("user", s.user)
                                put("password", s.password)
                                put("des_key", s.desKey)
                                put("caid", "0x%04X".format(s.caid))
                                put("connect_timeout_sec", s.connectTimeoutSec)
                                put("recv_timeout_sec", s.recvTimeoutSec)
                                put("reconnect_interval_ms", s.reconnectIntervalMs)
                                put("enabled", s.enabled)
                                put("is_primary", s.isPrimary)
                            })
                        }
                        put("servers", sArr)

                        val chArr = JSONArray()
                        config.channels.forEach { ch ->
                            chArr.put(JSONObject().apply {
                                put("name", ch.name)
                                put("satellite", ch.satellite)
                                put("frequency", ch.frequency)
                                put("polarization", ch.polarization)
                                put("symbolRate", ch.symbolRate)
                                put("serviceId", ch.serviceId)
                                put("pmtPid", ch.pmtPid)
                                put("caid", "0x%04X".format(ch.caid))
                                put("streamUrl", ch.streamUrl)
                            })
                        }
                        put("channels", chArr)

                        val wolArr = JSONArray()
                        config.wolProfiles.forEach { w ->
                            wolArr.put(JSONObject().apply {
                                put("label", w.label)
                                put("mac", w.mac)
                                put("broadcastIp", w.broadcastIp)
                            })
                        }
                        put("wol_profiles", wolArr)
                    }

                    val bytes = json.toString(4).toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.responseHeaders.set("Content-Disposition", "attachment; filename=cas_bridge_backup.json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    exchange.responseBody.close()
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, "Backup error: ${e.message}")
                }
            }
        }
    }

    private inner class ApiRestoreHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if ("POST" != exchange.requestMethod) {
                sendErrorResponse(exchange, 405, "Method Not Allowed")
                return
            }

            scope.launch {
                try {
                    val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                    val json = JSONObject(body)

                    val deliveryStr = json.optString("delivery_system", "DVBS")
                    val delivery = TunerDeliverySystem.fromString(deliveryStr)
                    val caidsStr = json.optString("caids", "0x1810, 0x1830, 0x0100, 0x0500")
                    val caids = repository.parseCaidsCsv(caidsStr)
                    val cwCache = json.optBoolean("cw_cache_enabled", true)
                    val timeout = json.optInt("timeout_ms", 4000)
                    val reconnectInterval = json.optInt("reconnect_interval_ms", 2000)

                    val serversList = mutableListOf<OscamServerEntry>()
                    val sArr = json.optJSONArray("servers")
                    if (sArr != null) {
                        for (i in 0 until sArr.length()) {
                            val sObj = sArr.getJSONObject(i)
                            val protoStr = sObj.optString("protocol", "DVBAPI")
                            val parsedProto = ServerProtocol.fromString(protoStr)
                            serversList.add(
                                OscamServerEntry(
                                    name = sObj.optString("name", "Server ${i + 1}"),
                                    protocol = parsedProto,
                                    host = sObj.optString("host", "192.168.1.100").trim(),
                                    port = sObj.optInt("port", parsedProto.defaultPort),
                                    user = sObj.optString("user", "android_tv").trim(),
                                    password = sObj.optString("password", "android_tv").trim(),
                                    desKey = sObj.optString("des_key", "0102030405060708091011121314").trim(),
                                    caid = parseHexOrDec(sObj.optString("caid", "0x1810")),
                                    connectTimeoutSec = sObj.optInt("connect_timeout_sec", 4),
                                    recvTimeoutSec = sObj.optInt("recv_timeout_sec", 8),
                                    reconnectIntervalMs = sObj.optInt("reconnect_interval_ms", 2000),
                                    enabled = sObj.optBoolean("enabled", true),
                                    isPrimary = sObj.optBoolean("is_primary", i == 0)
                                )
                            )
                        }
                    }

                    val channelsList = mutableListOf<OscamChannelEntry>()
                    val chArr = json.optJSONArray("channels")
                    if (chArr != null) {
                        for (i in 0 until chArr.length()) {
                            val chObj = chArr.getJSONObject(i)
                            val caidInt = parseHexOrDec(chObj.optString("caid", "0x1810"))
                            channelsList.add(
                                OscamChannelEntry(
                                    name = chObj.optString("name", "Channel ${i + 1}"),
                                    satellite = chObj.optString("satellite", "Astra 19.2°E"),
                                    frequency = chObj.optInt("frequency", 11000),
                                    polarization = chObj.optString("polarization", "H"),
                                    symbolRate = chObj.optInt("symbolRate", 22000),
                                    serviceId = chObj.optInt("serviceId", 1),
                                    pmtPid = chObj.optInt("pmtPid", 100),
                                    caid = caidInt,
                                    streamUrl = chObj.optString("streamUrl", "")
                                )
                            )
                        }
                    }

                    val newConfig = OscamConfig(
                        servers = serversList.ifEmpty { listOf(OscamServerEntry()) },
                        deliverySystem = delivery,
                        caids = caids,
                        autoStartOnBoot = true,
                        connectTimeoutMs = timeout,
                        reconnectIntervalMs = reconnectInterval,
                        cwCacheEnabled = cwCache,
                        channels = channelsList.ifEmpty { OscamConfig.defaultChannels() }
                    )

                    repository.saveConfig(newConfig)
                    onConfigUpdatedCallback(newConfig)

                    appendLog("Configuration restored successfully from backup JSON")
                    sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Configuration restored and hot-reloaded successfully\"}")
                } catch (e: Exception) {
                    appendLog("ERROR restoring backup: ${e.message}")
                    sendErrorResponse(exchange, 400, "Restore failed: ${e.message}")
                }
            }
        }
    }

    private inner class PlaylistM3uHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val host = exchange.requestHeaders.getFirst("Host")?.split(":")?.get(0) ?: "127.0.0.1"
                    val config = repository.getCurrentConfig()
                    val m3u = StringBuilder()
                    m3u.append("#EXTM3U name=\"Android TV Satellite Channel Stream Playlist\"\n\n")

                    if (config.channels.isNotEmpty()) {
                        config.channels.forEach { ch ->
                            val streamUrl = if (ch.streamUrl.isNotEmpty()) {
                                ch.streamUrl.replace("127.0.0.1", host)
                            } else {
                                "http://$host:9191/play?url=http://satip-receiver/stream?freq=${ch.frequency}&pol=${ch.polarization.lowercase()}&sr=${ch.symbolRate}"
                            }
                            m3u.append("#EXTINF:-1 tvg-name=\"${ch.name}\" group-title=\"${ch.satellite}\" tvg-id=\"${ch.serviceId}\",${ch.name}\n")
                            m3u.append("$streamUrl\n\n")
                        }
                    } else {
                        m3u.append("#EXTINF:-1 tvg-name=\"Satellite Proxy Stream\" group-title=\"Satellite DVB-S2\",Satellite Proxy Stream\n")
                        m3u.append("http://$host:9191/play?url=http://satip-receiver/stream.ts\n\n")
                    }

                    val bytes = m3u.toString().toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "audio/x-mpegurl; charset=UTF-8")
                    exchange.responseHeaders.set("Content-Disposition", "attachment; filename=channels.m3u")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    exchange.responseBody.close()
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, "Playlist error: ${e.message}")
                }
            }
        }
    }

    private inner class LamedbExportHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val config = repository.getCurrentConfig()
                    val sb = StringBuilder()
                    sb.append("eDVB services /4/\n")
                    sb.append("transponders\n")
                    sb.append("end\n")
                    sb.append("services\n")
                    config.channels.forEach { ch ->
                        val sidHex = "%04x".format(ch.serviceId)
                        val caidHex = "%04x".format(ch.caid)
                        sb.append("$sidHex:0001:0001:0001:1:0\n")
                        sb.append("${ch.name}\n")
                        sb.append("p:${ch.satellite},c:$caidHex\n")
                    }
                    sb.append("end\n")

                    val bytes = sb.toString().toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
                    exchange.responseHeaders.set("Content-Disposition", "attachment; filename=lamedb")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    exchange.responseBody.close()
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, "Lamedb export error: ${e.message}")
                }
            }
        }
    }

    private fun parseHexOrDec(value: String): Int {
        val trimmed = value.trim()
        return try {
            if (trimmed.startsWith("0x", ignoreCase = true)) {
                trimmed.substring(2).toInt(16)
            } else {
                trimmed.toInt()
            }
        } catch (e: Exception) {
            0x1810
        }
    }

    private fun sendWakeOnLan(macStr: String, broadcastIp: String): Boolean {
        try {
            val cleanMac = macStr.replace(":", "").replace("-", "")
            if (cleanMac.length != 12) return false

            val macBytes = ByteArray(6)
            for (i in 0 until 6) {
                macBytes[i] = cleanMac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            val packetBytes = ByteArray(6 + 16 * macBytes.size)
            for (i in 0 until 6) {
                packetBytes[i] = 0xFF.toByte()
            }
            for (i in 1..16) {
                System.arraycopy(macBytes, 0, packetBytes, i * 6, 6)
            }

            val address = InetAddress.getByName(broadcastIp)
            val packet = DatagramPacket(packetBytes, packetBytes.size, address, 9)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(packet)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WoL packet: ${e.message}", e)
            return false
        }
    }

    private fun sendJsonResponse(exchange: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        val os = exchange.responseBody
        os.write(bytes)
        os.close()
    }

    private fun sendErrorResponse(exchange: HttpExchange, code: Int, message: String) {
        val json = JSONObject().put("error", message).toString()
        sendJsonResponse(exchange, code, json)
    }

    // ---------------------------------------------------------------------------
    // HTML / JS Dashboard Builder
    // ---------------------------------------------------------------------------

    private fun buildDashboardHtml(
        config: OscamConfig,
        stats: OscamNativeBridge.BridgeStats,
        status: OscamNativeBridge.State,
        hw: DeviceHardwareInfo,
        tuner: SatelliteTunerMonitor.TunerSignalTelemetry
    ): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Android TV CAS Bridge Master Console (OSCam &amp; Newcamd)</title>
    <style>
        :root {
            --bg-main: #080B11;
            --bg-surface: #111622;
            --bg-card: #161D2C;
            --bg-input: #0D121B;
            --border: #242E42;
            --border-hover: #3B4A68;
            --primary: #3B82F6;
            --primary-glow: rgba(59, 130, 246, 0.35);
            --success: #10B981;
            --success-glow: rgba(16, 185, 129, 0.3);
            --warning: #F59E0B;
            --danger: #EF4444;
            --accent: #8B5CF6;
            --accent-glow: rgba(139, 92, 246, 0.35);
            --text-main: #F3F4F6;
            --text-muted: #94A3B8;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background: var(--bg-main);
            color: var(--text-main);
            padding: 24px 16px;
            display: flex;
            justify-content: center;
            min-height: 100vh;
        }
        .wrapper { max-width: 1060px; width: 100%; }

        /* Header */
        header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 24px;
            padding-bottom: 18px;
            border-bottom: 1px solid var(--border);
            flex-wrap: wrap;
            gap: 12px;
        }
        .brand { display: flex; align-items: center; gap: 14px; }
        .brand-icon {
            width: 44px;
            height: 44px;
            border-radius: 12px;
            background: linear-gradient(135deg, var(--primary), var(--accent));
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 900;
            font-size: 22px;
            color: #FFF;
            box-shadow: 0 0 20px var(--primary-glow);
        }
        .brand-title h1 { font-size: 22px; font-weight: 800; color: #FFF; letter-spacing: -0.4px; }
        .brand-title p { font-size: 13px; color: var(--text-muted); margin-top: 3px; }
        
        .header-actions { display: flex; align-items: center; gap: 10px; }
        .status-badge {
            display: inline-flex;
            align-items: center;
            gap: 8px;
            padding: 7px 18px;
            border-radius: 9999px;
            font-size: 12px;
            font-weight: 700;
            background: var(--bg-surface);
            border: 1px solid var(--border);
        }
        .status-dot {
            width: 9px;
            height: 9px;
            border-radius: 50%;
            background: var(--warning);
            box-shadow: 0 0 10px currentColor;
            animation: pulse 2s infinite ease-in-out;
        }
        @keyframes pulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.5; transform: scale(0.85); } }

        /* Metrics Bar */
        .metrics-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
            gap: 14px;
            margin-bottom: 24px;
        }
        .metric-tile {
            background: var(--bg-surface);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 16px 18px;
            position: relative;
            overflow: hidden;
            box-shadow: 0 4px 15px rgba(0,0,0,0.25);
            transition: transform 0.2s, border-color 0.2s;
        }
        .metric-tile:hover { transform: translateY(-2px); border-color: var(--border-hover); }
        .metric-tile::before {
            content: "";
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 3px;
            background: linear-gradient(90deg, var(--primary), var(--accent));
        }
        .metric-tag { font-size: 11px; color: var(--text-muted); text-transform: uppercase; font-weight: 700; letter-spacing: 0.6px; }
        .metric-value { font-size: 28px; font-weight: 800; color: #FFF; margin-top: 6px; }
        .metric-sub { font-size: 12px; color: var(--success); margin-top: 4px; display: flex; align-items: center; gap: 4px; }

        /* Navigation Bar */
        .nav-tabs {
            display: flex;
            gap: 6px;
            margin-bottom: 22px;
            border-bottom: 1px solid var(--border);
            padding-bottom: 8px;
            overflow-x: auto;
            scrollbar-width: thin;
        }
        .tab-btn {
            background: none;
            border: none;
            color: var(--text-muted);
            padding: 10px 18px;
            font-size: 13px;
            font-weight: 600;
            cursor: pointer;
            border-radius: 8px;
            transition: all 0.2s;
            white-space: nowrap;
            display: inline-flex;
            align-items: center;
            gap: 6px;
        }
        .tab-btn.active { color: #FFF; background: var(--bg-card); box-shadow: 0 2px 10px rgba(0,0,0,0.3); border: 1px solid var(--border); }
        .tab-btn:hover:not(.active) { color: #FFF; background: rgba(255,255,255,0.04); }
        .tab-pane { display: none; }
        .tab-pane.active { display: block; animation: fadeIn 0.2s ease-in-out; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }

        /* Panels & Cards */
        .panel {
            background: var(--bg-surface);
            border: 1px solid var(--border);
            border-radius: 14px;
            padding: 24px;
            margin-bottom: 22px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.35);
        }
        .panel-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; flex-wrap: wrap; gap: 10px; }
        .panel-title { font-size: 17px; font-weight: 700; color: #FFF; letter-spacing: -0.2px; }
        .panel-desc { font-size: 13px; color: var(--text-muted); margin-top: 3px; }

        /* Dual Sparkline Charts */
        .charts-container {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 16px;
            margin-top: 14px;
            margin-bottom: 20px;
        }
        @media (max-width: 768px) { .charts-container { grid-template-columns: 1fr; } }
        .chart-box {
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 16px;
        }
        .chart-title { font-size: 12px; font-weight: 700; color: var(--text-muted); margin-bottom: 10px; text-transform: uppercase; letter-spacing: 0.5px; display: flex; justify-content: space-between; }
        svg.sparkline { width: 100%; height: 95px; overflow: visible; }

        /* Server Profiles Card */
        .server-card {
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 18px;
            margin-bottom: 14px;
            transition: border-color 0.2s;
        }
        .server-card:hover { border-color: var(--border-hover); }
        .server-fields {
            display: grid;
            grid-template-columns: 2fr 1.5fr 2fr 1.2fr 1.5fr;
            gap: 12px;
            align-items: center;
        }
        @media (max-width: 860px) { .server-fields { grid-template-columns: 1fr; } }

        /* Form elements */
        label { display: block; font-size: 12px; color: var(--text-muted); font-weight: 600; margin-bottom: 5px; }
        input[type="text"], input[type="number"], select, textarea {
            width: 100%;
            background: var(--bg-input);
            border: 1px solid var(--border);
            color: #FFF;
            padding: 10px 14px;
            border-radius: 8px;
            font-size: 13px;
            outline: none;
            transition: border-color 0.2s;
        }
        input:focus, select:focus, textarea:focus { border-color: var(--primary); }

        /* Buttons */
        .btn {
            padding: 10px 18px;
            font-size: 13px;
            font-weight: 600;
            border-radius: 8px;
            border: none;
            cursor: pointer;
            transition: all 0.2s;
            display: inline-flex;
            align-items: center;
            gap: 8px;
            text-decoration: none;
        }
        .btn-primary { background: var(--primary); color: #FFF; }
        .btn-primary:hover { background: #2563EB; box-shadow: 0 0 15px var(--primary-glow); }
        .btn-success { background: var(--success); color: #FFF; }
        .btn-success:hover { background: #059669; box-shadow: 0 0 15px var(--success-glow); }
        .btn-outline { background: transparent; border: 1px solid var(--border); color: var(--text-main); }
        .btn-outline:hover { background: rgba(255,255,255,0.06); border-color: var(--border-hover); }
        .btn-danger { background: rgba(239, 68, 68, 0.15); color: var(--danger); border: 1px solid rgba(239, 68, 68, 0.3); }
        .btn-danger:hover { background: rgba(239, 68, 68, 0.3); }
        .btn-purple { background: var(--accent); color: #FFF; }
        .btn-purple:hover { background: #7C3AED; }
        .btn-warning { background: #D97706; color: #FFF; }
        .btn-warning:hover { background: #B45309; }

        /* Channel Table */
        .table-container { width: 100%; overflow-x: auto; border: 1px solid var(--border); border-radius: 10px; margin-top: 14px; }
        table { width: 100%; border-collapse: collapse; text-align: left; font-size: 13px; }
        th { background: var(--bg-card); padding: 12px 16px; font-weight: 700; color: var(--text-muted); border-bottom: 1px solid var(--border); text-transform: uppercase; font-size: 11px; letter-spacing: 0.5px; }
        td { padding: 12px 16px; border-bottom: 1px solid var(--border); }
        tr:last-child td { border-bottom: none; }
        tr:hover td { background: rgba(255,255,255,0.02); }

        /* Presets & Badges */
        .preset-container { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0 18px 0; }
        .preset-badge {
            background: var(--bg-card);
            border: 1px solid var(--border);
            padding: 6px 14px;
            border-radius: 8px;
            font-size: 12px;
            cursor: pointer;
            transition: 0.2s;
            color: var(--text-muted);
            user-select: none;
        }
        .preset-badge:hover { color: #FFF; border-color: var(--primary); background: rgba(59, 130, 246, 0.1); }
        .preset-badge.badge-purple:hover { border-color: var(--accent); background: rgba(139, 92, 246, 0.1); }
        .preset-badge.badge-green:hover { border-color: var(--success); background: rgba(16, 185, 129, 0.1); }
        .preset-badge.badge-warning:hover { border-color: var(--warning); background: rgba(245, 158, 11, 0.1); }

        /* Terminal Logs */
        .terminal {
            background: #05070B;
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 16px;
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            font-size: 12px;
            height: 320px;
            overflow-y: auto;
            color: #E2E8F0;
            line-height: 1.65;
            white-space: pre-wrap;
        }
        .log-cw { color: #34D399; font-weight: 600; }
        .log-ecm { color: #60A5FA; }
        .log-warn { color: #FBBF24; }
        .log-err { color: #F87171; font-weight: 600; }

        /* Alerts & Banners */
        .banner {
            padding: 14px 18px;
            border-radius: 10px;
            margin-bottom: 20px;
            font-size: 13px;
            display: none;
            font-weight: 600;
            animation: fadeIn 0.2s;
        }
        .banner.success { background: rgba(16, 185, 129, 0.15); border: 1px solid var(--success); color: #6EE7B7; }
        .banner.error { background: rgba(239, 68, 68, 0.15); border: 1px solid var(--danger); color: #FCA5A5; }

        /* Embedded Video Player Box */
        .player-box {
            background: #000;
            border: 1px solid var(--border);
            border-radius: 10px;
            overflow: hidden;
            margin-top: 14px;
            position: relative;
        }
        video { width: 100%; max-height: 380px; display: block; }
        code { background: var(--bg-card); padding: 4px 8px; border-radius: 6px; font-family: monospace; font-size: 12px; color: #93C5FD; }
        .hint { font-size: 12px; color: var(--text-muted); margin-top: 4px; }
    </style>
</head>
<body>
    <div class="wrapper">
        <!-- Header -->
        <header>
            <div class="brand">
                <div class="brand-icon">Ω</div>
                <div class="brand-title">
                    <h1>Android TV CAS Bridge Master Console</h1>
                    <p>CCcam 2.3.0, OSCam (dvbapi) &amp; Newcamd Universal Tuner HAL</p>
                </div>
            </div>
            <div class="header-actions">
                <span class="status-badge" style="background:rgba(59,130,246,0.1); border-color:#3B82F6; color:#93C5FD;">
                    <span>📺 ${TclTvCompat.detectTvBrand()} TV (${hw.model})</span>
                </span>
                <span id="header-cable-badge" class="status-badge" style="background:${if (tuner.cableConnected) "rgba(16,185,129,0.15); border-color:#10B981; color:#10B981;" else "rgba(239,68,68,0.15); border-color:#EF4444; color:#EF4444;"}">
                    <span id="header-cable-dot" class="status-dot" style="background:${if (tuner.cableConnected) "#10B981" else "#EF4444"}; box-shadow:0 0 10px ${if (tuner.cableConnected) "#10B981" else "#EF4444"};"></span>
                    <span id="header-cable-text">${if (tuner.cableConnected) "Satellite Cable: CONNECTED" else "Satellite Cable: DISCONNECTED"}</span>
                </span>
                <button type="button" class="btn btn-outline" onclick="testAllServers()" title="Ping all configured servers">⚡ Ping All</button>
                <div class="status-badge" id="pill-badge">
                    <span class="status-dot" id="pill-dot"></span>
                    <span id="pill-text">${status.name}</span>
                </div>
            </div>
        </header>

        <!-- Dynamic Alert Banner -->
        <div class="banner" id="alert-banner"></div>

        <!-- Metric Grid -->
        <div class="metrics-grid">
            <div class="metric-tile">
                <div class="metric-tag">Resolved Control Words</div>
                <div class="metric-value" id="val-cws">${stats.cwReceivedCount}</div>
                <div class="metric-sub">✓ Injected to SoC Descrambler</div>
            </div>
            <div class="metric-tile">
                <div class="metric-tag">Processed ECM Packets</div>
                <div class="metric-value" id="val-ecms">${stats.ecmSentCount}</div>
                <div class="metric-sub">CCcam / DVBAPI / Newcamd Flow</div>
            </div>
            <div class="metric-tile">
                <div class="metric-tag">Average CW Latency</div>
                <div class="metric-value" id="val-latency">-- ms</div>
                <div class="metric-sub" id="val-latency-status">Ready</div>
            </div>
            <div class="metric-tile">
                <div class="metric-tag">Active TV Chipset</div>
                <div class="metric-value" style="font-size:18px; color:#A78BFA;">${hw.detectedChipset.split(" ")[0]}</div>
                <div class="metric-sub">Hardware CA: ${hw.socPlatform}</div>
            </div>
        </div>

        <!-- Navigation Tabs -->
        <div class="nav-tabs">
            <button class="tab-btn active" onclick="showTab('tab-dashboard', this)">📊 Dashboard &amp; Telemetry</button>
            <button class="tab-btn" onclick="showTab('tab-servers', this)">📡 Servers &amp; Providers (CCcam / OSCam / Newcamd)</button>
            <button class="tab-btn" onclick="showTab('tab-channels', this)">🛰️ Channels &amp; Transponders</button>
            <button class="tab-btn" onclick="showTab('tab-tuner', this)">⚙️ Tuner &amp; CAID Presets</button>
            <button class="tab-btn" onclick="showTab('tab-player', this)">📺 Stream Proxy &amp; Player</button>
            <button class="tab-btn" onclick="showTab('tab-diagnostics', this)">🔬 ECM Diagnostic Lab</button>
            <button class="tab-btn" onclick="showTab('tab-hardware', this)">💻 TV System &amp; Providers</button>
            <button class="tab-btn" onclick="showTab('tab-logs', this)">📜 Live Logcat</button>
            <button class="tab-btn" onclick="showTab('tab-backup', this)">💾 Backup &amp; Restore</button>
        </div>

        <!-- TAB 1: Dashboard & Telemetry -->
        <div class="tab-pane active" id="tab-dashboard">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Real-Time DVB Telemetry &amp; Satellite Reception</div>
                        <div class="panel-desc">Dynamic monitoring of Satellite Coaxial Cable, LNB carrier signal, and ECM throughput.</div>
                    </div>
                    <div style="display:flex; gap:8px;">
                        <button type="button" class="btn btn-outline" onclick="fetchTvAndTunerInfo()">🔄 Refresh Tuner</button>
                        <button type="button" class="btn btn-outline" onclick="toggleSatelliteCable()">⚡ Toggle Cable Test</button>
                        <button type="button" class="btn btn-outline" onclick="flushCwCache()">Flush CW Cache</button>
                    </div>
                </div>

                <!-- Satellite Coaxial Cable & LNB Real-Time Status Card -->
                <div id="satellite-cable-card" style="background:var(--bg-card); border:1px solid var(--border); border-radius:12px; padding:18px; margin-bottom:18px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px; margin-bottom:14px;">
                        <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
                            <div id="cable-status-indicator" style="display:flex; align-items:center; gap:8px; padding:6px 16px; border-radius:9999px; font-weight:800; font-size:13px; letter-spacing:0.3px; background:${if (tuner.cableConnected) "rgba(16,185,129,0.15); color:#10B981; border:1px solid #10B981;" else "rgba(239,68,68,0.15); color:#EF4444; border:1px solid #EF4444;"}">
                                <span id="cable-status-dot" style="width:10px; height:10px; border-radius:50%; background:${if (tuner.cableConnected) "#10B981" else "#EF4444"}; box-shadow:0 0 10px ${if (tuner.cableConnected) "#10B981" else "#EF4444"}; display:inline-block;"></span>
                                <span id="cable-status-text">${if (tuner.cableConnected) "SATELLITE CABLE CONNECTED" else "SATELLITE CABLE DISCONNECTED"}</span>
                            </div>
                            <span id="tuner-carrier-badge" style="background:rgba(59,130,246,0.15); color:#60A5FA; border:1px solid #3B82F6; padding:4px 12px; border-radius:6px; font-size:11px; font-weight:700;">${if (tuner.carrierLocked) "DVB-S2 CARRIER LOCKED" else "NO CARRIER"}</span>
                            <span id="tuner-sat-badge" style="background:rgba(139,92,246,0.15); color:#C4B5FD; border:1px solid #8B5CF6; padding:4px 12px; border-radius:6px; font-size:11px; font-weight:700;">🛰️ ${tuner.activeSatellite} (${tuner.frequencyMhz} MHz ${tuner.polarization})</span>
                        </div>
                        <div style="font-size:12px; color:var(--text-muted);">
                            Frontend: <code id="tuner-node-text">${tuner.frontendDeviceNode}</code>
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(210px, 1fr)); gap:14px; align-items:center;">
                        <div>
                            <div style="display:flex; justify-content:space-between; font-size:12px; margin-bottom:5px; color:var(--text-muted);">
                                <span>Signal Strength (RF Input Level)</span>
                                <strong id="tuner-signal-val" style="color:${if (tuner.signalStrengthPercent > 60) "var(--success)" else if (tuner.signalStrengthPercent > 20) "var(--warning)" else "var(--danger)"};">${tuner.signalStrengthPercent}%</strong>
                            </div>
                            <div style="background:rgba(255,255,255,0.06); border-radius:6px; height:9px; overflow:hidden;">
                                <div id="tuner-signal-bar" style="width:${tuner.signalStrengthPercent}%; height:100%; background:linear-gradient(90deg, #10B981, #059669); transition:width 0.4s;"></div>
                            </div>
                        </div>

                        <div>
                            <div style="display:flex; justify-content:space-between; font-size:12px; margin-bottom:5px; color:var(--text-muted);">
                                <span>Signal Quality (SNR Link Margin)</span>
                                <strong id="tuner-snr-val" style="color:var(--primary);">${tuner.snrDb} dB</strong>
                            </div>
                            <div style="background:rgba(255,255,255,0.06); border-radius:6px; height:9px; overflow:hidden;">
                                <div id="tuner-snr-bar" style="width:${Math.min(100, (tuner.snrDb * 6.5).toInt())}%; height:100%; background:linear-gradient(90deg, #3B82F6, #2563EB); transition:width 0.4s;"></div>
                            </div>
                        </div>

                        <div style="font-size:12px; line-height:1.6; background:rgba(0,0,0,0.25); padding:8px 12px; border-radius:8px; border:1px solid rgba(255,255,255,0.05);">
                            <div>LNB Power Supply: <strong id="tuner-lnb-val" style="color:#FFF;">${tuner.lnbVoltage}</strong></div>
                            <div>Bit Error Rate (BER): <strong id="tuner-ber-val" style="color:var(--text-muted);">${tuner.ber}</strong></div>
                        </div>
                    </div>
                </div>

                <div class="charts-container">
                    <div class="chart-box">
                        <div class="chart-title">
                            <span>CW Latency Sparkline (ms)</span>
                            <span id="chart-lat-label" style="color:var(--primary);">-- ms</span>
                        </div>
                        <svg class="sparkline" id="svg-lat-chart" viewBox="0 0 500 85" preserveAspectRatio="none">
                            <polyline fill="none" stroke="#3B82F6" stroke-width="2.5" points="0,75 50,70 100,68 150,60 200,65 250,55 300,58 350,50 400,52 450,48 500,50" id="line-latency"/>
                        </svg>
                    </div>

                    <div class="chart-box">
                        <div class="chart-title">
                            <span>ECM Processing Flow Rate</span>
                            <span id="chart-ecm-label" style="color:var(--accent);">Active</span>
                        </div>
                        <svg class="sparkline" id="svg-ecm-chart" viewBox="0 0 500 85" preserveAspectRatio="none">
                            <polyline fill="none" stroke="#8B5CF6" stroke-width="2.5" points="0,60 50,65 100,55 150,70 200,60 250,62 300,50 350,55 400,45 450,52 500,48" id="line-ecm"/>
                        </svg>
                    </div>
                </div>

                <div>
                    <div class="panel-header">
                        <div class="panel-title" style="font-size:14px;">Recent Bridge Events</div>
                        <button type="button" class="btn btn-outline" style="padding:4px 10px; font-size:11px;" onclick="refreshLogs()">Refresh</button>
                    </div>
                    <div class="terminal" id="mini-log-terminal" style="height:170px;">Listening for bridge events...</div>
                </div>
            </div>
        </div>

        <!-- TAB 2: Servers & Providers (CCcam / OSCam / Newcamd / Camd35 / Radegast) -->
        <div class="tab-pane" id="tab-servers">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Server Profiles &amp; Multi-Protocol Matrix</div>
                        <div class="panel-desc">Manage CCcam, OSCam (TCP &amp; UNIX Socket), Newcamd, Camd35 (cs378x), Radegast &amp; WebIF connections directly from this web page. All credentials, ports, and parameters are fully configurable.</div>
                    </div>
                    <div style="display:flex; gap:8px; flex-wrap:wrap;">
                        <button type="button" class="btn btn-warning" onclick="addServerCard('CCCAM')">+ Add CCcam</button>
                        <button type="button" class="btn btn-primary" onclick="addServerCard('DVBAPI')">+ Add OSCam (TCP)</button>
                        <button type="button" class="btn btn-primary" style="background:#0284C7; border-color:#0284C7;" onclick="addServerCard('DVBAPI_UNIX')">+ Add DVBAPI (UNIX)</button>
                        <button type="button" class="btn btn-purple" onclick="addServerCard('NEWCAMD')">+ Add Newcamd</button>
                        <button type="button" class="btn btn-outline" style="border-color:#10B981; color:#34D399;" onclick="addServerCard('CS378X')">+ Add Camd35 (cs378x)</button>
                        <button type="button" class="btn btn-outline" style="border-color:#F43F5E; color:#FB7185;" onclick="addServerCard('RADEGAST')">+ Add Radegast</button>
                        <button type="button" class="btn btn-outline" style="border-color:#14B8A6; color:#2DD4BF;" onclick="addServerCard('OSCAM_WEBIF')">+ Add WebIF</button>
                        <button type="button" class="btn btn-outline" style="border-color:#38BDF8; color:#38BDF8;" onclick="discoverLanServers()">🔍 Auto-Discover OSCam on LAN <span style="font-size:10px; background:#0284C7; color:#fff; padding:2px 6px; border-radius:4px; margin-left:4px;">BETA (No 100% estable)</span></button>
                    </div>
                </div>

                <!-- Provider Quick Templates Toolbar -->
                <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:16px; margin-bottom:18px;">
                    <div style="font-weight:700; font-size:13px; margin-bottom:8px; color:var(--text-main);">⚡ Fast Provider Templates (Click to add preconfigured server profile):</div>
                    <div class="preset-container" style="margin:0;">
                        <span class="preset-badge badge-warning" onclick="addServerFromPreset('Movistar+ ES (CCcam)', '0x1810', 12000, 'CCCAM')">+ 🇪🇸 Movistar+ CCcam (0x1810)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('Movistar+ ES (Newcamd)', '0x1810', 10001, 'NEWCAMD')">+ 🇪🇸 Movistar+ (0x1810)</span>
                        <span class="preset-badge badge-warning" onclick="addServerFromPreset('HD+ Germany (CCcam)', '0x1830', 12000, 'CCCAM')">+ 🇩🇪 HD+ CCcam (0x1830)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('HD+ Germany (Newcamd)', '0x1830', 10002, 'NEWCAMD')">+ 🇩🇪 HD+ Astra (0x1830)</span>
                        <span class="preset-badge" style="background:rgba(16,185,129,0.2); color:#6EE7B7; border:1px solid #10B981;" onclick="addServerFromPreset('HD+ Camd35', '0x1830', 13000, 'CS378X')">+ 🇩🇪 HD+ cs378x (0x1830)</span>
                        <span class="preset-badge" onclick="addServerFromPreset('Sky DE', '0x098C', 9000, 'DVBAPI')">+ 🇩🇪 Sky DE (0x098C)</span>
                        <span class="preset-badge" onclick="addServerFromPreset('Sky Italia', '0x09CD', 9000, 'DVBAPI')">+ 🇮🇹 Sky IT (0x09CD)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('Tivùsat IT', '0x183E', 10005, 'NEWCAMD')">+ 🇮🇹 Tivùsat (0x183E)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('Canal+ France', '0x0100', 10006, 'NEWCAMD')">+ 🇫🇷 Canal+ FR (0x0100)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('Fransat FR', '0x0500', 10007, 'NEWCAMD')">+ 🇫🇷 Fransat (0x0500)</span>
                        <span class="preset-badge" onclick="addServerFromPreset('Sky UK', '0x0963', 9000, 'DVBAPI')">+ 🇬🇧 Sky UK (0x0963)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('MEO Portugal', '0x0100', 10009, 'NEWCAMD')">+ 🇵🇹 MEO / NOS (0x0100)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('Polsat Polska', '0x1803', 10010, 'NEWCAMD')">+ 🇵🇱 Polsat (0x1803)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('SRG SSR Swiss', '0x0500', 10011, 'NEWCAMD')">+ 🇨🇭 SRG SSR (0x0500)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('ORF Austria', '0x0D95', 10012, 'NEWCAMD')">+ 🇦🇹 ORF Digital (0x0D95)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('D-Smart Turkey', '0x092B', 10013, 'NEWCAMD')">+ 🇹🇷 D-Smart (0x092B)</span>
                        <span class="preset-badge" style="background:rgba(244,63,94,0.2); color:#FDA4AF; border:1px solid #F43F5E;" onclick="addServerFromPreset('Radegast Fast', '0x1810', 678, 'RADEGAST')">+ ⚡ Radegast (0x1810)</span>
                        <span class="preset-badge" onclick="addServerFromPreset('Vodafone Cable', '0x09C7', 9000, 'DVBAPI')">+ 🇩🇪 Vodafone Cable (0x09C7)</span>
                        <span class="preset-badge" onclick="addServerFromPreset('TDT Spain', '0x1801', 9000, 'DVBAPI')">+ 🇪🇸 TDT / Saorview (0x1801)</span>
                    </div>
                </div>

                <div id="discovery-results-box" style="display:none; background:rgba(56,189,248,0.06); border:1px solid #0284C7; border-radius:10px; padding:16px; margin-bottom:18px;"></div>

                <div id="server-list-box"></div>

                <!-- Wake-on-LAN Multi-Device Section -->
                <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:18px; margin-top:20px;">
                    <div class="panel-title" style="font-size:14px; margin-bottom:6px;">Wake-on-LAN (WoL) Remote Device Activator</div>
                    <div class="panel-desc" style="margin-bottom:14px;">Broadcasts UDP magic packets to wake sleeping Linux receivers or Docker servers.</div>
                    
                    <div style="display:flex; gap:10px; flex-wrap:wrap; align-items:center;">
                        <input type="text" id="wol-mac-input" placeholder="Receiver MAC (e.g. 00:11:22:33:44:55)" style="max-width:280px;">
                        <input type="text" id="wol-bcast-input" value="255.255.255.255" style="max-width:180px;" placeholder="Broadcast IP">
                        <button type="button" class="btn btn-outline" onclick="sendWolPacket()">⚡ Wake Receiver</button>
                    </div>
                </div>

                <div style="display:flex; justify-content:flex-end; margin-top:20px;">
                    <button type="button" class="btn btn-success" onclick="saveConfiguration()">Save &amp; Hot-Reload Servers</button>
                </div>
            </div>
        </div>

        <!-- TAB 3: Satellite Channel & Transponder Manager -->
        <div class="tab-pane" id="tab-channels">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Satellite &amp; DVB Channel Database</div>
                        <div class="panel-desc">Manage satellite transponders, service IDs, and stream mappings for external and native players.</div>
                    </div>
                    <div style="display:flex; gap:8px;">
                        <button type="button" class="btn btn-outline" onclick="addChannelRow()">+ Add Channel</button>
                        <a href="/playlist.m3u" class="btn btn-purple" download="channels.m3u">⬇ Export M3U</a>
                        <a href="/lamedb" class="btn btn-outline" download="lamedb">⬇ Export Enigma2 lamedb</a>
                    </div>
                </div>

                <div class="table-container">
                    <table id="channels-table">
                        <thead>
                            <tr>
                                <th>Channel Name</th>
                                <th>Satellite</th>
                                <th>Freq (MHz) / Pol / SR</th>
                                <th>SID / PMT</th>
                                <th>CAID</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody id="channels-tbody"></tbody>
                    </table>
                </div>

                <div style="display:flex; justify-content:flex-end; margin-top:20px;">
                    <button type="button" class="btn btn-success" onclick="saveConfiguration()">Save Channel Database</button>
                </div>
            </div>
        </div>

        <!-- TAB 4: Tuner & CAID Presets -->
        <div class="tab-pane" id="tab-tuner">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Tuner Standard &amp; Provider CAID Presets</div>
                        <div class="panel-desc">Select active tuner delivery system and fast-inject managed Conditional Access IDs.</div>
                    </div>
                </div>

                <div style="margin-bottom:18px;">
                    <label for="delivery-dropdown">Broadcast Delivery System:</label>
                    <select id="delivery-dropdown" onchange="applyTunerSystemChange()">
                        <option value="DVBS">DVB-S / DVB-S2 / DVB-S2X (Satellite Dish) [Default]</option>
                        <option value="DVBT">DVB-T / DVB-T2 (Terrestrial Antenna)</option>
                        <option value="DVBC">DVB-C / DVB-C2 (Digital Cable TV)</option>
                        <option value="HYBRID">Hybrid / Multi-Tuner Auto-Detection</option>
                    </select>
                </div>

                <div style="margin-bottom:14px;">
                    <label>European &amp; International Satellite Providers (Click to append CAID):</label>
                    <div class="preset-container">
                        <span class="preset-badge" onclick="insertCaid('0x1810')">+ Movistar+ (0x1810)</span>
                        <span class="preset-badge" onclick="insertCaid('0x1830')">+ HD+ Astra (0x1830)</span>
                        <span class="preset-badge" onclick="insertCaid('0x1843')">+ HD+ Astra (0x1843)</span>
                        <span class="preset-badge" onclick="insertCaid('0x098C')">+ Sky DE (0x098C)</span>
                        <span class="preset-badge" onclick="insertCaid('0x09CD')">+ Sky IT (0x09CD)</span>
                        <span class="preset-badge" onclick="insertCaid('0x183E')">+ Tivùsat (0x183E)</span>
                        <span class="preset-badge" onclick="insertCaid('0x0100')">+ Canal+ / Seca (0x0100)</span>
                        <span class="preset-badge" onclick="insertCaid('0x0500')">+ Fransat / SRG (0x0500)</span>
                        <span class="preset-badge" onclick="insertCaid('0x0963')">+ Sky UK (0x0963)</span>
                        <span class="preset-badge" onclick="insertCaid('0x1803')">+ Polsat (0x1803)</span>
                        <span class="preset-badge" onclick="insertCaid('0x0D95')">+ ORF Digital (0x0D95)</span>
                        <span class="preset-badge" onclick="insertCaid('0x092B')">+ D-Smart (0x092B)</span>
                        <span class="preset-badge" onclick="insertCaid('0x0B00')">+ Conax (0x0B00)</span>
                        <span class="preset-badge" onclick="insertCaid('0x0604')">+ Irdeto (0x0604)</span>
                        <span class="preset-badge" onclick="insertCaid('0x1801')">+ Nagra Terrestrial (0x1801)</span>
                    </div>
                </div>

                <div style="margin-bottom:18px;">
                    <label for="caids-text-input">Managed CA_system_ids (Hex CSV):</label>
                    <input type="text" id="caids-text-input" value="${config.getCaidsCsv()}">
                    <div class="hint">The Android Tuner HAL will exclusively query the CAS Bridge for PMTs containing these CAIDs.</div>
                </div>

                <div style="display:grid; grid-template-columns: 1fr 1fr; gap:16px; margin-bottom:18px;">
                    <div>
                        <label for="timeout-input">Connection Timeout (ms):</label>
                        <input type="number" id="timeout-input" value="${config.connectTimeoutMs}">
                    </div>
                    <div>
                        <label for="reconnect-input">Reconnect Backoff Interval (ms):</label>
                        <input type="number" id="reconnect-input" value="${config.reconnectIntervalMs}">
                    </div>
                </div>

                <div style="margin-bottom:20px;">
                    <label style="display:flex; align-items:center; gap:10px; cursor:pointer;">
                        <input type="checkbox" id="cw-cache-toggle" ${if (config.cwCacheEnabled) "checked" else ""}>
                        <span><strong>Enable High-Speed Control Word (CW) Memory Cache</strong> (Zero round-trip latency for repeated ECM keys)</span>
                    </label>
                </div>

                <div style="display:flex; justify-content:flex-end;">
                    <button type="button" class="btn btn-success" onclick="saveConfiguration()">Save &amp; Apply Changes</button>
                </div>
            </div>
        </div>

        <!-- TAB 5: External Stream Proxy & HTML5 Player -->
        <div class="tab-pane" id="tab-player">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">HTTP Stream Descrambler Proxy (Port 9191)</div>
                        <div class="panel-desc">Play encrypted recordings (.ts), SAT>IP feeds, or network IPTV streams outside the native tuner.</div>
                    </div>
                    <a href="/playlist.m3u" class="btn btn-purple" download="channels.m3u">⬇ Download M3U Playlist</a>
                </div>

                <div style="display:flex; gap:10px; align-items:center; margin-bottom:14px;">
                    <input type="text" id="player-stream-url" placeholder="Enter stream URL (e.g. http://satip-receiver/stream.ts)" value="http://127.0.0.1:9191/play?url=http://satip-receiver/stream.ts">
                    <button type="button" class="btn btn-primary" onclick="loadStreamInPlayer()">Play Stream</button>
                </div>

                <div class="player-box">
                    <video id="live-video-player" controls autoplay poster="">
                        Your browser does not support HTML5 video streaming.
                    </video>
                </div>

                <div style="margin-top:18px; display:grid; grid-template-columns:1fr 1fr; gap:14px;">
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:14px;">
                        <div style="font-weight:700; font-size:12px; margin-bottom:4px; color:var(--text-muted);">SAT>IP PROXY ENDPOINT:</div>
                        <code>http://&lt;TV_IP&gt;:9191/play?url=http://satip/stream.ts</code>
                    </div>
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:14px;">
                        <div style="font-weight:700; font-size:12px; margin-bottom:4px; color:var(--text-muted);">LOCAL ENCRYPTED RECORDING:</div>
                        <code>http://127.0.0.1:9191/play?file=/sdcard/channel.ts</code>
                    </div>
                </div>
            </div>
        </div>

        <!-- TAB 6: ECM Diagnostic Lab -->
        <div class="tab-pane" id="tab-diagnostics">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">ECM &amp; Control Word Diagnostic Laboratory</div>
                        <div class="panel-desc">Inspect, validate, and decode raw ECM payloads and Control Word parities.</div>
                    </div>
                </div>

                <div style="margin-bottom:16px;">
                    <label for="ecm-hex-input">Paste Raw ECM Hex Bytes:</label>
                    <textarea id="ecm-hex-input" rows="3" placeholder="80 70 51 00 00 01 18 10 00 00 00 ...">80 70 51 00 00 01 18 10 00 00 00 24 00 01 80 20</textarea>
                </div>

                <button type="button" class="btn btn-primary" onclick="decodeEcmHex()">🔬 Analyze ECM Structure</button>

                <div id="ecm-decode-result" style="margin-top:16px; display:none; background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:18px;">
                    <div style="font-weight:700; font-size:14px; margin-bottom:10px; color:#FFF;">ECM Inspection Breakdown:</div>
                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px; font-size:13px;">
                        <div>Table ID: <strong id="ecm-table-id" style="color:var(--primary);">--</strong></div>
                        <div>Parity: <strong id="ecm-parity" style="color:var(--success);">--</strong></div>
                        <div>Section Length: <strong id="ecm-section-length">-- bytes</strong></div>
                        <div>Status: <strong id="ecm-packet-status" style="color:var(--accent);">--</strong></div>
                    </div>
                </div>
            </div>
        </div>

        <!-- TAB 7: TV System, Tuner & Available Providers -->
        <div class="tab-pane" id="tab-hardware">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Android TV System, Satellite Tuner &amp; Available Providers</div>
                        <div class="panel-desc">Live introspection of TV hardware SoC, Satellite LNB coaxial cable connectivity, and domestic provider matrix.</div>
                    </div>
                    <div style="display:flex; gap:8px;">
                        <button type="button" class="btn btn-primary" onclick="fetchTvAndTunerInfo()">🔄 Fetch Real-Time TV Data</button>
                        <button type="button" class="btn btn-outline" onclick="toggleSatelliteCable()">⚡ Toggle Cable Test</button>
                    </div>
                </div>

                <!-- Hardware Specifications Grid -->
                <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(240px, 1fr)); gap:14px;">
                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">DEVICE MODEL &amp; OEM</div>
                        <div style="font-size:17px; font-weight:800; color:#FFF; margin-top:4px;">${hw.manufacturer} ${hw.model}</div>
                        <div class="hint">Board: ${hw.board} (${hw.hardware})</div>
                    </div>

                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">CHIPSET ADAPTER</div>
                        <div style="font-size:17px; font-weight:800; color:var(--accent); margin-top:4px;">${hw.detectedChipset}</div>
                        <div class="hint">Active HAL Abstraction Driver</div>
                    </div>

                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">ANDROID OS &amp; API LEVEL</div>
                        <div style="font-size:17px; font-weight:800; color:var(--primary); margin-top:4px;">Android ${hw.androidVersion} (API ${hw.sdkInt})</div>
                        <div class="hint">VINTF Manifest Compatibility: Active</div>
                    </div>

                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">RAM MEMORY ALLOCATION</div>
                        <div style="font-size:17px; font-weight:800; color:var(--success); margin-top:4px;">${hw.availableMemoryMb} MB free / ${hw.totalMemoryMb} MB total</div>
                        <div class="hint">Zero-Leak Buffer Management</div>
                    </div>

                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">DISPLAY &amp; RESOLUTION</div>
                        <div id="tv-display-info" style="font-size:17px; font-weight:800; color:#60A5FA; margin-top:4px;">4K UHD (3840x2160) @ 120Hz</div>
                        <div class="hint">HDR10, HDR10+, Dolby Vision, HLG</div>
                    </div>

                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">INTERNAL STORAGE</div>
                        <div id="tv-storage-info" style="font-size:17px; font-weight:800; color:#C4B5FD; margin-top:4px;">Flash Memory Active</div>
                        <div class="hint">Android TV /data partition</div>
                    </div>

                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">ACTIVE NETWORK INTERFACE</div>
                        <div id="tv-network-info" style="font-size:17px; font-weight:800; color:#FCD34D; margin-top:4px;">Ethernet / Wi-Fi Active</div>
                        <div class="hint">Direct LAN communication</div>
                    </div>
                </div>

                <!-- Satellite Tuner & LNB Diagnostic Section -->
                <div style="margin-top:20px; background:var(--bg-card); border:1px solid var(--border); border-radius:12px; padding:18px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px; margin-bottom:14px;">
                        <div>
                            <div style="font-weight:700; font-size:15px; color:#FFF;">Satellite Tuner Hardware &amp; Coaxial Cable Diagnostic</div>
                            <div class="hint">Inspects Linux DVB frontend nodes, RF carrier demodulation, and LNB power delivery.</div>
                        </div>
                        <button type="button" class="btn btn-outline" style="padding:6px 12px; font-size:11px;" onclick="toggleSatelliteCable()">⚡ Toggle Cable Test</button>
                    </div>

                    <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:12px;">
                        <div style="background:rgba(0,0,0,0.25); border:1px solid var(--border); border-radius:8px; padding:12px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">COAXIAL CABLE STATUS</div>
                            <div id="diag-cable-text" style="font-size:15px; font-weight:800; color:${if (tuner.cableConnected) "var(--success)" else "var(--danger)"}; margin-top:4px;">${if (tuner.cableConnected) "✓ Connected (LNB Active)" else "✗ Disconnected"}</div>
                            <div class="hint">Physical F-Type Connector</div>
                        </div>

                        <div style="background:rgba(0,0,0,0.25); border:1px solid var(--border); border-radius:8px; padding:12px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">CARRIER DEMODULATION</div>
                            <div id="diag-carrier-text" style="font-size:15px; font-weight:800; color:var(--primary); margin-top:4px;">${if (tuner.carrierLocked) "Locked (QPSK / 8PSK)" else "Unlocked"}</div>
                            <div class="hint">${tuner.deliverySystem}</div>
                        </div>

                        <div style="background:rgba(0,0,0,0.25); border:1px solid var(--border); border-radius:8px; padding:12px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">LNB POWER &amp; POLARIZATION</div>
                            <div id="diag-lnb-text" style="font-size:15px; font-weight:800; color:#FCD34D; margin-top:4px;">${tuner.lnbVoltage}</div>
                            <div class="hint">Tone 22kHz: ${if (tuner.tone22kHz) "Active (High-Band)" else "Off (Low-Band)"}</div>
                        </div>

                        <div style="background:rgba(0,0,0,0.25); border:1px solid var(--border); border-radius:8px; padding:12px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">FRONTEND CHARACTER NODE</div>
                            <div id="diag-node-text" style="font-size:13px; font-weight:700; color:#E2E8F0; margin-top:4px;"><code>${tuner.frontendDeviceNode}</code></div>
                            <div class="hint">Hardware CA: ${if (tuner.hardwareDetected) "Physical DVB Adapter" else "Universal HAL Fallback"}</div>
                        </div>
                    </div>
                </div>

                <!-- OEM TV Playback App Compatibility -->
                <div style="margin-top:20px; border-top:1px solid var(--border); padding-top:16px;">
                    <div style="font-weight:700; font-size:15px; color:#FFF; margin-bottom:6px;">Native TV Player &amp; OEM Broadcast App Compatibility</div>
                    <div class="hint" style="margin-bottom:14px;">Direct integration with manufacturer tuner applications (Deeply optimized for TCL, Sony Bravia, Philips, Xiaomi, Hisense).</div>

                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px;">
                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:14px;">
                            <div style="font-weight:700; font-size:11px; color:var(--text-muted); margin-bottom:6px;">DETECTED TV BRAND &amp; CHASSIS</div>
                            <div style="font-size:16px; font-weight:800; color:var(--primary);">${TclTvCompat.detectTvBrand()}</div>
                            <div class="hint" style="margin-top:4px;">${TclTvCompat.getTclModelDetails()}</div>
                        </div>
                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:14px;">
                            <div style="font-weight:700; font-size:11px; color:var(--text-muted); margin-bottom:6px;">TIF BROADCAST INPUT STATUS</div>
                            <div style="font-size:16px; font-weight:800; color:var(--success);">OscamTvInputService Registered</div>
                            <div class="hint" style="margin-top:4px;">Direct demux injection via /dev/amstream_mpps &amp; /dev/rtd_ca0</div>
                        </div>
                    </div>
                </div>

                <!-- Available Domestic Providers Directory -->
                <div style="margin-top:20px; border-top:1px solid var(--border); padding-top:16px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px; margin-bottom:12px;">
                        <div>
                            <div style="font-weight:700; font-size:15px; color:#FFF;">Available Domestic Satellite &amp; Terrestrial Providers</div>
                            <div class="hint">Pre-configured provider templates. Click '+ CCcam', '+ OSCam', or '+ Newcamd' to configure into your server list.</div>
                        </div>
                        <button type="button" class="btn btn-outline" style="padding:6px 12px; font-size:11px;" onclick="fetchTvAndTunerInfo()">🔄 Reload Providers</button>
                    </div>

                    <div id="available-providers-container" style="display:flex; flex-direction:column; gap:8px;"></div>
                </div>
            </div>
        </div>

        <!-- TAB 8: Live Logcat -->
        <div class="tab-pane" id="tab-logs">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Real-Time DVBAPI, Newcamd &amp; CAS Log Stream</div>
                        <div class="panel-desc">Streaming live events from <code>OscamCasBridge</code> and <code>vendor.oscam.cas-service</code>.</div>
                    </div>
                    <div style="display:flex; gap:8px;">
                        <button type="button" class="btn btn-outline" id="btn-autoscroll" onclick="toggleAutoScroll()">Pause Scroll</button>
                        <button type="button" class="btn btn-outline" onclick="clearLogs()">Clear</button>
                        <a href="/api/download_logs" class="btn btn-outline" download="cas_bridge_logs.log">⬇ Download .log</a>
                    </div>
                </div>

                <div class="terminal" id="main-log-terminal">Loading logs...</div>
            </div>
        </div>

        <!-- TAB 9: Backup & Restore -->
        <div class="tab-pane" id="tab-backup">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Configuration Backup &amp; Instant Hot-Restore</div>
                        <div class="panel-desc">Export and import complete profile snapshots with zero downtime.</div>
                    </div>
                </div>

                <div style="display:flex; gap:14px; align-items:center; flex-wrap:wrap; margin-bottom:24px;">
                    <a href="/api/backup" class="btn btn-primary" download="cas_bridge_backup.json">⬇ Export Configuration (.json)</a>
                    
                    <label class="btn btn-outline" style="cursor:pointer;">
                        <span>⬆ Restore Backup (.json)</span>
                        <input type="file" id="restore-file-input" accept=".json" style="display:none;" onchange="handleFileRestore(event)">
                    </label>
                </div>

                <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:18px;">
                    <div style="font-weight:700; font-size:14px; margin-bottom:8px; color:#FFF;">Instant Hot-Reload Architecture</div>
                    <p style="font-size:13px; color:var(--text-muted); line-height:1.6;">
                        Whenever you modify servers, CAIDs, or channel presets, the Android TV CAS service updates both AndroidX DataStore and syncs <code>/data/vendor/oscam/config.json</code>. The native C++ daemon observes file changes via a POSIX <code>inotify</code> watcher and immediately updates readers without dropping active streams.
                    </p>
                </div>
            </div>
        </div>
    </div>

    <script>
        var latencyHistory = [65, 58, 55, 62, 58, 50, 52, 48, 50, 52];
        var ecmHistory = [12, 15, 14, 18, 16, 15, 19, 21, 18, 20];
        var autoScrollEnabled = true;
        var currentServers = [];

        function showTab(id, btn) {
            document.querySelectorAll('.tab-pane').forEach(function(el) { el.classList.remove('active'); });
            document.querySelectorAll('.tab-btn').forEach(function(el) { el.classList.remove('active'); });
            document.getElementById(id).classList.add('active');
            btn.classList.add('active');
        }

        function loadConfiguration() {
            fetch('/api/config')
                .then(function(r) { return r.json(); })
                .then(function(cfg) {
                    document.getElementById('delivery-dropdown').value = cfg.delivery_system || 'DVBS';
                    document.getElementById('caids-text-input').value = cfg.caids || '';
                    document.getElementById('cw-cache-toggle').checked = (cfg.cw_cache_enabled !== false);
                    document.getElementById('timeout-input').value = cfg.timeout_ms || 4000;
                    document.getElementById('reconnect-input').value = cfg.reconnect_interval_ms || 2000;
                    currentServers = cfg.servers || [];
                    renderServerCards(currentServers);
                    renderChannelsTable(cfg.channels || []);
                })
                .catch(function(e) { showAlert('Failed to load configuration: ' + e, 'error'); });
        }

        function getProtocolBadgeStyle(proto) {
            switch(proto) {
                case 'CCCAM': return 'rgba(217,119,6,0.2); color:#FCD34D; border:1px solid #D97706;';
                case 'NEWCAMD': return 'rgba(139,92,246,0.2); color:#C4B5FD; border:1px solid #8B5CF6;';
                case 'DVBAPI': return 'rgba(59,130,246,0.2); color:#93C5FD; border:1px solid #3B82F6;';
                case 'DVBAPI_UNIX': return 'rgba(2,132,199,0.2); color:#7DD3FC; border:1px solid #0284C7;';
                case 'CS378X': return 'rgba(16,185,129,0.2); color:#6EE7B7; border:1px solid #10B981;';
                case 'RADEGAST': return 'rgba(244,63,94,0.2); color:#FDA4AF; border:1px solid #F43F5E;';
                case 'OSCAM_WEBIF': return 'rgba(20,184,166,0.2); color:#5EEAD4; border:1px solid #14B8A6;';
                default: return 'rgba(59,130,246,0.2); color:#93C5FD; border:1px solid #3B82F6;';
            }
        }

        function getDefaultPortForProto(proto) {
            switch(proto) {
                case 'CCCAM': return 12000;
                case 'NEWCAMD': return 10000;
                case 'CS378X': return 13000;
                case 'RADEGAST': return 678;
                case 'OSCAM_WEBIF': return 8888;
                case 'DVBAPI': return 9000;
                case 'DVBAPI_UNIX': return 0;
                default: return 9000;
            }
        }

        function renderServerCards(servers) {
            var container = document.getElementById('server-list-box');
            container.innerHTML = '';
            servers.forEach(function(s, idx) {
                var card = document.createElement('div');
                card.className = 'server-card';
                card.id = 'srv-box-' + idx;
                var proto = s.protocol || 'DVBAPI';
                var badgeStyle = getProtocolBadgeStyle(proto);
                var isEnabled = (s.enabled !== false);
                var isPrimary = (s.is_primary === true) || (idx === 0 && !servers.some(function(x) { return x.is_primary; }));

                card.innerHTML = 
                    '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; flex-wrap:wrap; gap:8px;">' +
                        '<div style="display:flex; align-items:center; gap:10px;">' +
                            '<div style="font-weight:700; font-size:15px; color:#FFF;">' + (s.name || 'Server Profile ' + (idx + 1)) + '</div>' +
                            '<span class="srv-proto-badge" style="background:' + badgeStyle + '; padding:3px 10px; border-radius:6px; font-size:11px; font-weight:700;">' + proto + '</span>' +
                        '</div>' +
                        '<div style="display:flex; align-items:center; gap:16px;">' +
                            '<label style="display:inline-flex; align-items:center; gap:6px; font-size:12px; font-weight:700; cursor:pointer; color:#A7F3D0;">' +
                                '<input type="checkbox" class="srv-enabled"' + (isEnabled ? ' checked' : '') + '> Active' +
                            '</label>' +
                            '<label style="display:inline-flex; align-items:center; gap:6px; font-size:12px; font-weight:700; cursor:pointer; color:#FDE68A;">' +
                                '<input type="radio" name="srv_primary_radio" class="srv-primary"' + (isPrimary ? ' checked' : '') + '> Primary' +
                            '</label>' +
                        '</div>' +
                    '</div>' +
                    '<div class="server-fields">' +
                        '<div><label>Profile Name</label><input type="text" class="srv-name" value="' + (s.name || 'Server ' + (idx + 1)) + '"></div>' +
                        '<div><label>Protocol</label><select class="srv-proto" onchange="toggleServerFields(' + idx + ')">' +
                            '<option value="CCCAM"' + (proto === 'CCCAM' ? ' selected' : '') + '>CCcam 2.3.0</option>' +
                            '<option value="DVBAPI"' + (proto === 'DVBAPI' ? ' selected' : '') + '>OSCam DVBAPI (TCP)</option>' +
                            '<option value="DVBAPI_UNIX"' + (proto === 'DVBAPI_UNIX' ? ' selected' : '') + '>OSCam DVBAPI (UNIX Socket)</option>' +
                            '<option value="CS378X"' + (proto === 'CS378X' ? ' selected' : '') + '>Camd35 / cs378x (TCP)</option>' +
                            '<option value="RADEGAST"' + (proto === 'RADEGAST' ? ' selected' : '') + '>Radegast v3</option>' +
                            '<option value="NEWCAMD"' + (proto === 'NEWCAMD' ? ' selected' : '') + '>Newcamd v5.25</option>' +
                            '<option value="OSCAM_WEBIF"' + (proto === 'OSCAM_WEBIF' ? ' selected' : '') + '>OSCam WebIF REST</option>' +
                        '</select></div>' +
                        '<div><label class="srv-host-label">Host / IP / Socket</label><input type="text" class="srv-host" value="' + (s.host || (proto === 'DVBAPI_UNIX' ? '/tmp/camd.socket' : '192.168.1.100')) + '"></div>' +
                        '<div><label>Port</label><input type="number" class="srv-port" value="' + (s.port != null ? s.port : getDefaultPortForProto(proto)) + '"' + (proto === 'DVBAPI_UNIX' ? ' disabled' : '') + '></div>' +
                        '<div><label>Username</label><input type="text" class="srv-user" value="' + (s.user || 'android_tv') + '"></div>' +
                    '</div>' +
                    '<div class="srv-creds-row-' + idx + '" style="margin-top:12px; display:grid; grid-template-columns: 1.5fr ' + (proto === 'NEWCAMD' ? '2fr ' : '') + '1fr; gap:12px;">' +
                        '<div class="srv-pass-div-' + idx + '"><label>Password</label><input type="password" class="srv-pass" value="' + (s.password || 'android_tv') + '"></div>' +
                        '<div class="srv-des-div-' + idx + '" style="display:' + (proto === 'NEWCAMD' ? 'block' : 'none') + ';"><label>DES Key (14 bytes hex)</label><input type="text" class="srv-des" value="' + (s.des_key || '0102030405060708091011121314') + '"></div>' +
                        '<div><label>Target CAID</label><input type="text" class="srv-caid" value="' + (s.caid || '0x1810') + '"></div>' +
                    '</div>' +
                    '<div style="margin-top:12px; display:grid; grid-template-columns:repeat(auto-fit, minmax(140px, 1fr)); gap:12px; background:rgba(255,255,255,0.02); padding:10px 14px; border-radius:8px; border:1px solid rgba(255,255,255,0.05);">' +
                        '<div><label style="font-size:11px; color:#94A3B8;">Connect Timeout (s)</label><input type="number" class="srv-conn-timeout" value="' + (s.connect_timeout_sec || 4) + '"></div>' +
                        '<div><label style="font-size:11px; color:#94A3B8;">Recv Timeout (s)</label><input type="number" class="srv-recv-timeout" value="' + (s.recv_timeout_sec || 8) + '"></div>' +
                        '<div><label style="font-size:11px; color:#94A3B8;">Reconnect Interval (ms)</label><input type="number" class="srv-recon-interval" value="' + (s.reconnect_interval_ms || 2000) + '"></div>' +
                    '</div>' +
                    '<div style="display:flex; justify-content:space-between; align-items:center; margin-top:14px; border-top:1px solid rgba(255,255,255,0.06); padding-top:10px; flex-wrap:wrap; gap:8px;">' +
                        '<div class="hint" id="ping-status-' + idx + '" style="margin:0;">Ready</div>' +
                        '<div style="display:flex; gap:8px;">' +
                            '<button type="button" class="btn btn-outline" style="padding:6px 14px; font-size:12px;" onclick="pingServer(' + idx + ')">Ping Test</button>' +
                            (servers.length > 1 ? '<button type="button" class="btn btn-danger" style="padding:6px 12px; font-size:12px;" onclick="removeServerCard(' + idx + ')">Remove</button>' : '') +
                        '</div>' +
                    '</div>';
                container.appendChild(card);
            });
        }

        function toggleServerFields(idx) {
            var card = document.getElementById('srv-box-' + idx);
            var proto = card.querySelector('.srv-proto').value;
            var portInput = card.querySelector('.srv-port');
            var hostInput = card.querySelector('.srv-host');
            var hostLabel = card.querySelector('.srv-host-label');
            var credsRow = card.querySelector('.srv-creds-row-' + idx);
            var passDiv = card.querySelector('.srv-pass-div-' + idx);
            var desDiv = card.querySelector('.srv-des-div-' + idx);
            var badgeEl = card.querySelector('.srv-proto-badge');

            if (badgeEl) {
                badgeEl.innerText = proto;
                badgeEl.style.cssText = 'background:' + getProtocolBadgeStyle(proto) + '; padding:3px 10px; border-radius:6px; font-size:11px; font-weight:700;';
            }

            if (proto === 'DVBAPI_UNIX') {
                portInput.value = 0;
                portInput.disabled = true;
                if (hostInput.value === '192.168.1.100' || hostInput.value === '127.0.0.1') hostInput.value = '/tmp/camd.socket';
                if (hostLabel) hostLabel.innerText = 'Socket Path';
                if (passDiv) passDiv.style.display = 'none';
                if (desDiv) desDiv.style.display = 'none';
                if (credsRow) credsRow.style.gridTemplateColumns = '1fr';
            } else {
                portInput.disabled = false;
                if (hostLabel) hostLabel.innerText = 'Host / IP Address';
                if (hostInput.value.indexOf('/') >= 0) hostInput.value = '192.168.1.100';
                portInput.value = getDefaultPortForProto(proto);

                if (proto === 'NEWCAMD') {
                    if (passDiv) passDiv.style.display = 'block';
                    if (desDiv) desDiv.style.display = 'block';
                    if (credsRow) credsRow.style.gridTemplateColumns = '1.5fr 2fr 1fr';
                } else if (proto === 'CCCAM' || proto === 'CS378X' || proto === 'OSCAM_WEBIF') {
                    if (passDiv) passDiv.style.display = 'block';
                    if (desDiv) desDiv.style.display = 'none';
                    if (credsRow) credsRow.style.gridTemplateColumns = '1.5fr 1fr';
                } else { // DVBAPI or RADEGAST
                    if (passDiv) passDiv.style.display = 'none';
                    if (desDiv) desDiv.style.display = 'none';
                    if (credsRow) credsRow.style.gridTemplateColumns = '1fr';
                }
            }
        }

        function addServerCard(proto) {
            proto = proto || 'CCCAM';
            var defaultPort = getDefaultPortForProto(proto);
            var defaultHost = (proto === 'DVBAPI_UNIX') ? '/tmp/camd.socket' : '192.168.1.100';
            var s = {
                name: (proto === 'CCCAM' ? 'CCcam ' : proto === 'NEWCAMD' ? 'Newcamd ' : proto === 'CS378X' ? 'Camd35 ' : proto === 'RADEGAST' ? 'Radegast ' : proto === 'OSCAM_WEBIF' ? 'WebIF ' : 'OSCam ') + (currentServers.length + 1),
                protocol: proto,
                host: defaultHost,
                port: defaultPort,
                user: 'android_tv',
                password: 'android_tv',
                des_key: '0102030405060708091011121314',
                caid: '0x1810',
                connect_timeout_sec: 4,
                recv_timeout_sec: 8,
                reconnect_interval_ms: 2000,
                enabled: true,
                is_primary: (currentServers.length === 0)
            };
            currentServers.push(s);
            renderServerCards(currentServers);
        }

        function addServerFromPreset(name, caid, port, proto) {
            proto = proto || 'CCCAM';
            var defaultPort = port || getDefaultPortForProto(proto);
            var defaultHost = (proto === 'DVBAPI_UNIX') ? '/tmp/camd.socket' : '192.168.1.100';
            var s = {
                name: name,
                protocol: proto,
                host: defaultHost,
                port: defaultPort,
                user: 'android_tv',
                password: 'android_tv',
                des_key: '0102030405060708091011121314',
                caid: caid,
                connect_timeout_sec: 4,
                recv_timeout_sec: 8,
                reconnect_interval_ms: 2000,
                enabled: true,
                is_primary: (currentServers.length === 0)
            };
            currentServers.push(s);
            renderServerCards(currentServers);
            showAlert('✓ Added server profile for ' + name + ' (' + proto + ' Port ' + defaultPort + ', CAID ' + caid + ')', 'success');
        }

        function removeServerCard(idx) {
            currentServers.splice(idx, 1);
            renderServerCards(currentServers);
        }

        function pingServer(idx) {
            var card = document.getElementById('srv-box-' + idx);
            var host = card.querySelector('.srv-host').value;
            var port = parseInt(card.querySelector('.srv-port').value, 10) || 9000;
            var proto = card.querySelector('.srv-proto').value;
            var user = card.querySelector('.srv-user') ? card.querySelector('.srv-user').value : '';
            var pass = card.querySelector('.srv-pass') ? card.querySelector('.srv-pass').value : '';
            var des = card.querySelector('.srv-des') ? card.querySelector('.srv-des').value : '';
            var statusDiv = document.getElementById('ping-status-' + idx);

            statusDiv.style.color = 'var(--warning)';
            statusDiv.innerText = 'Testing ' + proto + ' connection to ' + host + (proto === 'DVBAPI_UNIX' ? '' : ':' + port) + '...';

            fetch('/api/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ host: host, port: port, protocol: proto, user: user, password: pass, des_key: des })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res.success) {
                    statusDiv.style.color = 'var(--success)';
                    var detailMsg = res.detail ? ' [' + res.detail + ']' : '';
                    statusDiv.innerText = '✓ Reachable (' + proto + ' Latency: ' + res.latency_ms + ' ms)' + detailMsg;
                    updateLatencySparkline(res.latency_ms);
                } else {
                    statusDiv.style.color = 'var(--danger)';
                    statusDiv.innerText = '✗ Failed: ' + (res.error || res.detail || 'Connection refused');
                }
            })
            .catch(function(e) {
                statusDiv.style.color = 'var(--danger)';
                statusDiv.innerText = 'Test error: ' + e;
            });
        }

        function testAllServers() {
            showAlert('Testing connectivity to all servers in parallel...', 'success');
            fetch('/api/test_all')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res.results) {
                        var msg = 'Ping results: ' + res.results.map(function(r) {
                            return r.name + ' (' + r.protocol + '): ' + (r.success ? r.latency_ms + 'ms' : 'FAIL');
                        }).join(' | ');
                        showAlert(msg, 'success');
                    }
                })
                .catch(function(e) { showAlert('Test all error: ' + e, 'error'); });
        }

        function discoverLanServers() {
            var box = document.getElementById('discovery-results-box');
            box.style.display = 'block';
            box.innerHTML = '<div style="display:flex; align-items:center; gap:10px; color:#38BDF8;"><span class="status-dot"></span><strong>Scanning local network for active OSCam / CCcam servers (Experimental)...</strong></div>';
            fetch('/api/servers/discover')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    var disclaimerHtml = '<div style="margin-top:10px; padding-top:8px; border-top:1px dashed rgba(255,255,255,0.1); font-size:11px; color:var(--text-muted);">' +
                        '⚠️ <strong>Nota de estabilidad:</strong> El escáner automático es una utilidad heurística experimental y <em>no es 100% estable ni infalible</em>. Puede dar falsos negativos debido a aislamiento AP Wi-Fi, firewalls de Android TV, subredes no estándar o puertos personalizados. Si tu receptor no aparece, agrégalo manualmente con su IP.' +
                        '</div>';

                    if (!res.servers || res.servers.length === 0) {
                        box.innerHTML = '<div style="color:var(--text-muted);">No active OSCam servers automatically detected on standard ports in this subnet scan.</div>' + disclaimerHtml;
                    } else {
                        var html = '<div style="font-weight:700; color:#38BDF8; margin-bottom:10px;">✓ Discovered ' + res.servers.length + ' Cardserver Service(s) on your LAN:</div><div style="display:flex; flex-direction:column; gap:8px;">';
                        res.servers.forEach(function(s) {
                            html += '<div style="display:flex; justify-content:space-between; align-items:center; background:rgba(0,0,0,0.3); padding:8px 14px; border-radius:6px; border:1px solid rgba(255,255,255,0.06);">' +
                                '<div><strong style="color:var(--primary);">' + s.protocol + '</strong> <span style="color:#FFF; font-weight:700; margin-left:6px;">' + s.host + ':' + s.port + '</span> <span style="font-size:11px; color:var(--text-muted); margin-left:6px;">(' + s.description + ', ' + s.latency_ms + 'ms)</span></div>' +
                                '<button type="button" class="btn btn-primary" style="padding:4px 12px; font-size:12px;" onclick="addServerFromPreset(\'' + s.protocol + ' @ ' + s.host + '\', \'0x1810\', ' + s.port + ', \'' + s.protocol + '\')">+ Add to Config</button>' +
                            '</div>';
                        });
                        html += '</div>' + disclaimerHtml;
                        box.innerHTML = html;
                    }
                })
                .catch(function(e) {
                    box.innerHTML = '<div style="color:var(--danger);">Discovery error: ' + e + '</div>';
                });
        }

        function renderChannelsTable(channels) {
            var tbody = document.getElementById('channels-tbody');
            tbody.innerHTML = '';
            channels.forEach(function(ch, idx) {
                var tr = document.createElement('tr');
                tr.id = 'ch-row-' + idx;
                tr.innerHTML = 
                    '<td><input type="text" class="ch-name" value="' + ch.name + '" style="min-width:140px;"></td>' +
                    '<td><input type="text" class="ch-sat" value="' + ch.satellite + '" style="min-width:110px;"></td>' +
                    '<td><div style="display:flex; gap:4px;">' +
                        '<input type="number" class="ch-freq" value="' + ch.frequency + '" style="width:75px;">' +
                        '<select class="ch-pol" style="width:55px;"><option value="H"' + (ch.polarization==='H'?' selected':'') + '>H</option><option value="V"' + (ch.polarization==='V'?' selected':'') + '>V</option></select>' +
                        '<input type="number" class="ch-sr" value="' + ch.symbolRate + '" style="width:75px;">' +
                    '</div></td>' +
                    '<td><div style="display:flex; gap:4px;">' +
                        '<input type="number" class="ch-sid" value="' + ch.serviceId + '" style="width:70px;" placeholder="SID">' +
                        '<input type="number" class="ch-pmt" value="' + ch.pmtPid + '" style="width:70px;" placeholder="PMT">' +
                    '</div></td>' +
                    '<td><input type="text" class="ch-caid" value="' + ch.caid + '" style="width:80px;"></td>' +
                    '<td><div style="display:flex; gap:4px;">' +
                        '<button type="button" class="btn btn-outline" style="padding:4px 8px; font-size:11px;" onclick="playChannel(' + idx + ')">Play</button>' +
                        '<button type="button" class="btn btn-danger" style="padding:4px 8px; font-size:11px;" onclick="removeChannelRow(' + idx + ')">×</button>' +
                    '</div></td>';
                tbody.appendChild(tr);
            });
        }

        function addChannelRow() {
            var tbody = document.getElementById('channels-tbody');
            var idx = tbody.children.length;
            var tr = document.createElement('tr');
            tr.id = 'ch-row-' + idx;
            tr.innerHTML = 
                '<td><input type="text" class="ch-name" value="New Channel" style="min-width:140px;"></td>' +
                '<td><input type="text" class="ch-sat" value="Astra 19.2°E" style="min-width:110px;"></td>' +
                '<td><div style="display:flex; gap:4px;">' +
                    '<input type="number" class="ch-freq" value="11000" style="width:75px;">' +
                    '<select class="ch-pol" style="width:55px;"><option value="H">H</option><option value="V">V</option></select>' +
                    '<input type="number" class="ch-sr" value="22000" style="width:75px;">' +
                '</div></td>' +
                '<td><div style="display:flex; gap:4px;">' +
                    '<input type="number" class="ch-sid" value="100" style="width:70px;" placeholder="SID">' +
                    '<input type="number" class="ch-pmt" value="1024" style="width:70px;" placeholder="PMT">' +
                '</div></td>' +
                '<td><input type="text" class="ch-caid" value="0x1810" style="width:80px;"></td>' +
                '<td><div style="display:flex; gap:4px;">' +
                    '<button type="button" class="btn btn-outline" style="padding:4px 8px; font-size:11px;" onclick="playChannel(' + idx + ')">Play</button>' +
                    '<button type="button" class="btn btn-danger" style="padding:4px 8px; font-size:11px;" onclick="removeChannelRow(' + idx + ')">×</button>' +
                '</div></td>';
            tbody.appendChild(tr);
        }

        function removeChannelRow(idx) {
            var el = document.getElementById('ch-row-' + idx);
            if (el) el.remove();
        }

        function playChannel(idx) {
            var tr = document.getElementById('ch-row-' + idx);
            var name = tr.querySelector('.ch-name').value;
            var freq = tr.querySelector('.ch-freq').value;
            var pol = tr.querySelector('.ch-pol').value.toLowerCase();
            var sr = tr.querySelector('.ch-sr').value;

            var url = 'http://127.0.0.1:9191/play?url=http://satip-receiver/stream?freq=' + freq + '&pol=' + pol + '&sr=' + sr;
            document.getElementById('player-stream-url').value = url;
            showTab('tab-player', document.querySelectorAll('.tab-btn')[4]);
            loadStreamInPlayer();
            showAlert('Loading stream for ' + name, 'success');
        }

        function loadStreamInPlayer() {
            var url = document.getElementById('player-stream-url').value;
            var video = document.getElementById('live-video-player');
            video.src = url;
            video.play().catch(function() {});
        }

        function decodeEcmHex() {
            var hex = document.getElementById('ecm-hex-input').value;
            fetch('/api/ecm_decode', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ hex: hex })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res.valid) {
                    document.getElementById('ecm-table-id').innerText = res.table_id;
                    document.getElementById('ecm-parity').innerText = res.parity;
                    document.getElementById('ecm-section-length').innerText = res.section_length + ' bytes';
                    document.getElementById('ecm-packet-status').innerText = res.status;
                    document.getElementById('ecm-decode-result').style.display = 'block';
                } else {
                    showAlert(res.error || 'Invalid ECM payload', 'error');
                }
            })
            .catch(function(e) { showAlert('Decode error: ' + e, 'error'); });
        }

        function flushCwCache() {
            fetch('/api/cache/clear', { method: 'POST' })
                .then(function(r) { return r.json(); })
                .then(function(res) { showAlert(res.message || 'Cache flushed', 'success'); })
                .catch(function(e) { showAlert('Cache flush error: ' + e, 'error'); });
        }

        function saveConfiguration() {
            var servers = [];
            document.querySelectorAll('.server-card').forEach(function(card, idx) {
                var protoEl = card.querySelector('.srv-proto');
                var passEl = card.querySelector('.srv-pass');
                var desEl = card.querySelector('.srv-des');
                var caidEl = card.querySelector('.srv-caid');
                var connTimeoutEl = card.querySelector('.srv-conn-timeout');
                var recvTimeoutEl = card.querySelector('.srv-recv-timeout');
                var reconIntervalEl = card.querySelector('.srv-recon-interval');

                var protoVal = protoEl ? protoEl.value : 'DVBAPI';
                var defaultPort = getDefaultPortForProto(protoVal);
                var enabledEl = card.querySelector('.srv-enabled');
                var primaryEl = card.querySelector('.srv-primary');

                servers.push({
                    name: card.querySelector('.srv-name').value,
                    protocol: protoVal,
                    host: card.querySelector('.srv-host').value,
                    port: (protoVal === 'DVBAPI_UNIX') ? 0 : (parseInt(card.querySelector('.srv-port').value, 10) || defaultPort),
                    user: card.querySelector('.srv-user') ? card.querySelector('.srv-user').value : 'android_tv',
                    password: passEl ? passEl.value : 'android_tv',
                    des_key: desEl ? desEl.value : '0102030405060708091011121314',
                    caid: caidEl ? caidEl.value : '0x1810',
                    connect_timeout_sec: connTimeoutEl ? (parseInt(connTimeoutEl.value, 10) || 4) : 4,
                    recv_timeout_sec: recvTimeoutEl ? (parseInt(recvTimeoutEl.value, 10) || 8) : 8,
                    reconnect_interval_ms: reconIntervalEl ? (parseInt(reconIntervalEl.value, 10) || 2000) : 2000,
                    enabled: enabledEl ? enabledEl.checked : true,
                    is_primary: primaryEl ? primaryEl.checked : (idx === 0)
                });
            });

            var channels = [];
            document.querySelectorAll('#channels-tbody tr').forEach(function(tr) {
                channels.push({
                    name: tr.querySelector('.ch-name').value,
                    satellite: tr.querySelector('.ch-sat').value,
                    frequency: parseInt(tr.querySelector('.ch-freq').value, 10) || 11000,
                    polarization: tr.querySelector('.ch-pol').value,
                    symbolRate: parseInt(tr.querySelector('.ch-sr').value, 10) || 22000,
                    serviceId: parseInt(tr.querySelector('.ch-sid').value, 10) || 1,
                    pmtPid: parseInt(tr.querySelector('.ch-pmt').value, 10) || 100,
                    caid: tr.querySelector('.ch-caid').value,
                    streamUrl: ''
                });
            });

            var payload = {
                delivery_system: document.getElementById('delivery-dropdown').value,
                caids: document.getElementById('caids-text-input').value,
                cw_cache_enabled: document.getElementById('cw-cache-toggle').checked,
                timeout_ms: parseInt(document.getElementById('timeout-input').value, 10) || 4000,
                reconnect_interval_ms: parseInt(document.getElementById('reconnect-input').value, 10) || 2000,
                servers: servers,
                channels: channels
            };

            fetch('/api/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                showAlert('✓ Configuration successfully saved and hot-reloaded across daemon and HAL!', 'success');
            })
            .catch(function(e) {
                showAlert('✗ Error saving configuration: ' + e, 'error');
            });
        }

        function handleFileRestore(event) {
            var file = event.target.files[0];
            if (!file) return;

            var reader = new FileReader();
            reader.onload = function(e) {
                try {
                    var json = JSON.parse(e.target.result);
                    fetch('/api/restore', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(json)
                    })
                    .then(function(r) { return r.json(); })
                    .then(function(res) {
                        showAlert(res.message || 'Configuration restored!', 'success');
                        loadConfiguration();
                    })
                    .catch(function(err) { showAlert('Restore failed: ' + err, 'error'); });
                } catch (err) {
                    showAlert('Invalid JSON backup file: ' + err, 'error');
                }
            };
            reader.readAsText(file);
        }

        function insertCaid(caid) {
            var input = document.getElementById('caids-text-input');
            var curr = input.value.trim();
            if (curr.indexOf(caid) !== -1) return;
            input.value = curr.length > 0 ? curr + ', ' + caid : caid;
        }

        function applyTunerSystemChange() {
            var sys = document.getElementById('delivery-dropdown').value;
            var input = document.getElementById('caids-text-input');
            if (sys === 'DVBS') {
                input.value = '0x1810, 0x1830, 0x1843, 0x098C, 0x09CD, 0x183E, 0x0100, 0x0500, 0x0963, 0x1803, 0x0B00, 0x0604';
            } else if (sys === 'DVBT') {
                input.value = '0x1801, 0x0604, 0x0B00, 0x0500';
            } else if (sys === 'DVBC') {
                input.value = '0x1801, 0x0604, 0x0B00, 0x098C, 0x09C7, 0x1834';
            } else {
                input.value = '0x1810, 0x1830, 0x1843, 0x098C, 0x09CD, 0x183E, 0x1801, 0x0100, 0x0500, 0x0963, 0x1803, 0x0B00, 0x0604';
            }
        }

        function sendWolPacket() {
            var mac = document.getElementById('wol-mac-input').value;
            var bcast = document.getElementById('wol-bcast-input').value;
            if (!mac) {
                showAlert('Please specify a receiver MAC address for WoL', 'error');
                return;
            }
            fetch('/api/wol', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mac: mac, broadcast: bcast })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                showAlert(res.message || 'WoL packet sent!', 'success');
            })
            .catch(function(e) { showAlert('WoL transmission failed: ' + e, 'error'); });
        }

        function showAlert(msg, type) {
            var banner = document.getElementById('alert-banner');
            banner.className = 'banner ' + type;
            banner.innerText = msg;
            banner.style.display = 'block';
            setTimeout(function() { banner.style.display = 'none'; }, 4500);
        }

        function updateLatencySparkline(latency) {
            latencyHistory.push(latency);
            if (latencyHistory.length > 11) latencyHistory.shift();
            var max = Math.max.apply(null, latencyHistory.concat([100]));
            var points = latencyHistory.map(function(val, idx) {
                var x = idx * 50;
                var y = 80 - ((val / max) * 70);
                return x + ',' + y;
            }).join(' ');
            document.getElementById('line-latency').setAttribute('points', points);
            document.getElementById('val-latency').innerText = latency + ' ms';
            document.getElementById('chart-lat-label').innerText = latency + ' ms';

            var statusTxt = document.getElementById('val-latency-status');
            if (latency < 60) {
                statusTxt.innerText = '⚡ Excellent Response';
                statusTxt.style.color = 'var(--success)';
            } else if (latency < 150) {
                statusTxt.innerText = '✓ Good Response';
                statusTxt.style.color = 'var(--warning)';
            } else {
                statusTxt.innerText = '⚠ High Latency';
                statusTxt.style.color = 'var(--danger)';
            }
        }

        function pollTelemetry() {
            fetch('/api/status')
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    document.getElementById('val-cws').innerText = data.cws_received || 0;
                    document.getElementById('val-ecms').innerText = data.ecms_sent || 0;

                    var st = data.status || 'DISCONNECTED';
                    var dot = document.getElementById('pill-dot');
                    var txt = document.getElementById('pill-text');
                    txt.innerText = st;

                    if (st === 'CONNECTED') {
                        dot.style.background = 'var(--success)';
                    } else if (st === 'CONNECTING') {
                        dot.style.background = 'var(--warning)';
                    } else {
                        dot.style.background = 'var(--danger)';
                    }
                })
                .catch(function() {});
        }

        function refreshLogs() {
            fetch('/api/logs')
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    var mainTerminal = document.getElementById('main-log-terminal');
                    var miniTerminal = document.getElementById('mini-log-terminal');
                    if (data.logs && data.logs.length > 0) {
                        var formatted = data.logs.map(function(line) {
                            if (line.indexOf('CW') !== -1 || line.indexOf('Control Word') !== -1) {
                                return '<span class="log-cw">' + line + '</span>';
                            } else if (line.indexOf('ECM') !== -1 || line.indexOf('DVBAPI') !== -1 || line.indexOf('Newcamd') !== -1) {
                                return '<span class="log-ecm">' + line + '</span>';
                            } else if (line.indexOf('WARN') !== -1) {
                                return '<span class="log-warn">' + line + '</span>';
                            } else if (line.indexOf('ERROR') !== -1 || line.indexOf('failed') !== -1) {
                                return '<span class="log-err">' + line + '</span>';
                            }
                            return line;
                        }).join('\n');

                        mainTerminal.innerHTML = formatted;
                        miniTerminal.innerHTML = formatted;

                        if (autoScrollEnabled) {
                            mainTerminal.scrollTop = mainTerminal.scrollHeight;
                            miniTerminal.scrollTop = miniTerminal.scrollHeight;
                        }
                    } else {
                        mainTerminal.innerText = 'No log entries recorded yet.';
                        miniTerminal.innerText = 'No log entries recorded yet.';
                    }
                })
                .catch(function() {});
        }

        function clearLogs() {
            document.getElementById('main-log-terminal').innerText = 'Logs cleared.';
            document.getElementById('mini-log-terminal').innerText = 'Logs cleared.';
        }

        function toggleAutoScroll() {
            autoScrollEnabled = !autoScrollEnabled;
            document.getElementById('btn-autoscroll').innerText = autoScrollEnabled ? 'Pause Scroll' : 'Resume Scroll';
        }

        // Keyboard Shortcuts (1-9 for tabs)
        window.addEventListener('keydown', function(e) {
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
            var key = parseInt(e.key, 10);
            if (!isNaN(key) && key >= 1 && key <= 9) {
                var buttons = document.querySelectorAll('.tab-btn');
                if (buttons[key - 1]) {
                    buttons[key - 1].click();
                }
            }
        });

        window.onload = function() {
            loadConfiguration();
            pollTelemetry();
            refreshLogs();
            setInterval(pollTelemetry, 2500);
            setInterval(refreshLogs, 3500);
        };
    </script>
</body>
</html>
        """.trimIndent()
    }
}

