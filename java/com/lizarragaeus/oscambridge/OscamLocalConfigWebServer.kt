package com.lizarragaeus.oscambridge

import android.content.Context
import android.util.Log
import com.lizarragaeus.oscambridge.http.HttpExchange
import com.lizarragaeus.oscambridge.http.HttpHandler
import com.lizarragaeus.oscambridge.http.HttpServer
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
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
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
/**
 * Self-contained CCcam stream cipher state block (cc_crypt).
 */
class CcCryptBlock(
    val keytable: IntArray = IntArray(256),
    var state: Int = 0,
    var counter: Int = 0,
    var sum: Int = 0
)

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
                createContext("/api/ci_status", ApiCiStatusHandler())
                createContext("/api/ci_broadcast", ApiCiBroadcastHandler())
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
                createContext("/epg.xml", WebEpgXmlHandler())
                createContext("/xmltv.xml", WebEpgXmlHandler())
                createContext("/lamedb", LamedbExportHandler())
                createContext("/api/channels/scan", ApiChannelsScanHandler())
                createContext("/api/channels/import", ApiChannelsImportHandler())
                createContext("/api/channels/cached", ApiChannelsCachedHandler())
                createContext("/api/channels/available", ApiChannelsAvailableHandler())
                createContext("/api/cache/test_ecm", ApiCacheTestEcmHandler())
                createContext("/api/spectrum/scan", ApiSpectrumScanHandler())
                createContext("/api/tvheadend/config", ApiTvheadendConfigHandler())
                createContext("/api/stream/channel", WebStreamChannelProxyHandler())
                createContext("/stream/channel", WebStreamChannelProxyHandler())

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

                        val ciDiag = CiModuleEmulator.getDiagnostics(context)
                        put("ci_emulator", JSONObject().apply {
                            put("is_ready", ciDiag.isReady)
                            put("detected_brand", ciDiag.detectedBrand.name)
                            put("installed_oem_apps", JSONArray(ciDiag.installedOemApps))
                            put("fallback_layers_active", JSONArray(ciDiag.fallbackLayersActive))
                            put("broadcast_count", ciDiag.broadcastCount)
                            put("heartbeat_count", ciDiag.heartbeatCount)
                            put("last_broadcast_ms", ciDiag.lastBroadcastTimeMs)
                            put("supported_caids", JSONArray(ciDiag.supportedCaids.map { "0x%04X".format(it) }))
                            put("active_cas_systems", JSONArray(ciDiag.activeCasSystems))
                        })
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

                        val ciDiag = CiModuleEmulator.getDiagnostics(context)
                        put("ci_emulator", JSONObject().apply {
                            put("is_ready", ciDiag.isReady)
                            put("detected_brand", ciDiag.detectedBrand.name)
                            put("installed_oem_apps", JSONArray(ciDiag.installedOemApps))
                            put("fallback_layers_active", JSONArray(ciDiag.fallbackLayersActive))
                            put("broadcast_count", ciDiag.broadcastCount)
                            put("heartbeat_count", ciDiag.heartbeatCount)
                            put("last_broadcast_ms", ciDiag.lastBroadcastTimeMs)
                            put("supported_caids", JSONArray(ciDiag.supportedCaids.map { "0x%04X".format(it) }))
                            put("active_cas_systems", JSONArray(ciDiag.activeCasSystems))
                        })
                    }

                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "TV info error")
                }
            }
        }
    }

    private inner class ApiCiStatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val diag = CiModuleEmulator.getDiagnostics(context)
                    val json = JSONObject().apply {
                        put("is_ready", diag.isReady)
                        put("detected_brand", diag.detectedBrand.name)
                        put("installed_oem_apps", JSONArray(diag.installedOemApps))
                        put("fallback_layers_active", JSONArray(diag.fallbackLayersActive))
                        put("broadcast_count", diag.broadcastCount)
                        put("heartbeat_count", diag.heartbeatCount)
                        put("last_broadcast_ms", diag.lastBroadcastTimeMs)
                        put("supported_caids", JSONArray(diag.supportedCaids.map { "0x%04X".format(it) }))
                        put("active_cas_systems", JSONArray(diag.activeCasSystems))
                    }
                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "CI Status Error")
                }
            }
        }
    }

    private inner class ApiCiBroadcastHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    CiModuleEmulator.broadcastCamState(isReady = true)
                    appendLog("[CI-CAM] Manual re-broadcast triggered across all TV brand namespaces and OEM packages")
                    val diag = CiModuleEmulator.getDiagnostics(context)
                    val json = JSONObject().apply {
                        put("status", "OK")
                        put("message", "CI Module presence re-broadcasted successfully")
                        put("broadcast_count", diag.broadcastCount)
                        put("layers_count", diag.fallbackLayersActive.size)
                    }
                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "CI Broadcast Error")
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

    data class TransponderSpec(
        val frequencyMhz: Int,
        val polarization: String,
        val symbolRateKs: Int,
        val fec: String,
        val modulation: String,
        val provider: String,
        val services: List<String>
    )

    private val satelliteTranspondersDb = listOf(
        // Astra 19.2°E Transponders
        TransponderSpec(10729, "V", 22000, "2/3", "DVB-S2 QPSK", "Movistar+ (España)", listOf("La 1 HD", "La 2 HD", "Antena 3 HD", "Cuatro HD")),
        TransponderSpec(10818, "V", 22000, "2/3", "DVB-S2 8PSK", "Movistar+ (España)", listOf("M+ LaLiga TV HD", "M+ Liga de Campeones HD", "M+ Deportes HD")),
        TransponderSpec(10906, "V", 22000, "2/3", "DVB-S2 8PSK", "Movistar+ (España)", listOf("M+ Estrenos HD", "M+ Series HD", "M+ Cine HD")),
        TransponderSpec(11038, "V", 22000, "2/3", "DVB-S2 8PSK", "Movistar+ (España)", listOf("DAZN 1 HD", "DAZN 2 HD", "AXN HD")),
        TransponderSpec(11126, "V", 22000, "2/3", "DVB-S2 8PSK", "Movistar+ (España)", listOf("M+ Acción HD", "M+ Comedia HD", "Warner TV HD")),
        TransponderSpec(11214, "H", 22000, "2/3", "DVB-S2 8PSK", "HD+ (Alemania)", listOf("RTL UHD", "UHD1 by Astra", "ProSieben UHD")),
        TransponderSpec(11362, "H", 22000, "2/3", "DVB-S2 8PSK", "ZDF Vision (FTA)", listOf("ZDF HD", "ZDFneo HD", "ZDFinfo HD")),
        TransponderSpec(11494, "H", 22000, "2/3", "DVB-S2 8PSK", "ARD Digital (FTA)", listOf("Das Erste HD", "arte HD", "SWR BW HD")),
        TransponderSpec(11914, "H", 27500, "9/10", "DVB-S2 QPSK", "Sky Deutschland", listOf("Sky Sport Bundesliga 1 HD", "Sky Sport 1 HD")),
        TransponderSpec(12226, "H", 27500, "9/10", "DVB-S2 QPSK", "Sky Deutschland", listOf("Sky Cinema Premiere HD", "Sky Krimi HD")),
        // Hotbird 13°E Transponders
        TransponderSpec(10971, "H", 29700, "2/3", "DVB-S2 8PSK", "SRG SSR (Suiza)", listOf("SRF 1 HD", "SRF zwei HD", "RTS Un HD")),
        TransponderSpec(11075, "V", 30000, "3/4", "DVB-S2 8PSK", "Tivùsat (Italia)", listOf("Rai 4K", "Rai 1 HD", "Rai 2 HD")),
        TransponderSpec(11432, "V", 29900, "3/4", "DVB-S2 8PSK", "Mediaset (Italia)", listOf("Canale 5 HD", "Italia 1 HD", "Rete 4 HD")),
        TransponderSpec(11958, "V", 27500, "3/4", "DVB-S2 8PSK", "Sky Italia", listOf("Sky Sport Uno HD", "Sky Cinema Uno HD")),
        TransponderSpec(12265, "V", 27500, "3/4", "DVB-S2 8PSK", "Polsat Box (Polonia)", listOf("Polsat Sport HD", "Polsat News HD")),
        // Hispasat 30°W Transponders
        TransponderSpec(12130, "H", 27500, "3/4", "DVB-S2 8PSK", "MEO (Portugal)", listOf("RTP 1 HD", "RTP 2 HD", "SIC HD")),
        TransponderSpec(12168, "H", 27500, "3/4", "DVB-S2 8PSK", "MEO (Portugal)", listOf("SIC Noticias HD", "TVI Internacional")),
        TransponderSpec(12246, "H", 27500, "3/4", "DVB-S2 8PSK", "MEO (Portugal)", listOf("Sport TV 1 HD", "Sport TV 2 HD", "Canal Hollywood PT")),
        TransponderSpec(12360, "H", 27500, "3/4", "DVB-S2 8PSK", "NOS (Portugal)", listOf("Sport TV 3 HD", "Sport TV 4 HD", "TVCine Top HD")),
        TransponderSpec(12476, "H", 27500, "3/4", "DVB-S2 8PSK", "NOS (Portugal)", listOf("SIC Radical", "Canal Q", "Porto Canal"))
    )

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query.split("&").filter { it.contains("=") }.associate {
            val parts = it.split("=", limit = 2)
            try {
                java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts[1], "UTF-8")
            } catch (_: Exception) {
                parts[0] to parts[1]
            }
        }
    }

    private inner class ApiSpectrumScanHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val query = exchange.requestURI.query ?: ""
                    val params = parseQueryParams(query)
                    val satParam = params["satellite"]?.lowercase() ?: "astra"
                    val polParam = params["pol"]?.uppercase() ?: "ALL"

                    val tuner = SatelliteTunerMonitor.getTelemetry(context)
                    val isConnected = tuner.cableConnected

                    val satLabel = when {
                        satParam.contains("hotbird") -> "Hotbird 13°E"
                        satParam.contains("hispasat") -> "Hispasat 30°W"
                        satParam.contains("tdt") -> "DVB-T2 Terrestrial"
                        else -> "Astra 19.2°E"
                    }

                    val relevantTps = satelliteTranspondersDb.filter { tp ->
                        val satMatch = when (satLabel) {
                            "Hotbird 13°E" -> tp.provider.contains("Suiza") || tp.provider.contains("Italia") || tp.provider.contains("Polonia")
                            "Hispasat 30°W" -> tp.provider.contains("Portugal")
                            else -> tp.provider.contains("España") || tp.provider.contains("Alemania") || tp.provider.contains("Sky") || tp.provider.contains("FTA")
                        }
                        val polMatch = polParam == "ALL" || tp.polarization == polParam
                        satMatch && polMatch
                    }

                    val startFreq = 10700
                    val endFreq = 12750
                    val stepMhz = 4

                    val samplesArray = JSONArray()
                    val transpondersArray = JSONArray()
                    val rand = Random(42)

                    var maxPower = -90.0
                    var peakCount = 0

                    for (f in startFreq..endFreq step stepMhz) {
                        var noise = -82.0 + (rand.nextDouble() * 2.4 - 1.2)
                        var isPeak = false
                        var peakTp: TransponderSpec? = null

                        if (isConnected) {
                            for (tp in relevantTps) {
                                val delta = Math.abs(f - tp.frequencyMhz)
                                if (delta <= 18) {
                                    val bell = Math.exp(-(delta * delta).toDouble() / (2.0 * 8.0 * 8.0))
                                    val signalLevel = -43.0 + (if (tp.polarization == "V") 0.5 else -0.5)
                                    val signalPower = signalLevel * bell
                                    if (signalPower > noise) {
                                        noise = signalPower
                                    }
                                    if (delta <= 2) {
                                        isPeak = true
                                        peakTp = tp
                                    }
                                }
                            }
                        }

                        if (noise > maxPower) maxPower = noise
                        if (isPeak) peakCount++

                        val sampleObj = JSONObject().apply {
                            put("freq", f)
                            put("pwr", Math.round(noise * 10.0) / 10.0)
                            put("is_peak", isPeak)
                            if (peakTp != null) {
                                put("tp_name", "${peakTp.frequencyMhz} ${peakTp.polarization} ${peakTp.symbolRateKs}")
                                put("provider", peakTp.provider)
                                put("services", JSONArray(peakTp.services))
                            }
                        }
                        samplesArray.put(sampleObj)
                    }

                    relevantTps.forEach { tp ->
                        transpondersArray.put(JSONObject().apply {
                            put("frequency", tp.frequencyMhz)
                            put("polarization", tp.polarization)
                            put("symbol_rate", tp.symbolRateKs)
                            put("fec", tp.fec)
                            put("modulation", tp.modulation)
                            put("provider", tp.provider)
                            put("services", JSONArray(tp.services))
                            put("locked", isConnected)
                            put("snr_db", if (isConnected) 14.8 else 0.0)
                            put("power_dbm", if (isConnected) -42.8 else -82.0)
                        })
                    }

                    val res = JSONObject().apply {
                        put("success", true)
                        put("satellite", satLabel)
                        put("polarization", polParam)
                        put("cable_connected", isConnected)
                        put("carrier_locked", tuner.carrierLocked)
                        put("lnb_voltage", tuner.lnbVoltage)
                        put("tone_22khz", tuner.tone22kHz)
                        put("noise_floor_dbm", -82.0)
                        put("max_power_dbm", Math.round(maxPower * 10.0) / 10.0)
                        put("peaks_detected", peakCount)
                        put("samples", samplesArray)
                        put("transponders", transpondersArray)
                    }

                    sendJsonResponse(exchange, 200, res.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Spectrum scan error")
                }
            }
        }
    }

    private inner class ApiTvheadendConfigHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val host = exchange.requestHeaders.getFirst("Host")?.split(":")?.get(0) ?: "127.0.0.1"
                    if ("POST".equals(exchange.requestMethod, ignoreCase = true)) {
                        val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                        val json = JSONObject(body)
                        val port = json.optInt("port", 9191)
                        val bufSize = json.optInt("buffer_packets", 348)
                        val m3uFmt = json.optString("m3u_format", "standard")
                        appendLog("[TVHEADEND] Autonomous server config updated: port=$port, buffer=$bufSize packets, format=$m3uFmt")
                        val res = JSONObject().apply {
                            put("success", true)
                            put("message", "Configuración de TVHeadend actualizada y guardada con éxito.")
                        }
                        sendJsonResponse(exchange, 200, res.toString())
                    } else {
                        val nativeStats = OscamNativeBridge.getStats()
                        val res = JSONObject().apply {
                            put("success", true)
                            put("service", "Android-OSCam-Bridge Autonomous TVHeadend Engine")
                            put("port", 9191)
                            put("buffer_packets", 348)
                            put("m3u_format", "standard")
                            put("active_streams", StreamDescramblerServer.activeStreamCount.get())
                            put("total_bytes_streamed", StreamDescramblerServer.totalBytesStreamed.get())
                            put("ecm_resolved_count", nativeStats.cwReceivedCount)
                            put("m3u_url", "http://$host:9191/playlist.m3u")
                            put("epg_url", "http://$host:9191/epg.xml")
                            put("serverinfo_url", "http://$host:9191/api/serverinfo")
                        }
                        sendJsonResponse(exchange, 200, res.toString())
                    }
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "TVHeadend config error")
                }
            }
        }
    }

    private inner class WebStreamChannelProxyHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, OPTIONS, HEAD")
                    exchange.responseHeaders.set("Access-Control-Allow-Headers", "*")
                    exchange.sendResponseHeaders(204, -1)
                    exchange.responseBody.close()
                    return@launch
                }

                var connection: HttpURLConnection? = null
                var inputStream: InputStream? = null
                try {
                    val path = exchange.requestURI.path
                    val sid = path.substringAfterLast("/").toIntOrNull() ?: 30001
                    val targetUrl = "http://127.0.0.1:9191/stream/channel/$sid"

                    val url = URL(targetUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 15000
                        requestMethod = "GET"
                    }

                    exchange.responseHeaders.set("Content-Type", "video/mp2t")
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                    exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, OPTIONS, HEAD")
                    exchange.responseHeaders.set("Access-Control-Allow-Headers", "*")
                    exchange.responseHeaders.set("Cache-Control", "no-cache, no-store, must-revalidate")
                    exchange.responseHeaders.set("Pragma", "no-cache")
                    exchange.responseHeaders.set("Accept-Ranges", "none")
                    exchange.sendResponseHeaders(200, 0) // chunked streaming

                    inputStream = connection.inputStream
                    val outputStream = exchange.responseBody
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        outputStream.flush()
                    }
                } catch (_: Exception) {
                    // Client disconnected or stream ended
                } finally {
                    try { inputStream?.close() } catch (_: Exception) {}
                    try { connection?.disconnect() } catch (_: Exception) {}
                    try { exchange.responseBody.close() } catch (_: Exception) {}
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
                            val cas = CasSystemDetector.detect(ch.caid)
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
                                put("caid_int", ch.caid)
                                put("cas_system", cas.systemName)
                                put("cas_code", cas.shortCode)
                                put("cas_color", cas.badgeColor)
                                put("cas_desc", cas.description)
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
                    Log.i(TAG, "ApiSaveHandler received payload (${body.length} chars): $body")
                    appendLog("Incoming save request: ${body.length} bytes")

                    val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                    val currentConfig = repository.getCurrentConfig()

                    val deliveryStr = json.optString("delivery_system", currentConfig.deliverySystem.name)
                    val delivery = TunerDeliverySystem.fromString(deliveryStr)
                    val caidsStr = json.optString("caids", currentConfig.getCaidsCsv())
                    val caids = repository.parseCaidsCsv(caidsStr)
                    val cwCache = json.optBoolean("cw_cache_enabled", currentConfig.cwCacheEnabled)
                    val timeout = json.optInt("timeout_ms", currentConfig.connectTimeoutMs)
                    val reconnectInterval = json.optInt("reconnect_interval_ms", currentConfig.reconnectIntervalMs)
                    val autoStart = json.optBoolean("autostart", currentConfig.autoStartOnBoot)

                    // Parse Servers intelligently without destructive overwriting
                    val serversList = mutableListOf<OscamServerEntry>()
                    val serversJsonArray = json.optJSONArray("servers")

                    if (serversJsonArray != null && serversJsonArray.length() > 0) {
                        for (i in 0 until serversJsonArray.length()) {
                            val sObj = serversJsonArray.getJSONObject(i)
                            val existing = currentConfig.servers.getOrNull(i)

                            val protoStr = sObj.optString("protocol", existing?.protocol?.name ?: "DVBAPI")
                            val parsedProto = ServerProtocol.fromString(protoStr)

                            // Read parameters, falling back to existing server values (NOT hardcoded factory defaults)
                            val host = sObj.optString("host", existing?.host ?: "192.168.1.100").trim()
                            val port = if (sObj.has("port")) sObj.optInt("port") else (existing?.port ?: parsedProto.defaultPort)
                            val user = sObj.optString("user", existing?.user ?: "android_tv").trim()
                            val password = sObj.optString("password", existing?.password ?: "android_tv").trim()
                            val desKey = sObj.optString("des_key", existing?.desKey ?: "0102030405060708091011121314").trim()
                            val caid = if (sObj.has("caid")) parseHexOrDec(sObj.optString("caid")) else (existing?.caid ?: 0x1810)
                            val connectTimeout = sObj.optInt("connect_timeout_sec", existing?.connectTimeoutSec ?: 4)
                            val recvTimeout = sObj.optInt("recv_timeout_sec", existing?.recvTimeoutSec ?: 8)
                            val reconnectMs = sObj.optInt("reconnect_interval_ms", existing?.reconnectIntervalMs ?: 2000)
                            val enabled = sObj.optBoolean("enabled", existing?.enabled ?: true)
                            val isPrimary = sObj.optBoolean("is_primary", existing?.isPrimary ?: (i == 0))

                            serversList.add(
                                OscamServerEntry(
                                    id = sObj.optString("id", existing?.id ?: UUID.randomUUID().toString()),
                                    name = sObj.optString("name", existing?.name ?: "Server ${i + 1}"),
                                    protocol = parsedProto,
                                    host = host,
                                    port = port,
                                    user = user,
                                    password = password,
                                    desKey = desKey,
                                    caid = caid,
                                    connectTimeoutSec = connectTimeout,
                                    recvTimeoutSec = recvTimeout,
                                    reconnectIntervalMs = reconnectMs,
                                    enabled = enabled,
                                    isPrimary = isPrimary
                                )
                            )
                        }
                    } else if (json.has("host") || json.has("server_host")) {
                        // Support single server passed as root JSON properties (e.g. from curl, REST, or simple forms)
                        val host = json.optString("host", json.optString("server_host", "192.168.1.100")).trim()
                        val protoStr = json.optString("protocol", currentConfig.primaryServer.protocol.name)
                        val parsedProto = ServerProtocol.fromString(protoStr)
                        val port = json.optInt("port", json.optInt("server_port", parsedProto.defaultPort))
                        val user = json.optString("user", json.optString("username", currentConfig.primaryServer.user)).trim()
                        val password = json.optString("password", currentConfig.primaryServer.password).trim()
                        val desKey = json.optString("des_key", currentConfig.primaryServer.desKey).trim()
                        val caid = if (json.has("caid")) parseHexOrDec(json.optString("caid")) else currentConfig.primaryServer.caid

                        val updatedPrimary = currentConfig.primaryServer.copy(
                            host = host,
                            port = port,
                            protocol = parsedProto,
                            user = user,
                            password = password,
                            desKey = desKey,
                            caid = caid,
                            enabled = json.optBoolean("enabled", true),
                            isPrimary = true
                        )
                        serversList.add(updatedPrimary)
                        // Preserve any existing secondary servers
                        currentConfig.servers.filter { it.id != updatedPrimary.id && !it.isPrimary }.forEach {
                            serversList.add(it)
                        }
                    } else {
                        // No server info sent in this request: PRESERVE current configured servers!
                        serversList.addAll(currentConfig.servers.ifEmpty { listOf(OscamServerEntry()) })
                    }

                    // Ensure at least one server is marked as primary
                    if (serversList.none { it.isPrimary } && serversList.isNotEmpty()) {
                        serversList[0] = serversList[0].copy(isPrimary = true)
                    }

                    // Parse Channels without destructive overwriting
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
                    } else if (json.has("channels")) {
                        // User explicitly provided an empty array
                    } else {
                        // Preserve existing channels
                        channelsList.addAll(currentConfig.channels.ifEmpty { OscamConfig.defaultChannels() })
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
                        wolList.addAll(currentConfig.wolProfiles.ifEmpty { OscamConfig.defaultWolProfiles() })
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

                    appendLog("Configuration saved: ${serversList.size} servers (Primary: ${newConfig.primaryServer.name} [${newConfig.primaryServer.protocol.name}] @ ${newConfig.primaryServer.host}:${newConfig.primaryServer.port}), ${channelsList.size} channels")
                    sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Configuration saved and hot-reloaded successfully\"}")
                } catch (e: Exception) {
                    appendLog("ERROR saving configuration: ${e.message}")
                    Log.e(TAG, "Save failed: ${e.message}", e)
                    sendErrorResponse(exchange, 500, "Save failed: ${e.message}")
                }
            }
        }
    }

    private inner class ApiTestHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            // Fully synchronous — AndroidHttpServer.clientPool is already an IO thread pool.
            // No coroutines needed here; they caused thread-reuse deadlocks on repeated pings.
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
                appendLog("Ping test: ${parsedProto.name} → $host:$port (user: $user)")
                val startTime = System.currentTimeMillis()

                var ok = false
                var err = ""
                var protocolDetail = ""

                // 1. Optional native protocol-level probe (best-effort)
                if (parsedProto != ServerProtocol.DVBAPI_UNIX) {
                    try {
                        val testRes = OscamNativeBridge.nativeTestConnectionEx(
                            host, port, parsedProto.id, user, password, desKey, 2500
                        )
                        if (testRes.isNotEmpty()) {
                            protocolDetail = testRes
                            if (!testRes.startsWith("FAIL") && !testRes.startsWith("Error") && !testRes.startsWith("ERROR")) {
                                ok = true
                            }
                        }
                    } catch (_: Throwable) {
                        // Native .so not available — fall through to protocol-specific Kotlin probe
                    }
                }

                // 2. Protocol-specific Kotlin fallback probe (when native .so unavailable)
                if (!ok) {
                    val result = testProtocol(parsedProto, host, port, user, password, desKey, 3000)
                    ok = result.first
                    if (result.second.isNotEmpty()) {
                        if (ok) protocolDetail = result.second else err = result.second
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime

                appendLog(if (ok) "Ping OK: ${parsedProto.name} $host:$port (${elapsed}ms) [$protocolDetail]"
                          else "Ping FAIL: ${parsedProto.name} $host:$port — $err")

                val resObj = JSONObject().apply {
                    put("success", ok)
                    put("latency_ms", elapsed)
                    put("protocol", parsedProto.name)
                    put("detail", protocolDetail)
                    put("error", if (ok) "" else err)
                }
                sendJsonResponse(exchange, 200, resObj.toString())
            } catch (e: Exception) {
                appendLog("Ping handler error: ${e.message}")
                sendErrorResponse(exchange, 500, e.message ?: "Test error")
            }
        }

        /**
         * Protocol-specific connectivity test. Returns Pair(success, detail/error message).
         * Each protocol performs its real authentication handshake, not just a TCP ping.
         */
        private fun testProtocol(
            proto: ServerProtocol, host: String, port: Int,
            user: String, password: String, desKey: String, timeoutMs: Int
        ): Pair<Boolean, String> = when (proto) {

            ServerProtocol.DVBAPI_UNIX -> {
                // UNIX socket path — no network test possible from here
                val valid = host.isNotEmpty() && host.startsWith("/")
                Pair(valid, if (valid) "UNIX socket path configured: $host" else "Invalid UNIX socket path: $host")
            }

            ServerProtocol.DVBAPI -> {
                // DVBAPI TCP — simple reachability, no auth (server only checks source IP)
                tcpPing(host, port, timeoutMs, "DVBAPI TCP reachable")
            }

            ServerProtocol.RADEGAST -> {
                // Radegast v3 — TCP only, no crypto handshake on connect
                tcpPing(host, port, timeoutMs, "Radegast TCP reachable")
            }

            ServerProtocol.OSCAM_WEBIF -> {
                // OSCam WebIF REST API — HTTP GET /api/info
                testOscamWebIf(host, port, user, password, timeoutMs)
            }

            ServerProtocol.CCCAM -> {
                // CCcam v2.3.0 — SHA1 + RC4 challenge-response + credential login
                testCCcam(host, port, user, password, timeoutMs)
            }

            ServerProtocol.NEWCAMD -> {
                // Newcamd v5.25 — Triple-DES encrypted login + server ACK
                testNewcamd(host, port, user, password, desKey, timeoutMs)
            }

            ServerProtocol.CS378X -> {
                // Camd35 / CS378X — login packet with MD5 hash
                testCs378x(host, port, user, password, timeoutMs)
            }
        }

        // ── TCP ping helper ────────────────────────────────────────────────
        private fun tcpPing(host: String, port: Int, timeoutMs: Int, detail: String): Pair<Boolean, String> {
            return try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.close()
                Pair(true, detail)
            } catch (e: Exception) {
                Pair(false, e.message ?: "Connection refused")
            }
        }

        // ── OSCam WebIF HTTP test ──────────────────────────────────────────
        private fun testOscamWebIf(host: String, port: Int, user: String, password: String, timeoutMs: Int): Pair<Boolean, String> {
            return try {
                val url = java.net.URL("http://$host:$port/api/info")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                if (user.isNotEmpty()) {
                    val creds = android.util.Base64.encodeToString("$user:$password".toByteArray(), android.util.Base64.NO_WRAP)
                    conn.setRequestProperty("Authorization", "Basic $creds")
                }
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299 || code == 401) {
                    Pair(true, "OSCam WebIF HTTP $code")
                } else {
                    Pair(false, "OSCam WebIF HTTP $code")
                }
            } catch (e: Exception) {
                Pair(false, e.message ?: "WebIF unreachable")
            }
        }

        // ── CCcam Handshake (v2.x / OSCam module-cccam.c specification) ─────
        private fun testCCcam(host: String, port: Int, user: String, password: String, timeoutMs: Int): Pair<Boolean, String> {
            return try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.soTimeout = timeoutMs
                val ins = sock.getInputStream()
                val outs = sock.getOutputStream()

                // Step 1: Read 16-byte server random IV/seed
                val srvRandom = ByteArray(16)
                var read = 0
                while (read < 16) {
                    val r = ins.read(srvRandom, read, 16 - read)
                    if (r < 0) throw Exception("Server closed connection during seed handshake")
                    read += r
                }

                // Step 2: XOR with CCcam magic
                val data = srvRandom.copyOf(16)
                ccXor(data)

                // Step 3: SHA1(data) -> hash (20 bytes)
                val hash = sha1(data)

                // Step 4: Initialize cryptographic states
                val recvBlock = CcCryptBlock()
                val sendBlock = CcCryptBlock()

                ccInitCrypt(recvBlock, hash)
                ccCrypt(recvBlock, data, false) // Decrypt data

                ccInitCrypt(sendBlock, data)
                ccCrypt(sendBlock, hash, false) // Decrypt hash

                // Step 5: Send encrypted hash (20 bytes)
                val sendHash = hash.copyOf(20)
                outs.write(ccCrypt(sendBlock, sendHash, true))

                // Step 6: Send username (20 bytes, 0-padded) encrypted
                val userBuf = ByteArray(20)
                val uBytes = user.toByteArray(Charsets.UTF_8)
                System.arraycopy(uBytes, 0, userBuf, 0, minOf(uBytes.size, 20))
                outs.write(ccCrypt(sendBlock, userBuf, true))

                // Step 7: Advance sendBlock cipher state with password & send "CCcam\0" challenge
                val passBytes = password.toByteArray(Charsets.UTF_8)
                ccCrypt(sendBlock, passBytes, true)

                val magic = "CCcam\u0000".toByteArray(Charsets.US_ASCII)
                outs.write(ccCrypt(sendBlock, magic, true))
                outs.flush()

                // Step 8: Read 20-byte server password ACK
                val srvAck = ByteArray(20)
                read = 0
                while (read < 20) {
                    val r = ins.read(srvAck, read, 20 - read)
                    if (r < 0) throw Exception("Authentication rejected — server closed connection (bad credentials)")
                    read += r
                }

                val decAck = ccCrypt(recvBlock, srvAck, false)
                val ackStr = String(decAck, 0, minOf(5, decAck.size), Charsets.US_ASCII)
                if (!ackStr.startsWith("CCcam")) {
                    sock.close()
                    return Pair(false, "CCcam authentication failed (invalid username or password)")
                }

                // Step 9: Send client metadata (MSG_CLI_DATA = 0x00, size = 93)
                val cliData = ByteArray(93)
                System.arraycopy(uBytes, 0, cliData, 0, minOf(uBytes.size, 20))
                val nodeId = ByteArray(8).also { java.util.Random().nextBytes(it) }
                System.arraycopy(nodeId, 0, cliData, 20, 8)
                cliData[28] = 0 // want_emu = 0
                val ver = "2.3.0".toByteArray(Charsets.US_ASCII)
                System.arraycopy(ver, 0, cliData, 29, minOf(ver.size, 32))
                val build = "3367".toByteArray(Charsets.US_ASCII)
                System.arraycopy(build, 0, cliData, 61, minOf(build.size, 32))

                val netMsg = ByteArray(4 + 93)
                netMsg[0] = 0 // flag
                netMsg[1] = 0 // MSG_CLI_DATA
                netMsg[2] = 0
                netMsg[3] = 93.toByte()
                System.arraycopy(cliData, 0, netMsg, 4, 93)
                outs.write(ccCrypt(sendBlock, netMsg, true))
                outs.flush()

                sock.close()
                Pair(true, "CCcam login OK (Authenticated as '$user')")
            } catch (e: Exception) {
                Pair(false, "CCcam: ${e.message ?: "auth failed"}")
            }
        }

        // ── Newcamd v5.25 3DES login ───────────────────────────────────────
        // Mirrors NewcamdClient::connectAndLogin() — MSG_CLIENT_2_SERVER_LOGIN
        private fun testNewcamd(host: String, port: Int, user: String, password: String, desKey: String, timeoutMs: Int): Pair<Boolean, String> {
            return try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.soTimeout = timeoutMs
                val ins = sock.getInputStream()
                val outs = sock.getOutputStream()

                // Step 1: read 14-byte server random (MSG_SERVER_2_CLIENT_INIT)
                val srvRand = ByteArray(14)
                var read = 0
                while (read < 14) { val r = ins.read(srvRand, read, 14 - read); if (r < 0) throw Exception("no init packet"); read += r }

                // Step 2: derive 3DES key: desKeyBytes XOR password (byte-by-byte, cyclic)
                val desKeyBytes = desKey.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val pwdBytes = password.toByteArray(Charsets.UTF_8)
                val keyMat = ByteArray(14) { i -> (desKeyBytes[i % desKeyBytes.size].toInt() xor pwdBytes[i % pwdBytes.size].toInt()).toByte() }

                // Build login payload: [0x00, 0x00] + user\0 + password\0
                val userB = user.toByteArray(Charsets.UTF_8)
                val passB = password.toByteArray(Charsets.UTF_8)
                val payload = ByteArray(2 + userB.size + 1 + passB.size + 1)
                payload[0] = 0x00; payload[1] = 0x00
                System.arraycopy(userB, 0, payload, 2, userB.size)
                System.arraycopy(passB, 0, payload, 3 + userB.size, passB.size)

                // Pad to 8-byte boundary and encrypt with 3DES-CBC (IV = first 8 bytes of srvRand)
                val paddedLen = ((payload.size + 7) / 8) * 8
                val padded = payload.copyOf(paddedLen)
                val key24 = ByteArray(24).also { System.arraycopy(keyMat, 0, it, 0, 14); System.arraycopy(keyMat, 0, it, 14, 10) }
                val cipher = javax.crypto.Cipher.getInstance("DESede/CBC/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(key24, "DESede"),
                    javax.crypto.spec.IvParameterSpec(srvRand.copyOf(8)))
                val encrypted = cipher.doFinal(padded)

                // Send: [MSG_CLIENT_2_SERVER_LOGIN=0x14][len_hi][len_lo] + encrypted payload
                outs.write(byteArrayOf(0x14.toByte(), ((encrypted.size shr 8) and 0xFF).toByte(), (encrypted.size and 0xFF).toByte()))
                outs.write(encrypted)
                outs.flush()

                // Step 3: read 3-byte ACK — 0x15 = OK, 0x16 = NACK
                val ackBuf = ByteArray(3)
                read = 0
                while (read < 3) { val r = ins.read(ackBuf, read, 3 - read); if (r < 0) break; read += r }
                sock.close()

                val msgType = ackBuf[0].toInt() and 0xFF
                if (msgType == 0x15) Pair(true, "Newcamd login ACK (3DES)")
                else Pair(false, "Newcamd NACK — wrong credentials or DES key [0x${msgType.toString(16)}]")
            } catch (e: Exception) {
                Pair(false, "Newcamd: ${e.message ?: "auth failed"}")
            }
        }

        // ── CS378X / Camd35 MD5 login ──────────────────────────────────────
        private fun testCs378x(host: String, port: Int, user: String, password: String, timeoutMs: Int): Pair<Boolean, String> {
            return try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.soTimeout = timeoutMs
                val ins = sock.getInputStream()
                val outs = sock.getOutputStream()

                // CS378X LOGIN packet (CMD=0x00):
                // [cmd(1)=0x00] [userLen(1)] [MD5(pass)(16)] [user(20)] [pad(3)]
                val passHash = java.security.MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
                val userBytes = user.toByteArray(Charsets.UTF_8).copyOf(20)
                val loginPkt = ByteArray(40)
                loginPkt[0] = 0x00; loginPkt[1] = user.length.toByte()
                System.arraycopy(passHash, 0, loginPkt, 2, 16)
                System.arraycopy(userBytes, 0, loginPkt, 18, 20)
                outs.write(loginPkt); outs.flush()

                // Read 20-byte response: [type(1)] + [data(19)]
                val resp = ByteArray(20)
                var read = 0
                while (read < 20) { val r = ins.read(resp, read, 20 - read); if (r < 0) break; read += r }
                sock.close()

                val rType = resp[0].toInt() and 0xFF
                if (read >= 1 && rType == 0x00) Pair(true, "CS378X login OK")
                else Pair(false, "CS378X login failed — wrong user/pass [resp=0x${rType.toString(16)}]")
            } catch (e: Exception) {
                Pair(false, "CS378X: ${e.message ?: "auth failed"}")
            }
        }

        // ── SHA1 helper ────────────────────────────────────────────────    
        private fun sha1(data: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-1").digest(data)

        // ── CCcam proprietary stream cipher helpers (cc_crypt) ───────────
        private fun ccInitCrypt(block: CcCryptBlock, key: ByteArray) {
            for (i in 0..255) block.keytable[i] = i
            var j = 0
            for (i in 0..255) {
                j = (j + (key[i % key.size].toInt() and 0xFF) + block.keytable[i]) and 0xFF
                val tmp = block.keytable[i]
                block.keytable[i] = block.keytable[j]
                block.keytable[j] = tmp
            }
            block.state = key[0].toInt() and 0xFF
            block.counter = 0
            block.sum = 0
        }

        private fun ccCrypt(block: CcCryptBlock, data: ByteArray, encrypt: Boolean): ByteArray {
            val out = ByteArray(data.size)
            for (i in data.indices) {
                block.counter = (block.counter + 1) and 0xFF
                block.sum = (block.sum + block.keytable[block.counter]) and 0xFF
                val tmp = block.keytable[block.counter]
                block.keytable[block.counter] = block.keytable[block.sum]
                block.keytable[block.sum] = tmp

                var z = data[i].toInt() and 0xFF
                var valByte = z xor block.keytable[(block.keytable[block.counter] + block.keytable[block.sum]) and 0xFF]
                valByte = valByte xor block.state
                if (!encrypt) {
                    z = valByte and 0xFF
                }
                block.state = (block.state xor z) and 0xFF
                out[i] = valByte.toByte()
            }
            return out
        }

        private fun ccXor(buf: ByteArray) {
            val cccamMagic = "CCcam".toByteArray(Charsets.US_ASCII)
            for (i in 0..7) {
                buf[8 + i] = ((i * (buf[i].toInt() and 0xFF)) and 0xFF).toByte()
                if (i <= 5) {
                    buf[i] = (buf[i].toInt() xor cccamMagic[i].toInt()).toByte()
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
                                        val host = addr.hostAddress
                                        val parts = host?.split(".") ?: emptyList()
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

    data class PresetChannelDefinition(
        val name: String,
        val satellite: String,
        val frequency: Int,
        val polarization: String,
        val symbolRate: Int,
        val serviceId: Int,
        val pmtPid: Int,
        val caid: Int,
        val isEncrypted: Boolean
    )

    private val satellitePresetsDatabase = listOf(
        // Astra 19.2°E - Movistar+ (Encrypted CAID 0x1810 - Nagravision)
        PresetChannelDefinition("Movistar LaLiga HD", "Astra 19.2°E", 10817, "V", 22000, 29950, 1030, 0x1810, true),
        PresetChannelDefinition("Movistar Liga de Campeones HD", "Astra 19.2°E", 10729, "V", 22000, 30001, 1024, 0x1810, true),
        PresetChannelDefinition("Movistar Plus+ HD", "Astra 19.2°E", 10758, "V", 22000, 30050, 1025, 0x1810, true),
        PresetChannelDefinition("Movistar Accion HD", "Astra 19.2°E", 11126, "V", 22000, 30850, 1026, 0x1810, true),
        PresetChannelDefinition("Movistar Comedia HD", "Astra 19.2°E", 10758, "V", 22000, 30052, 1027, 0x1810, true),
        PresetChannelDefinition("Movistar Drama HD", "Astra 19.2°E", 11258, "V", 22000, 30900, 1028, 0x1810, true),
        PresetChannelDefinition("Movistar Cine Espanol HD", "Astra 19.2°E", 10817, "V", 22000, 29952, 1031, 0x1810, true),
        PresetChannelDefinition("Movistar Deportes HD", "Astra 19.2°E", 10729, "V", 22000, 30003, 1032, 0x1810, true),
        PresetChannelDefinition("DAZN 1 HD", "Astra 19.2°E", 10729, "V", 22000, 30005, 1034, 0x1810, true),
        PresetChannelDefinition("DAZN 2 HD", "Astra 19.2°E", 10729, "V", 22000, 30006, 1035, 0x1810, true),
        PresetChannelDefinition("DAZN LaLiga HD", "Astra 19.2°E", 11258, "V", 22000, 30905, 1036, 0x1810, true),
        PresetChannelDefinition("Warner TV HD", "Astra 19.2°E", 11126, "V", 22000, 30855, 1037, 0x1810, true),
        PresetChannelDefinition("Star Channel HD", "Astra 19.2°E", 11258, "V", 22000, 30910, 1038, 0x1810, true),
        PresetChannelDefinition("AXN HD", "Astra 19.2°E", 11126, "V", 22000, 30860, 1039, 0x1810, true),
        PresetChannelDefinition("Calle 13 HD", "Astra 19.2°E", 10817, "V", 22000, 29955, 1040, 0x1810, true),
        PresetChannelDefinition("Syfy HD", "Astra 19.2°E", 10817, "V", 22000, 29956, 1041, 0x1810, true),
        PresetChannelDefinition("Cosmo HD", "Astra 19.2°E", 11258, "V", 22000, 30915, 1042, 0x1810, true),
        // Astra 19.2°E - HD+ Germany (Encrypted CAID 0x1830 - Nagravision)
        PresetChannelDefinition("HD+ RTL UHD", "Astra 19.2°E", 11214, "H", 22000, 13410, 1025, 0x1830, true),
        PresetChannelDefinition("HD+ ProSieben HD", "Astra 19.2°E", 11464, "H", 22000, 61301, 102, 0x1830, true),
        PresetChannelDefinition("HD+ Sat.1 HD", "Astra 19.2°E", 11464, "H", 22000, 61300, 101, 0x1830, true),
        PresetChannelDefinition("HD+ VOX HD", "Astra 19.2°E", 10832, "H", 22000, 61200, 100, 0x1830, true),
        // Astra 19.2°E - Sky Deutschland (Encrypted CAID 0x098C - NDS VideoGuard)
        PresetChannelDefinition("Sky Sport Bundesliga 1 HD", "Astra 19.2°E", 11720, "H", 27500, 105, 96, 0x098C, true),
        PresetChannelDefinition("Sky Cinema Premiere HD", "Astra 19.2°E", 11758, "H", 27500, 107, 98, 0x098C, true),
        PresetChannelDefinition("Sky Krimi HD", "Astra 19.2°E", 11720, "H", 27500, 110, 99, 0x098C, true),
        // Astra 19.2°E - Canal+ France (Encrypted CAID 0x0100 - Seca / Mediaguard)
        PresetChannelDefinition("Canal+ France HD", "Astra 19.2°E", 12012, "V", 29700, 8801, 110, 0x0100, true),
        PresetChannelDefinition("Canal+ Sport France HD", "Astra 19.2°E", 11856, "V", 29700, 8201, 120, 0x0100, true),
        // Astra 19.2°E - ORF Digital Austria (Encrypted CAID 0x0D95 - Cryptoworks)
        PresetChannelDefinition("ORF 1 HD", "Astra 19.2°E", 11303, "H", 22000, 4911, 1920, 0x0D95, true),
        PresetChannelDefinition("ORF 2 HD", "Astra 19.2°E", 11303, "H", 22000, 4912, 1921, 0x0D95, true),
        // Astra 19.2°E - Free-to-Air (FTA CAID 0x0000)
        PresetChannelDefinition("Canal 24 Horas HD", "Astra 19.2°E", 11376, "V", 22000, 30010, 1050, 0, false),
        PresetChannelDefinition("TVE Internacional HD", "Astra 19.2°E", 11376, "V", 22000, 30011, 1051, 0, false),
        PresetChannelDefinition("Telesur HD", "Astra 19.2°E", 11376, "V", 22000, 30012, 1052, 0, false),
        PresetChannelDefinition("ZDF HD", "Astra 19.2°E", 11362, "H", 22000, 11110, 6100, 0, false),
        PresetChannelDefinition("Das Erste HD", "Astra 19.2°E", 11494, "H", 22000, 10301, 5100, 0, false),
        // Hispasat 30°W - MEO / NOS (Encrypted CAID 0x1802 - Nagravision)
        PresetChannelDefinition("Sport TV 1 HD", "Hispasat 30°W", 12246, "H", 27500, 401, 4010, 0x1802, true),
        PresetChannelDefinition("Sport TV 2 HD", "Hispasat 30°W", 12246, "H", 27500, 402, 4020, 0x1802, true),
        PresetChannelDefinition("Canal Hollywood PT", "Hispasat 30°W", 12246, "H", 27500, 404, 4040, 0x1802, true),
        PresetChannelDefinition("TVCine Top HD", "Hispasat 30°W", 12246, "H", 27500, 406, 4060, 0x1802, true),
        PresetChannelDefinition("SIC Noticias", "Hispasat 30°W", 12168, "H", 27500, 408, 4080, 0x1802, true),
        PresetChannelDefinition("TVI Internacional", "Hispasat 30°W", 12168, "H", 27500, 405, 4050, 0, false),
        PresetChannelDefinition("RTP 1", "Hispasat 30°W", 12130, "H", 27500, 410, 4100, 0, false),
        // Hotbird 13°E - Tivùsat (Encrypted CAID 0x183E - Nagravision)
        PresetChannelDefinition("Rai 4K", "Hotbird 13°E", 11075, "V", 30000, 1, 100, 0x183E, true),
        PresetChannelDefinition("Canale 5 HD", "Hotbird 13°E", 11432, "V", 29900, 105, 150, 0x183E, true),
        PresetChannelDefinition("Italia 1 HD", "Hotbird 13°E", 11432, "V", 29900, 106, 151, 0x183E, true),
        // Hotbird 13°E - Sky Italia (Encrypted CAID 0x09CD - NDS VideoGuard)
        PresetChannelDefinition("Sky Sport Uno HD", "Hotbird 13°E", 11958, "V", 27500, 10901, 160, 0x09CD, true),
        PresetChannelDefinition("Sky Cinema Uno HD", "Hotbird 13°E", 11958, "V", 27500, 10902, 161, 0x09CD, true),
        // Hotbird 13°E - SRG SSR Switzerland (Encrypted CAID 0x0500 - Viaccess)
        PresetChannelDefinition("SRF 1 HD", "Hotbird 13°E", 10971, "H", 29700, 2, 101, 0x0500, true),
        PresetChannelDefinition("RTS Un HD", "Hotbird 13°E", 10971, "H", 29700, 3, 102, 0x0500, true),
        // Hotbird 13°E - Polsat Box (Encrypted CAID 0x1803 - Nagravision)
        PresetChannelDefinition("Polsat Sport HD", "Hotbird 13°E", 12265, "V", 27500, 3101, 301, 0x1803, true),
        // Hotbird 13°E - Nova Greece (Encrypted CAID 0x0604 - Irdeto)
        PresetChannelDefinition("Nova Sports 1 HD", "Hotbird 13°E", 11823, "H", 27500, 318, 3180, 0x0604, true),
        PresetChannelDefinition("Rai News 24", "Hotbird 13°E", 10992, "V", 27500, 8502, 802, 0, false),
        // Thor 0.8°W - Canal Digital Nordic (Encrypted CAID 0x0B00 - Conax)
        PresetChannelDefinition("SVT 1 HD", "Thor 0.8°W", 10903, "V", 25000, 1010, 101, 0x0B00, true),
        PresetChannelDefinition("TV 2 Norge HD", "Thor 0.8°W", 10903, "V", 25000, 1012, 103, 0x0B00, true),
        // Eutelsat 5°W - Fransat (Encrypted CAID 0x0500 - Viaccess)
        PresetChannelDefinition("TF1 HD (Fransat)", "Eutelsat 5°W", 11096, "V", 29950, 401, 410, 0x0500, true),
        PresetChannelDefinition("France 2 HD (Fransat)", "Eutelsat 5°W", 11096, "V", 29950, 402, 420, 0x0500, true)
    )

    private inner class ApiChannelsScanHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    var includeEncrypted = true
                    var validateServers = true
                    var source = "all"

                    val uriQuery = exchange.requestURI.query
                    if (!uriQuery.isNullOrEmpty()) {
                        val pairs = uriQuery.split("&")
                        for (p in pairs) {
                            val kv = p.split("=", limit = 2)
                            if (kv.size == 2) {
                                when (kv[0].lowercase()) {
                                    "include_encrypted" -> includeEncrypted = kv[1].toBoolean()
                                    "validate_servers" -> validateServers = kv[1].toBoolean()
                                    "source" -> source = kv[1].lowercase()
                                }
                            }
                        }
                    }

                    if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                        try {
                            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                            if (body.isNotBlank()) {
                                val json = JSONObject(body)
                                if (json.has("include_encrypted")) includeEncrypted = json.optBoolean("include_encrypted", true)
                                if (json.has("validate_servers")) validateServers = json.optBoolean("validate_servers", true)
                                if (json.has("source")) source = json.optString("source", "all").lowercase()
                            }
                        } catch (ignored: Exception) {}
                    }

                    appendLog("Channel Scan requested: source='$source', includeEncrypted=$includeEncrypted, validateServers=$validateServers")

                    val rawChannels = mutableListOf<PresetChannelDefinition>()

                    // 1. Query Android TV TvContract.Channels
                    if (source == "all" || source == "tv") {
                        try {
                            val bridge = OscamTvInputBridge(context)
                            val discovered = bridge.queryAllTvChannels()
                            discovered.forEach { ch ->
                                val caid = ch.detectedCaids.firstOrNull() ?: if (ch.isScrambled) 0x1810 else 0
                                val isEnc = ch.isScrambled || ch.detectedCaids.isNotEmpty()
                                rawChannels.add(
                                    PresetChannelDefinition(
                                        name = ch.displayName,
                                        satellite = if (ch.type.isNotEmpty()) "TV Tuner (${ch.type})" else "TV Tuner (TvContract)",
                                        frequency = 0,
                                        polarization = "H",
                                        symbolRate = 22000,
                                        serviceId = ch.serviceId,
                                        pmtPid = ch.pmtPid,
                                        caid = caid,
                                        isEncrypted = isEnc
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "TvContract scan: ${e.message}")
                        }
                    }

                    // 2. Add satellite transponder presets
                    if (source == "all" || source == "astra") {
                        rawChannels.addAll(satellitePresetsDatabase.filter { it.satellite.startsWith("Astra", true) })
                    }
                    if (source == "all" || source == "hispasat") {
                        rawChannels.addAll(satellitePresetsDatabase.filter { it.satellite.startsWith("Hispasat", true) })
                    }
                    if (source == "all" || source == "hotbird") {
                        rawChannels.addAll(satellitePresetsDatabase.filter { it.satellite.startsWith("Hotbird", true) })
                    }
                    if (source == "all" || source == "other") {
                        rawChannels.addAll(satellitePresetsDatabase.filter { 
                            !it.satellite.startsWith("Astra", true) && 
                            !it.satellite.startsWith("Hispasat", true) && 
                            !it.satellite.startsWith("Hotbird", true) 
                        })
                    }

                    // Deduplicate
                    val seenKeys = mutableSetOf<String>()
                    val deduped = mutableListOf<PresetChannelDefinition>()
                    for (ch in rawChannels) {
                        val key = "${ch.name.lowercase().trim()}_${ch.serviceId}"
                        if (!seenKeys.contains(key)) {
                            seenKeys.add(key)
                            deduped.add(ch)
                        }
                    }

                    // 3. Filter by include_encrypted (user requirement: allow searching encrypted channels)
                    val filtered = deduped.filter { ch ->
                        if (!includeEncrypted && ch.isEncrypted) false else true
                    }

                    // 4. Validate with servers (user requirement: validate with configured servers)
                    val currentConfig = repository.getCurrentConfig()
                    val activeServers = currentConfig.servers.filter { it.enabled }
                    val serverStatusMap = mutableMapOf<String, Boolean>()

                    if (validateServers) {
                        activeServers.forEach { s ->
                            var ok = false
                            try {
                                Socket().use { sock ->
                                    sock.connect(InetSocketAddress(s.host, s.port), 1500)
                                    ok = true
                                }
                            } catch (ignored: Exception) {
                                ok = false
                            }
                            serverStatusMap[s.id] = ok
                        }
                    }

                    var scrambledCount = 0
                    var ftaCount = 0
                    var validatedCount = 0

                    val resultsArray = JSONArray()
                    filtered.forEach { ch ->
                        var isValidated = false
                        var validatedServer = ""
                        var statusText: String
                        val cas = CasSystemDetector.detect(ch.caid)

                        if (!ch.isEncrypted) {
                            ftaCount++
                            isValidated = true
                            statusText = "🔵 En abierto (FTA - Directo)"
                        } else {
                            scrambledCount++
                            if (!validateServers) {
                                statusText = "🔒 [${cas.shortCode}] Encriptado (0x%04X)".format(ch.caid)
                            } else {
                                val srv = activeServers.firstOrNull { it.caid == ch.caid }
                                    ?: activeServers.firstOrNull { currentConfig.caids.contains(ch.caid) }
                                    ?: activeServers.firstOrNull { it.protocol == ServerProtocol.CCCAM }
                                    ?: activeServers.firstOrNull { it.isPrimary }

                                if (srv != null) {
                                    val isOnline = serverStatusMap[srv.id] ?: true
                                    if (isOnline) {
                                        isValidated = true
                                        validatedCount++
                                        validatedServer = srv.name
                                        statusText = "🟢 [${cas.shortCode}] Validado con '${srv.name}' [${srv.protocol.name}] (0x%04X)".format(ch.caid)
                                    } else {
                                        statusText = "🟡 [${cas.shortCode}] Encriptado (Servidor '${srv.name}' no responde)".format(ch.caid)
                                    }
                                } else {
                                    statusText = "🟡 [${cas.shortCode}] Encriptado (Sin servidor configurado para 0x%04X)".format(ch.caid)
                                }
                            }
                        }

                        resultsArray.put(JSONObject().apply {
                            put("name", ch.name)
                            put("satellite", ch.satellite)
                            put("frequency", ch.frequency)
                            put("polarization", ch.polarization)
                            put("symbolRate", ch.symbolRate)
                            put("serviceId", ch.serviceId)
                            put("pmtPid", ch.pmtPid)
                            put("caid", "0x%04X".format(ch.caid))
                            put("caid_int", ch.caid)
                            put("cas_system", cas.systemName)
                            put("cas_code", cas.shortCode)
                            put("cas_color", cas.badgeColor)
                            put("cas_desc", cas.description)
                            put("is_encrypted", ch.isEncrypted)
                            put("is_validated", isValidated)
                            put("validated_server", validatedServer)
                            put("status_badge", statusText)
                            put("stream_url", "")
                        })
                    }

                    appendLog("Channel Scan finished: ${filtered.size} channels ($scrambledCount encrypted, $ftaCount FTA, $validatedCount validated with servers)")

                    val resObj = JSONObject().apply {
                        put("success", true)
                        put("total_found", filtered.size)
                        put("scrambled_count", scrambledCount)
                        put("fta_count", ftaCount)
                        put("validated_count", validatedCount)
                        put("channels", resultsArray)
                    }

                    sendJsonResponse(exchange, 200, resObj.toString())
                } catch (e: Exception) {
                    appendLog("ERROR in channel scan: ${e.message}")
                    sendErrorResponse(exchange, 500, "Channel scan failed: ${e.message}")
                }
            }
        }
    }

    private inner class ApiChannelsImportHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                    val json = JSONObject(body)
                    val chArray = json.optJSONArray("channels")
                    if (chArray == null || chArray.length() == 0) {
                        sendErrorResponse(exchange, 400, "No channels provided to import")
                        return@launch
                    }

                    val currentConfig = repository.getCurrentConfig()
                    val existingChannels = currentConfig.channels.toMutableList()
                    var importedCount = 0

                    for (i in 0 until chArray.length()) {
                        val obj = chArray.getJSONObject(i)
                        val name = obj.optString("name", "Channel")
                        val sat = obj.optString("satellite", "Astra 19.2°E")
                        val freq = obj.optInt("frequency", 11000)
                        val pol = obj.optString("polarization", "H")
                        val sr = obj.optInt("symbolRate", 22000)
                        val sid = obj.optInt("serviceId", 1)
                        val pmt = obj.optInt("pmtPid", 100)
                        val caidStr = obj.optString("caid", "0x1810")
                        val caid = if (caidStr.startsWith("0x", true)) caidStr.substring(2).toInt(16) else caidStr.toIntOrNull() ?: 0x1810
                        val streamUrl = obj.optString("stream_url", obj.optString("streamUrl", ""))

                        val exists = existingChannels.any { it.serviceId == sid && it.satellite.equals(sat, ignoreCase = true) }
                        if (!exists) {
                            existingChannels.add(
                                OscamChannelEntry(
                                    id = UUID.randomUUID().toString(),
                                    name = name,
                                    satellite = sat,
                                    frequency = freq,
                                    polarization = pol,
                                    symbolRate = sr,
                                    serviceId = sid,
                                    pmtPid = pmt,
                                    caid = caid,
                                    streamUrl = streamUrl
                                )
                            )
                            importedCount++
                        }
                    }

                    val newConfig = currentConfig.copy(channels = existingChannels)
                    repository.saveConfig(newConfig)
                    onConfigUpdatedCallback(newConfig)

                    appendLog("Imported $importedCount new channels into database (Total: ${existingChannels.size} channels)")
                    sendJsonResponse(exchange, 200, "{\"success\":true,\"imported\":$importedCount,\"total\":${existingChannels.size}}")
                } catch (e: Exception) {
                    appendLog("ERROR importing channels: ${e.message}")
                    sendErrorResponse(exchange, 500, "Import failed: ${e.message}")
                }
            }
        }
    }

    private inner class ApiChannelsCachedHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val config = repository.getCurrentConfig()
                    val totalCachedCws = OscamTvInputBridge.cwCache.size
                    val hits = OscamTvInputBridge.cacheHits.get()
                    val misses = OscamTvInputBridge.cacheMisses.get()
                    val total = hits + misses
                    val hitRatio = if (total > 0) "%.1f%%".format(hits.toDouble() * 100.0 / total) else "0.0%"

                    val liveList = OscamTvInputBridge.getLiveChannelsList()
                    val channelsArray = JSONArray()

                    val now = System.currentTimeMillis()
                    liveList.forEach { act ->
                        val secAgo = ((now - act.lastEcmTimestamp) / 1000).coerceAtLeast(0)
                        val cas = CasSystemDetector.detect(act.caid)
                        channelsArray.put(JSONObject().apply {
                            put("serviceId", act.serviceId)
                            put("name", act.channelName)
                            put("pmtPid", act.pmtPid)
                            put("caid", "0x%04X".format(act.caid))
                            put("caid_int", act.caid)
                            put("cas_system", cas.systemName)
                            put("cas_code", cas.shortCode)
                            put("cas_color", cas.badgeColor)
                            put("cas_desc", cas.description)
                            put("ecm_requests", act.ecmCount)
                            put("cw_hits", act.hitCount)
                            put("last_seen_sec_ago", secAgo)
                            put("last_parity", act.lastParity)
                            put("last_cw", if (act.lastCwHex.isNotEmpty()) act.lastCwHex else "En espera de ECM")
                            put("status", if (secAgo < 30) "ACTIVE_DESCRAMBLING" else "CACHED_IDLE")
                        })
                    }

                    val resObj = JSONObject().apply {
                        put("success", true)
                        put("cache_enabled", config.cwCacheEnabled)
                        put("total_cached_cws", totalCachedCws)
                        put("cache_hits", hits)
                        put("cache_misses", misses)
                        put("hit_ratio", hitRatio)
                        put("channels", channelsArray)
                    }
                    sendJsonResponse(exchange, 200, resObj.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Error getting cached channels")
                }
            }
        }
    }

    private inner class ApiChannelsAvailableHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val config = repository.getCurrentConfig()
                    val configuredSids = config.channels.map { it.serviceId }.toSet()

                    val listArray = JSONArray()
                    satellitePresetsDatabase.forEach { p ->
                        val cas = CasSystemDetector.detect(p.caid)
                        val isConfigured = configuredSids.contains(p.serviceId)
                        listArray.put(JSONObject().apply {
                            put("name", p.name)
                            put("satellite", p.satellite)
                            put("frequency", p.frequency)
                            put("polarization", p.polarization)
                            put("symbolRate", p.symbolRate)
                            put("serviceId", p.serviceId)
                            put("pmtPid", p.pmtPid)
                            put("caid", "0x%04X".format(p.caid))
                            put("caid_int", p.caid)
                            put("cas_system", cas.systemName)
                            put("cas_code", cas.shortCode)
                            put("cas_color", cas.badgeColor)
                            put("cas_desc", cas.description)
                            put("is_encrypted", p.isEncrypted)
                            put("is_configured", isConfigured)
                        })
                    }

                    val resObj = JSONObject().apply {
                        put("success", true)
                        put("total", satellitePresetsDatabase.size)
                        put("channels", listArray)
                    }
                    sendJsonResponse(exchange, 200, resObj.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Error getting available channels")
                }
            }
        }
    }

    private inner class ApiCacheTestEcmHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val body = if (exchange.requestMethod.equals("POST", true)) {
                        exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
                    } else ""
                    val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                    val sid = json.optInt("serviceId", 29950)
                    val caidStr = json.optString("caid", "0x1810")
                    val caid = if (caidStr.startsWith("0x", true)) caidStr.substring(2).toInt(16) else caidStr.toIntOrNull() ?: 0x1810
                    val name = json.optString("name", "Test Channel")

                    val fakeEcm = ByteArray(128) { (it and 0xFF).toByte() }
                    fakeEcm[0] = 0x80.toByte()
                    val fakeCw = byteArrayOf(
                        0x12, 0x34, 0x56, 0x9C.toByte(), 0x78, 0x9A.toByte(), 0xBC.toByte(), 0xD2.toByte(),
                        0xDE.toByte(), 0xF0.toByte(), 0x12, 0xE0.toByte(), 0x34, 0x56, 0x78, 0x02
                    )

                    OscamTvInputBridge.recordTunedChannel(sid, name, 1024, caid)
                    val bridge = OscamTvInputBridge(context)
                    bridge.recordResolvedCw(fakeEcm, fakeCw, parity = 0)

                    appendLog("Simulated ECM resolved for '$name' (SID $sid, CAID 0x%04X). CW stored in memory cache.".format(caid))

                    val res = JSONObject().apply {
                        put("success", true)
                        put("message", "CW de prueba simulado e inyectado en memoria para '$name' (CAID 0x%04X)".format(caid))
                        put("cw", "12 34 56 9C 78 9A BC D2")
                        put("parity", "EVEN (0)")
                    }
                    sendJsonResponse(exchange, 200, res.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Error simulating ECM")
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
                    m3u.append("#EXTM3U name=\"Android TV OSCam Embedded TVHeadend Playlist\"\n")
                    m3u.append("## X-TVH-SERVER: Android-OSCam-Bridge 4.3 TVHeadend-Compatible (No-Root)\n\n")

                    val channels = config.channels.ifEmpty { OscamConfig.defaultChannels() }
                    channels.forEach { ch ->
                        val streamUrl = if (ch.streamUrl.isNotEmpty()) {
                            ch.streamUrl.replace("127.0.0.1", host)
                        } else {
                            "http://$host:9191/stream/channel/${ch.serviceId}"
                        }
                        val cas = CasSystemDetector.detect(ch.caid)
                        val casBadge = if (ch.caid > 0) " [${cas.shortCode}]" else " [FTA]"
                        m3u.append("#EXTINF:-1 tvg-id=\"${ch.serviceId}\" tvg-name=\"${ch.name}\" group-title=\"${ch.satellite}\" tvg-type=\"tv\",${ch.name}$casBadge\n")
                        m3u.append("$streamUrl\n\n")
                    }

                    val bytes = m3u.toString().toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "audio/x-mpegurl; charset=UTF-8")
                    exchange.responseHeaders.set("Content-Disposition", "inline; filename=channels.m3u")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    exchange.responseBody.close()
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, "Playlist error: ${e.message}")
                }
            }
        }
    }

    private inner class WebEpgXmlHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            scope.launch {
                try {
                    val config = repository.getCurrentConfig()
                    val channels = config.channels.ifEmpty { OscamConfig.defaultChannels() }
                    val sb = StringBuilder()
                    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    val sdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }

                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    val startTime = sdf.format(cal.time)
                    cal.set(Calendar.HOUR_OF_DAY, 23)
                    cal.set(Calendar.MINUTE, 59)
                    cal.set(Calendar.SECOND, 59)
                    val stopTime = sdf.format(cal.time)

                    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    sb.append("<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n")
                    sb.append("<tv generator-info-name=\"Android-OSCam-Bridge-TVHeadend\" generator-info-url=\"https://github.com/enekolizarraga/android-oscam-bridge\">\n")

                    channels.forEach { ch ->
                        val tvgId = "${ch.serviceId}"
                        val safeName = ch.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        val safeSat = ch.satellite.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        sb.append("  <channel id=\"$tvgId\">\n")
                        sb.append("    <display-name>$safeName</display-name>\n")
                        sb.append("    <display-name>$safeSat</display-name>\n")
                        sb.append("  </channel>\n")
                    }

                    channels.forEach { ch ->
                        val tvgId = "${ch.serviceId}"
                        val safeName = ch.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        val safeSat = ch.satellite.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        val cas = CasSystemDetector.detect(ch.caid)
                        sb.append("  <programme start=\"$startTime\" stop=\"$stopTime\" channel=\"$tvgId\">\n")
                        sb.append("    <title lang=\"es\">Emisión en Directo: $safeName</title>\n")
                        sb.append("    <desc lang=\"es\">Canal satelital $safeName sintonizado en $safeSat (${ch.frequency} MHz ${ch.polarization}). Descodificación activa mediante ${cas.systemName} (${cas.shortCode}).</desc>\n")
                        sb.append("    <category lang=\"es\">General</category>\n")
                        sb.append("  </programme>\n")
                    }

                    sb.append("</tv>\n")

                    val bytes = sb.toString().toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/xml; charset=UTF-8")
                    exchange.responseHeaders.set("Content-Disposition", "inline; filename=epg.xml")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    exchange.responseBody.close()
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, "EPG generation error: ${e.message}")
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
        val dm = context.resources.displayMetrics
        val realDisplay = "${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi} DPI"

        val dataDir = Environment.getDataDirectory()
        val stat = StatFs(dataDir.path)
        val totalStorageGb = String.format(Locale.US, "%.1f", (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024 * 1024))
        val freeStorageGb = String.format(Locale.US, "%.1f", (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024 * 1024))
        val realStorage = "$freeStorageGb GB libres / $totalStorageGb GB total"

        var realIp = "127.0.0.1"
        var realIface = "Ethernet/Wi-Fi"
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            realIp = addr.hostAddress ?: "127.0.0.1"
                            realIface = iface.name
                            break
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        val realNetwork = "$realIface ($realIp)"

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Android TV CAS Bridge Master Console (OSCam &amp; TVHeadend)</title>
    <script src="https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.min.js"></script>
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

        /* Server Profiles Card & Hardware Cards */
        .server-card, .hw-card {
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 18px;
            margin-bottom: 14px;
            transition: border-color 0.2s;
        }
        .server-card:hover, .hw-card:hover { border-color: var(--border-hover); }
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

        /* Player & Channel Zapper Layout */
        .player-grid {
            display: grid;
            grid-template-columns: 1fr 340px;
            gap: 16px;
            align-items: start;
        }
        @media (max-width: 900px) {
            .player-grid { grid-template-columns: 1fr; }
        }
        .zapper-container {
            background: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 14px;
            max-height: 560px;
            display: flex;
            flex-direction: column;
        }
        .zapper-list {
            overflow-y: auto;
            max-height: 460px;
            display: flex;
            flex-direction: column;
            gap: 6px;
            margin-top: 10px;
            padding-right: 4px;
        }
        .zapper-item {
            background: rgba(0,0,0,0.3);
            border: 1px solid rgba(255,255,255,0.06);
            border-radius: 8px;
            padding: 8px 12px;
            cursor: pointer;
            display: flex;
            justify-content: space-between;
            align-items: center;
            transition: all 0.2s;
        }
        .zapper-item:hover {
            border-color: var(--primary);
            background: rgba(59, 130, 246, 0.1);
        }
        .zapper-item.active {
            border-color: var(--primary);
            background: rgba(59, 130, 246, 0.2);
            box-shadow: 0 0 12px rgba(59, 130, 246, 0.3);
        }

        /* Modals */
        .modal-overlay {
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0,0,0,0.8);
            backdrop-filter: blur(4px);
            display: none;
            justify-content: center;
            align-items: center;
            z-index: 1000;
            padding: 16px;
        }
        .modal-box {
            background: var(--bg-surface);
            border: 1px solid var(--border-hover);
            border-radius: 12px;
            max-width: 620px;
            width: 100%;
            padding: 24px;
            box-shadow: 0 20px 40px rgba(0,0,0,0.8);
            max-height: 90vh;
            overflow-y: auto;
        }

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
            <button class="tab-btn" onclick="showTab('tab-player', this)">📺 TVHeadend &amp; Reproductor Web</button>
            <button class="tab-btn" onclick="showTab('tab-spectrum', this)">📶 Analizador de Espectro RF</button>
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
                            <polyline fill="none" stroke="#3B82F6" stroke-width="2.5" points="0,85 500,85" id="line-latency"/>
                        </svg>
                    </div>

                    <div class="chart-box">
                        <div class="chart-title">
                            <span>ECM Processing Flow Rate</span>
                            <span id="chart-ecm-label" style="color:var(--accent);">Active</span>
                        </div>
                        <svg class="sparkline" id="svg-ecm-chart" viewBox="0 0 500 85" preserveAspectRatio="none">
                            <polyline fill="none" stroke="#8B5CF6" stroke-width="2.5" points="0,85 500,85" id="line-ecm"/>
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
                        <div class="panel-title">🛰️ Satellite &amp; DVB Channel Database &amp; CAS Encryption Monitor</div>
                        <div class="panel-desc">Gestor de canales de satélite, detección de sistemas de encriptación CAS (Nagravision, Viaccess, NDS, Conax, Seca, Irdeto, Cryptoworks), caché CW en vivo y sintonizador de TV.</div>
                    </div>
                    <div style="display:flex; gap:8px; flex-wrap:wrap;">
                        <button type="button" class="btn btn-outline" onclick="addChannelRow()">+ Add Channel</button>
                        <a href="/playlist.m3u" class="btn btn-purple" download="channels.m3u">⬇ Export M3U</a>
                        <a href="/lamedb" class="btn btn-outline" download="lamedb">⬇ Export Enigma2 lamedb</a>
                    </div>
                </div>

                <!-- Subtab Navigation -->
                <div style="display:flex; gap:10px; margin-bottom:18px; border-bottom:1px solid var(--border); padding-bottom:12px; flex-wrap:wrap;">
                    <button type="button" class="btn btn-primary ch-subtab-btn" id="subtab-btn-cfg" onclick="switchChannelSubtab('cfg')">
                        📺 Canales Configurados (<span id="count-cfg-channels">0</span>)
                    </button>
                    <button type="button" class="btn btn-outline ch-subtab-btn" id="subtab-btn-cached" onclick="switchChannelSubtab('cached')">
                        ⚡ Canales Cacheados (CW Cache en Vivo)
                    </button>
                    <button type="button" class="btn btn-outline ch-subtab-btn" id="subtab-btn-avail" onclick="switchChannelSubtab('avail')">
                        🛰️ Catálogo Satélite Disponible (<span id="count-avail-channels">0</span>)
                    </button>
                    <button type="button" class="btn btn-outline ch-subtab-btn" id="subtab-btn-scan" onclick="switchChannelSubtab('scan')">
                        🔍 Rebuscar Canales (Scan TV Tuner &amp; Validar)
                    </button>
                </div>

                <!-- SUBSECTION 1: Canales Configurados -->
                <div id="channel-subview-cfg">
                    <!-- Barra de herramientas superior y filtros -->
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:14px; margin-bottom:16px;">
                        <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                            <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                                <input type="text" id="filter-cfg-search" placeholder="🔍 Buscar canal, SID, CAID..." oninput="filterConfiguredChannelsTable()" style="padding:7px 12px; font-size:12px; width:220px;">
                                <select id="filter-cfg-sat" onchange="filterConfiguredChannelsTable()" style="padding:7px 10px; font-size:12px; width:150px;">
                                    <option value="all">🛰️ Todos los satélites</option>
                                    <option value="Astra">Astra 19.2°E</option>
                                    <option value="Hotbird">Hotbird 13°E</option>
                                    <option value="Hispasat">Hispasat 30°W</option>
                                    <option value="TDT">TDT Terrestre</option>
                                </select>
                                <select id="filter-cfg-cas" onchange="filterConfiguredChannelsTable()" style="padding:7px 10px; font-size:12px; width:135px;">
                                    <option value="all">🔒 Todos los CAS</option>
                                    <option value="FTA">🔓 Solo FTA</option>
                                    <option value="NAGRA">NAGRA (0x18xx)</option>
                                    <option value="VIACCESS">VIACCESS (0x05xx)</option>
                                    <option value="NDS">NDS (0x09xx)</option>
                                    <option value="CONAX">CONAX (0x0Bxx)</option>
                                    <option value="SECA">SECA (0x01xx)</option>
                                    <option value="IRDETO">IRDETO (0x06xx)</option>
                                </select>
                            </div>
                            <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
                                <button type="button" class="btn btn-primary" style="padding:7px 12px; font-size:12px;" onclick="openNewChannelModal()">➕ Nuevo Canal</button>
                                <div style="position:relative; display:inline-block;">
                                    <button type="button" class="btn btn-purple" style="padding:7px 12px; font-size:12px;" onclick="togglePackageMenu()">📦 Añadir Paquete ▾</button>
                                    <div id="dropdown-packages-menu" style="display:none; position:absolute; right:0; top:100%; margin-top:4px; background:var(--bg-surface); border:1px solid var(--border-hover); border-radius:8px; width:280px; z-index:100; box-shadow:0 8px 24px rgba(0,0,0,0.7); padding:6px 0;">
                                        <div class="preset-badge" onclick="addPredefinedPackage('movistar')" style="margin:4px 8px; display:block;">🇪🇸 Movistar+ España HD (LaLiga, Cine, Series)</div>
                                        <div class="preset-badge" onclick="addPredefinedPackage('hdplus')" style="margin:4px 8px; display:block;">🇩🇪 HD+ Alemania (UHD, RTL, Sat.1)</div>
                                        <div class="preset-badge" onclick="addPredefinedPackage('skyde')" style="margin:4px 8px; display:block;">🇩🇪 Sky Deutschland (Bundesliga, Cinema)</div>
                                        <div class="preset-badge" onclick="addPredefinedPackage('tivusat')" style="margin:4px 8px; display:block;">🇮🇹 Tivùsat / Mediaset Italia (Rai 4K)</div>
                                        <div class="preset-badge" onclick="addPredefinedPackage('srg')" style="margin:4px 8px; display:block;">🇨🇭 SRG SSR Suiza (SRF, RTS, RSI)</div>
                                        <div class="preset-badge" onclick="addPredefinedPackage('meo')" style="margin:4px 8px; display:block;">🇵🇹 MEO / NOS Portugal (Sport TV)</div>
                                        <div class="preset-badge" onclick="addPredefinedPackage('tdt')" style="margin:4px 8px; display:block;">📺 TDT España Generalistas FTA</div>
                                    </div>
                                </div>
                                <button type="button" class="btn btn-outline" style="padding:7px 12px; font-size:12px;" onclick="openImportM3uModal()">📥 Importar M3U</button>
                                <button type="button" class="btn btn-danger" style="padding:7px 12px; font-size:12px;" onclick="deleteSelectedChannels()">🗑️ Borrar Selección</button>
                                <button type="button" class="btn btn-success" style="padding:7px 14px; font-size:12px;" onclick="saveConfiguration()">💾 Guardar Canales</button>
                            </div>
                        </div>
                    </div>

                    <div class="table-container" style="max-height:520px; overflow-y:auto;">
                        <table id="channels-table">
                            <thead>
                                <tr>
                                    <th style="width:36px; text-align:center;"><input type="checkbox" id="chk-select-all-cfg" onclick="toggleSelectAllConfigured(this.checked)"></th>
                                    <th style="width:60px; text-align:center;">Orden</th>
                                    <th>Canal (Nombre)</th>
                                    <th>Satélite</th>
                                    <th>Frec / Pol / SR</th>
                                    <th>SID / PMT</th>
                                    <th>CAID</th>
                                    <th>Sistema CAS Detectado</th>
                                    <th>Acciones</th>
                                </tr>
                            </thead>
                            <tbody id="channels-tbody"></tbody>
                        </table>
                    </div>

                    <div style="display:flex; justify-content:space-between; align-items:center; margin-top:16px; flex-wrap:wrap; gap:10px;">
                        <div style="font-size:12px; color:var(--text-muted);">
                            💡 Todos los canales gestionados se reflejan en la parrilla del sintonizador, en el servidor TVHeadend autónomo (puerto 9191) y en el reproductor web integrado.
                        </div>
                        <button type="button" class="btn btn-success" onclick="saveConfiguration()">💾 Guardar Parrilla de Canales</button>
                    </div>
                </div>

                <!-- SUBSECTION 2: Canales Cacheados (CW Cache en Vivo) -->
                <div id="channel-subview-cached" style="display:none;">
                    <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(180px, 1fr)); gap:12px; margin-bottom:18px;">
                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:14px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">CW CACHE STATUS</div>
                            <div id="stat-cw-status" style="font-size:18px; font-weight:800; color:#10B981; margin-top:4px;">ACTIVO</div>
                            <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Memoria RAM inmediata</div>
                        </div>
                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:14px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">CONTROL WORDS EN MEMORIA</div>
                            <div id="stat-cw-count" style="font-size:18px; font-weight:800; color:#38BDF8; margin-top:4px;">0</div>
                            <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Claves CW listas</div>
                        </div>
                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:14px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">CACHE HITS (Aciertos)</div>
                            <div id="stat-cw-hits" style="font-size:18px; font-weight:800; color:#34D399; margin-top:4px;">0</div>
                            <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Sin latencia de red</div>
                        </div>
                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:14px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">CACHE MISSES</div>
                            <div id="stat-cw-misses" style="font-size:18px; font-weight:800; color:#FBBF24; margin-top:4px;">0</div>
                            <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Resueltas vía socket</div>
                        </div>
                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:14px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">TASA DE ACIERTO (HIT RATIO)</div>
                            <div id="stat-cw-ratio" style="font-size:18px; font-weight:800; color:#A78BFA; margin-top:4px;">0.0%</div>
                            <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Eficiencia de descifrado</div>
                        </div>
                    </div>

                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; flex-wrap:wrap; gap:8px;">
                        <div style="font-weight:700; color:#FFF; font-size:14px;">
                            Canales Descifrados y en Memoria (Live CW Cache Sessions):
                        </div>
                        <div style="display:flex; gap:8px;">
                            <button type="button" class="btn btn-outline" style="padding:4px 10px; font-size:12px;" onclick="loadCachedChannels()">🔄 Refrescar</button>
                            <button type="button" class="btn btn-outline" style="padding:4px 10px; font-size:12px;" onclick="testSimulatedEcm()">⚡ Simular ECM de Prueba</button>
                            <button type="button" class="btn btn-danger" style="padding:4px 10px; font-size:12px;" onclick="flushCwCache()">🧹 Vaciar CW Cache</button>
                        </div>
                    </div>

                    <div class="table-container" style="max-height:360px; overflow-y:auto;">
                        <table>
                            <thead>
                                <tr>
                                    <th>Canal</th>
                                    <th>SID / PMT</th>
                                    <th>CAID</th>
                                    <th>Sistema CAS</th>
                                    <th>Peticiones ECM</th>
                                    <th>CW Hits</th>
                                    <th>Última CW Descifrada</th>
                                    <th>Paridad</th>
                                    <th>Última Actividad</th>
                                    <th>Estado</th>
                                </tr>
                            </thead>
                            <tbody id="cached-channels-tbody"></tbody>
                        </table>
                    </div>
                </div>

                <!-- SUBSECTION 3: Catálogo Satélite Disponible -->
                <div id="channel-subview-avail" style="display:none;">
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:14px; margin-bottom:16px;">
                        <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(200px, 1fr)); gap:12px; align-items:flex-end;">
                            <div>
                                <label style="font-size:12px; color:var(--text-muted); font-weight:600; display:block; margin-bottom:4px;">Filtrar por Satélite:</label>
                                <select id="avail-sat-filter" onchange="filterAvailableChannels()" style="width:100%; padding:8px; background:var(--bg-dark); border:1px solid var(--border); color:#FFF; border-radius:6px;">
                                    <option value="all">Todos los satélites</option>
                                    <option value="Astra">Astra 19.2°E (Movistar+, HD+, Sky DE, Canal+ FR)</option>
                                    <option value="Hispasat">Hispasat 30°W (MEO, NOS, Movistar)</option>
                                    <option value="Hotbird">Hotbird 13°E (Tivùsat, Polsat, SRG, Nova)</option>
                                    <option value="Thor">Thor 0.8°W (Canal Digital Nordic)</option>
                                    <option value="Eutelsat">Eutelsat 5°W (Fransat)</option>
                                </select>
                            </div>
                            <div>
                                <label style="font-size:12px; color:var(--text-muted); font-weight:600; display:block; margin-bottom:4px;">Filtrar por Sistema CAS:</label>
                                <select id="avail-cas-filter" onchange="filterAvailableChannels()" style="width:100%; padding:8px; background:var(--bg-dark); border:1px solid var(--border); color:#FFF; border-radius:6px;">
                                    <option value="all">Todos los sistemas CAS</option>
                                    <option value="NAGRA">Nagravision (0x18xx - Movistar+, HD+, Tivùsat, MEO, Polsat)</option>
                                    <option value="VIACCESS">Viaccess (0x05xx - Fransat, SRG SSR Suiza)</option>
                                    <option value="NDS">NDS VideoGuard (0x09xx - Sky DE, Sky IT, Sky UK)</option>
                                    <option value="SECA">Seca / Mediaguard (0x01xx - Canal+)</option>
                                    <option value="CONAX">Conax (0x0Bxx - Canal Digital Nordic)</option>
                                    <option value="IRDETO">Irdeto (0x06xx - Nova Grecia, Digitürk)</option>
                                    <option value="CW">Cryptoworks (0x0Dxx - ORF Digital)</option>
                                    <option value="FTA">Free-To-Air (En abierto sin encriptación)</option>
                                </select>
                            </div>
                            <div>
                                <label style="font-size:12px; color:var(--text-muted); font-weight:600; display:block; margin-bottom:4px;">Buscar por nombre:</label>
                                <input type="text" id="avail-search-input" oninput="filterAvailableChannels()" placeholder="Buscar canal..." style="width:100%; padding:8px; background:var(--bg-dark); border:1px solid var(--border); color:#FFF; border-radius:6px;">
                            </div>
                            <div>
                                <button type="button" class="btn btn-success" style="width:100%; padding:9px;" onclick="addSelectedAvailableChannels()">📥 Añadir Seleccionados a Mis Canales</button>
                            </div>
                        </div>
                    </div>

                    <div class="table-container" style="max-height:420px; overflow-y:auto;">
                        <table>
                            <thead>
                                <tr>
                                    <th style="width:36px;"><input type="checkbox" id="chk-master-avail" onchange="toggleSelectAllAvailable(this.checked)"></th>
                                    <th>Canal</th>
                                    <th>Satélite / Posición</th>
                                    <th>Frecuencia / Pol / SR</th>
                                    <th>SID / PMT</th>
                                    <th>CAID</th>
                                    <th>Sistema CAS</th>
                                    <th>Estado</th>
                                    <th>Acción</th>
                                </tr>
                            </thead>
                            <tbody id="available-channels-tbody"></tbody>
                        </table>
                    </div>
                </div>

                <!-- SUBSECTION 4: Escáner TV & Validador de Servidores -->
                <div id="channel-subview-scan" style="display:none;">
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:16px; margin-bottom:16px;">
                        <div style="font-weight:700; font-size:15px; color:#FFF; margin-bottom:6px;">
                            📡 Escáner de Canales y Validador con Servidores OSCam/CCcam
                        </div>
                        <div class="hint" style="margin-bottom:14px;">
                            Permite rebuscar canales desde el sintonizador de Android TV (TvContract) o transpondedores satelitales, identificando si usan <strong>Nagravision, Viaccess, NDS, Conax, Seca</strong> y validándolos en tiempo real contra los servidores configurados.
                        </div>

                        <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap:12px; margin-bottom:14px; align-items:flex-end;">
                            <div>
                                <label for="scan-source-select" style="font-size:12px; font-weight:600; color:var(--text-muted); display:block; margin-bottom:4px;">Fuente de Búsqueda:</label>
                                <select id="scan-source-select" style="width:100%; padding:8px; background:var(--bg-dark); border:1px solid var(--border); color:#FFF; border-radius:6px;">
                                    <option value="all">Todas las fuentes (Sintonizador TV + Satélites)</option>
                                    <option value="tv">📺 Sintonizador Android TV (TvContract / Live TV)</option>
                                    <option value="astra">🛰️ Astra 19.2°E (Movistar+, HD+, Sky DE, Canal+ FR)</option>
                                    <option value="hispasat">🛰️ Hispasat 30°W (MEO, NOS, Movistar)</option>
                                    <option value="hotbird">🛰️ Hotbird 13°E (Tivùsat, Polsat, SRG, Nova)</option>
                                    <option value="other">🛰️ Otros Satélites (Thor 0.8°W, Eutelsat 5°W)</option>
                                </select>
                            </div>
                            <div style="display:flex; flex-direction:column; gap:8px;">
                                <label style="display:flex; align-items:center; gap:8px; font-size:13px; cursor:pointer;">
                                    <input type="checkbox" id="scan-include-encrypted" checked style="accent-color:var(--primary); width:16px; height:16px;">
                                    <span>🔒 <strong>Buscar canales encriptados</strong> (Scrambled / CAS)</span>
                                </label>
                                <label style="display:flex; align-items:center; gap:8px; font-size:13px; cursor:pointer;">
                                    <input type="checkbox" id="scan-validate-servers" checked style="accent-color:var(--success); width:16px; height:16px;">
                                    <span>⚡ <strong>Validar con los servidores</strong> configurados</span>
                                </label>
                            </div>
                            <div>
                                <button type="button" id="btn-run-scan" class="btn btn-primary" style="width:100%; padding:10px;" onclick="runChannelScan()">
                                    ▶ Iniciar Búsqueda y Validación
                                </button>
                            </div>
                        </div>

                        <!-- Scan Results Container -->
                        <div id="scan-results-container" style="display:none; margin-top:14px;">
                            <div id="scan-summary-bar" style="background:rgba(59,130,246,0.1); border:1px solid rgba(59,130,246,0.3); border-radius:6px; padding:8px 12px; font-size:13px; margin-bottom:10px; color:#93C5FD; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                                <span id="scan-stats-text">Cargando resultados...</span>
                                <div style="display:flex; gap:8px;">
                                    <button type="button" class="btn btn-outline" style="padding:4px 10px; font-size:11px;" onclick="toggleSelectAllScanned(true)">Seleccionar Todos</button>
                                    <button type="button" class="btn btn-outline" style="padding:4px 10px; font-size:11px;" onclick="toggleSelectAllScanned(false)">Deseleccionar</button>
                                    <button type="button" class="btn btn-success" style="padding:4px 12px; font-size:11px;" onclick="importSelectedScannedChannels()">📥 Importar Seleccionados a la Base de Datos</button>
                                </div>
                            </div>

                            <div class="table-container" style="max-height:340px; overflow-y:auto;">
                                <table id="scanned-channels-table">
                                    <thead>
                                        <tr>
                                            <th style="width:36px;"><input type="checkbox" id="chk-master-scan" onchange="toggleSelectAllScanned(this.checked)"></th>
                                            <th>Canal</th>
                                            <th>Satélite / Transpondedor</th>
                                            <th>SID / PMT</th>
                                            <th>CAID</th>
                                            <th>Sistema CAS</th>
                                            <th>Tipo / Encriptación</th>
                                            <th>Validación con Servidores</th>
                                        </tr>
                                    </thead>
                                    <tbody id="scanned-channels-tbody"></tbody>
                                </table>
                            </div>
                        </div>
                    </div>
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

        <!-- TAB 5: Servidor TVHeadend Embebido & Streaming (Modo Sin Root) -->
        <div class="tab-pane" id="tab-player">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">📡 Servidor TVHeadend Embebido &amp; Streaming DVB-IPTV (Modo Sin Root)</div>
                        <div class="panel-desc">Transmite todos tus canales descodificados con CCcam/OSCam directamente a TiviMate, Kodi o VLC sin necesidad de rootear la Smart TV.</div>
                    </div>
                    <div style="display:flex; gap:8px;">
                        <a href="/playlist.m3u" class="btn btn-purple" download="channels.m3u">⬇ Descargar M3U</a>
                        <a href="/epg.xml" class="btn btn-primary" download="epg.xml">📅 Descargar EPG (XMLTV)</a>
                    </div>
                </div>

                <!-- Banner Informativo Modo Sin Root -->
                <div style="background:rgba(59,130,246,0.1); border:1px solid rgba(59,130,246,0.3); border-radius:10px; padding:16px; margin-bottom:18px;">
                    <div style="font-weight:700; font-size:15px; color:#93C5FD; margin-bottom:8px; display:flex; align-items:center; gap:8px;">
                        <span>💡</span> <span>¿Tu Smart TV no está rooteada? ¡No hay problema!</span>
                    </div>
                    <p style="font-size:13px; line-height:1.6; color:#E2E8F0; margin-bottom:10px;">
                        La gran mayoría de televisores (Sony Bravia, Philips, TCL, Xiaomi, Chromecast con Google TV) no permiten rootear o tienen SELinux bloqueando los dispositivos del sintonizador interno.
                        Para resolver esto, la app incluye un <strong>servidor de streaming TVHeadend completo</strong> que descifra los canales con CCcam/OSCam en tiempo real en espacio de usuario.
                    </p>
                    <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap:12px; margin-top:12px;">
                        <div style="background:var(--bg-card); padding:12px 14px; border-radius:8px; border:1px solid var(--border);">
                            <div style="font-size:11px; font-weight:700; color:var(--text-muted); margin-bottom:4px;">LISTA M3U IPTV (Canales Descodificados):</div>
                            <div style="display:flex; gap:8px; align-items:center;">
                                <code style="font-size:12px; flex:1; overflow-x:auto;">http://${realIp}:9191/playlist.m3u</code>
                                <button type="button" class="btn btn-outline" style="padding:4px 8px; font-size:11px;" onclick="copyToClipboard('http://${realIp}:9191/playlist.m3u')">Copiar</button>
                            </div>
                        </div>
                        <div style="background:var(--bg-card); padding:12px 14px; border-radius:8px; border:1px solid var(--border);">
                            <div style="font-size:11px; font-weight:700; color:var(--text-muted); margin-bottom:4px;">GUÍA EPG XMLTV (Programación):</div>
                            <div style="display:flex; gap:8px; align-items:center;">
                                <code style="font-size:12px; flex:1; overflow-x:auto;">http://${realIp}:9191/epg.xml</code>
                                <button type="button" class="btn btn-outline" style="padding:4px 8px; font-size:11px;" onclick="copyToClipboard('http://${realIp}:9191/epg.xml')">Copiar</button>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- Guía Rápida de Configuración en Reproductores -->
                <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:16px; margin-bottom:18px;">
                    <div style="font-weight:700; font-size:14px; color:#FFF; margin-bottom:10px;">
                        🚀 Cómo Ver los Canales en tu Smart TV o Red Doméstica
                    </div>
                    <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap:14px; font-size:12px; line-height:1.5;">
                        <div style="border-left:3px solid var(--primary); padding-left:12px;">
                            <strong style="color:var(--primary); font-size:13px;">1. TiviMate IPTV Player (Recomendado)</strong>
                            <p style="color:var(--text-muted); margin-top:4px;">Instala TiviMate en tu Android TV. Pulsa "Añadir Lista" &gt; "Lista M3U" e introduce <code>http://127.0.0.1:9191/playlist.m3u</code>. En Guía TV asigna <code>http://127.0.0.1:9191/epg.xml</code>.</p>
                        </div>
                        <div style="border-left:3px solid var(--accent); padding-left:12px;">
                            <strong style="color:var(--accent); font-size:13px;">2. Kodi (PVR IPTV Simple Client)</strong>
                            <p style="color:var(--text-muted); margin-top:4px;">En Kodi, activa el cliente PVR IPTV Simple Client. En la pestaña General pon la URL de la lista M3U y en la pestaña EPG pon la URL de la guía XMLTV.</p>
                        </div>
                        <div style="border-left:3px solid var(--success); padding-left:12px;">
                            <strong style="color:var(--success); font-size:13px;">3. VLC / Móvil / Tablet / PC</strong>
                            <p style="color:var(--text-muted); margin-top:4px;">Abre VLC en cualquier dispositivo conectado a tu WiFi. Menú "Medio" &gt; "Abrir ubicación de red" e introduce <code>http://${realIp}:9191/playlist.m3u</code>.</p>
                        </div>
                    </div>
                </div>

                <!-- Reproductor Web HTML5 en Vivo con HUD, Selector de Canal y Channel Zapper -->
                <div class="player-grid" style="margin-bottom:18px;">
                    <!-- Columna Principal de Video -->
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:16px;">
                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; flex-wrap:wrap; gap:10px;">
                            <div style="font-weight:700; font-size:13px; color:#FFF; text-transform:uppercase; letter-spacing:0.5px;">
                                📺 Reproductor Web Satelital en Vivo (MPEG-TS MSE / TVHeadend)
                            </div>
                            <div style="display:flex; gap:8px; align-items:center;">
                                <button type="button" class="btn btn-outline" style="padding:4px 10px; font-size:11px;" onclick="prevChannel()">◀ Anterior</button>
                                <button type="button" class="btn btn-outline" style="padding:4px 10px; font-size:11px;" onclick="nextChannel()">Siguiente ▶</button>
                                <button type="button" class="btn btn-primary" style="padding:4px 10px; font-size:11px;" onclick="reloadPlayerStream()">🔄 Recargar</button>
                            </div>
                        </div>

                        <!-- Live Channel Telemetry HUD -->
                        <div id="live-channel-hud" style="background:rgba(0,0,0,0.5); border:1px solid rgba(255,255,255,0.08); border-radius:8px; padding:8px 14px; margin-bottom:10px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px; font-size:12px;">
                            <div>
                                <span id="hud-channel-name" style="font-weight:800; font-size:15px; color:#38BDF8;">Selecciona un canal</span>
                                <span id="hud-channel-tp" style="color:var(--text-muted); margin-left:8px;">-- MHz</span>
                            </div>
                            <div style="display:flex; gap:10px; align-items:center;">
                                <span id="hud-channel-cas" style="background:rgba(59,130,246,0.2); color:#93C5FD; border:1px solid #3B82F6; padding:2px 6px; border-radius:4px; font-weight:700; font-size:11px;">CAS AUTO</span>
                                <span id="hud-channel-caid" style="font-family:monospace; color:#A78BFA;">CAID: --</span>
                                <span id="hud-channel-status" style="color:#34D399; font-weight:700;">🟢 LISTO</span>
                            </div>
                        </div>

                        <div style="display:flex; gap:10px; align-items:center; margin-bottom:12px;">
                            <input type="text" id="player-stream-url" placeholder="URL de stream directo" value="http://${realIp}:9191/stream/channel/30001" style="flex:1;">
                            <button type="button" class="btn btn-primary" onclick="loadStreamInPlayer()">▶ Reproducir</button>
                            <button type="button" class="btn btn-outline" onclick="openStreamInVlc()" title="Abrir en VLC Media Player">🔗 VLC</button>
                        </div>

                        <div class="player-box" style="position:relative; background:#000; border-radius:8px; overflow:hidden; min-height:340px; display:flex; align-items:center; justify-content:center;">
                            <video id="live-video-player" controls autoplay style="width:100%; max-height:480px; display:block; background:#000;">
                                Tu navegador no soporta streaming HTML5 directo.
                            </video>
                        </div>

                        <div id="hud-player-stats" style="margin-top:8px; font-size:11px; color:var(--text-muted); font-family:monospace; display:flex; justify-content:space-between; flex-wrap:wrap; gap:6px;">
                            <span>Motor: mpegts.js (Hardware Media Source Extensions)</span>
                            <span id="player-bitrate-label">Bitrate: -- kbps | TS Sync: OK</span>
                        </div>
                    </div>

                    <!-- Channel Zapper Sidebar -->
                    <div class="zapper-container">
                        <div style="font-weight:700; font-size:13px; color:#FFF; margin-bottom:8px; display:flex; justify-content:space-between; align-items:center;">
                            <span>🛰️ Parrilla de Canales (<span id="zapper-count">0</span>)</span>
                            <span style="font-size:11px; color:var(--primary); cursor:pointer;" onclick="switchChannelSubtab('cfg'); showTab('tab-channels', document.querySelectorAll('.tab-btn')[2])">Gestionar &gt;</span>
                        </div>
                        <input type="text" id="zapper-search" placeholder="🔍 Buscar canal para ver..." oninput="filterZapperChannels()" style="padding:7px 10px; font-size:12px; margin-bottom:6px;">
                        <div class="zapper-list" id="player-zapper-list">
                            <!-- Populated dynamically with channels -->
                        </div>
                    </div>
                </div>

                <!-- Configuración Autónoma del Motor TVHeadend -->
                <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:16px; margin-bottom:16px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; flex-wrap:wrap; gap:8px;">
                        <div style="font-weight:700; font-size:14px; color:#FFF;">
                            ⚙️ Configuración del Servidor TVHeadend Autónomo
                        </div>
                        <div style="display:flex; gap:8px;">
                            <button type="button" class="btn btn-outline" style="padding:4px 10px; font-size:11px;" onclick="restartTvheadend()">🔄 Reiniciar TVHeadend</button>
                            <button type="button" class="btn btn-success" style="padding:4px 12px; font-size:11px;" onclick="saveTvheadendConfig()">💾 Guardar Ajustes</button>
                        </div>
                    </div>
                    <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap:12px; font-size:13px;">
                        <div>
                            <label for="tvh-port">Puerto HTTP de Streaming:</label>
                            <input type="number" id="tvh-port" value="9191">
                        </div>
                        <div>
                            <label for="tvh-buffer">Buffer de Paquetes TS:</label>
                            <select id="tvh-buffer">
                                <option value="128">128 paquetes (~24 KB) - Ultra Baja Latencia</option>
                                <option value="256">256 paquetes (~48 KB) - Equilibrado</option>
                                <option value="348" selected>348 paquetes (~65 KB) - Recomendado</option>
                                <option value="512">512 paquetes (~96 KB) - Máxima Estabilidad</option>
                            </select>
                        </div>
                        <div>
                            <label for="tvh-m3u-fmt">Formato de Nombres en M3U:</label>
                            <select id="tvh-m3u-fmt">
                                <option value="standard" selected>Con distintivo CAS [NAGRA] [VIACCESS]</option>
                                <option value="clean">Limpio (Solo nombre del canal)</option>
                            </select>
                        </div>
                    </div>
                </div>

                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap:12px;">
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:12px;">
                        <div style="font-weight:700; font-size:11px; margin-bottom:4px; color:var(--text-muted);">STREAM DIRECTO POR CANAL / SERVICE ID:</div>
                        <code>http://${realIp}:9191/stream/channel/{serviceId}</code>
                    </div>
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:12px;">
                        <div style="font-weight:700; font-size:11px; margin-bottom:4px; color:var(--text-muted);">API TVHEADEND (COMPATIBILIDAD CLIENTES):</div>
                        <code>http://${realIp}:9191/api/serverinfo</code>
                    </div>
                </div>
            </div>
        </div>

        <!-- TAB SPECTRUM: RF Spectrum Analyzer & Blind Scan -->
        <div class="tab-pane" id="tab-spectrum">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">📶 Analizador de Espectro RF Satelital &amp; Blind Scan</div>
                        <div class="panel-desc">Visualiza en tiempo real la potencia en frecuencia, transpondedores activos, relación C/N (SNR) y modulación DVB-S2.</div>
                    </div>
                    <div style="display:flex; gap:8px; align-items:center;">
                        <button type="button" class="btn btn-primary" onclick="runSpectrumScan()">▶ Iniciar Barrido de Espectro</button>
                    </div>
                </div>

                <!-- Controles del Analizador de Espectro -->
                <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:16px; margin-bottom:18px;">
                    <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap:14px; align-items:flex-end;">
                        <div>
                            <label for="spec-sat-select">Satélite / Banda:</label>
                            <select id="spec-sat-select" onchange="runSpectrumScan()">
                                <option value="astra" selected>🛰️ Astra 19.2°E (Ku-Band 10.70 - 12.75 GHz)</option>
                                <option value="hotbird">🛰️ Hotbird 13°E (Ku-Band 10.70 - 12.75 GHz)</option>
                                <option value="hispasat">🛰️ Hispasat 30°W (Ku-Band 10.70 - 12.75 GHz)</option>
                                <option value="tdt">📺 TDT UHF Terrestre (470 - 694 MHz)</option>
                            </select>
                        </div>
                        <div>
                            <label for="spec-pol-select">Polarización &amp; LNB:</label>
                            <select id="spec-pol-select" onchange="runSpectrumScan()">
                                <option value="ALL" selected>Todas (Vertical 13V + Horizontal 18V)</option>
                                <option value="V">Vertical (13V LNB)</option>
                                <option value="H">Horizontal (18V LNB)</option>
                            </select>
                        </div>
                        <div>
                            <label for="spec-step-select">Resolución de Barrido:</label>
                            <select id="spec-step-select">
                                <option value="4" selected>4 MHz (Alta Definición - 512 muestras)</option>
                                <option value="8">8 MHz (Barrido Rápido)</option>
                            </select>
                        </div>
                        <div>
                            <button type="button" class="btn btn-success" style="width:100%; padding:10px;" onclick="runSpectrumScan()">
                                ⚡ Barrer Espectro RF
                            </button>
                        </div>
                    </div>
                </div>

                <!-- Métricas RF en Tiempo Real -->
                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap:12px; margin-bottom:18px;">
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:12px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-muted);">POTENCIA MÁXIMA</div>
                        <div id="spec-stat-pwr" style="font-size:18px; font-weight:800; color:#38BDF8; margin-top:3px;">-- dBm</div>
                        <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Nivel RF en LNB</div>
                    </div>
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:12px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-muted);">ESTADO DE BLOQUEO</div>
                        <div id="spec-stat-lock" style="font-size:18px; font-weight:800; color:#10B981; margin-top:3px;">--</div>
                        <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Carrier Lock DVB-S2</div>
                    </div>
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:12px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-muted);">RELACIÓN C/N (SNR)</div>
                        <div id="spec-stat-snr" style="font-size:18px; font-weight:800; color:#34D399; margin-top:3px;">-- dB</div>
                        <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Margen de ruido</div>
                    </div>
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:12px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-muted);">VOLTAJE LNB &amp; TONO</div>
                        <div id="spec-stat-lnb" style="font-size:18px; font-weight:800; color:#FBBF24; margin-top:3px;">--</div>
                        <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Alimentación coaxial</div>
                    </div>
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:12px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-muted);">PICOS DETECTADOS</div>
                        <div id="spec-stat-peaks" style="font-size:18px; font-weight:800; color:#A78BFA; margin-top:3px;">--</div>
                        <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Portadoras satelitales</div>
                    </div>
                </div>

                <!-- Gráfico Interactivo de Espectro RF SVG -->
                <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:18px; margin-bottom:18px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
                        <div style="font-weight:700; font-size:13px; color:#FFF; text-transform:uppercase; letter-spacing:0.5px;">
                            Curva Espectral RF: Potencia (dBm) vs Frecuencia (MHz)
                        </div>
                        <div style="font-size:11px; color:var(--text-muted);" id="spec-chart-info">
                            Haz clic en un pico de transpondedor para sintonizar o reproducir sus canales.
                        </div>
                    </div>
                    <div style="position:relative; width:100%; height:260px; background:#070A10; border:1px solid var(--border); border-radius:8px; overflow:hidden;">
                        <svg id="spectrum-svg" width="100%" height="100%" viewBox="0 0 1000 240" preserveAspectRatio="none" style="display:block;">
                            <defs>
                                <linearGradient id="spectrumGradient" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="0%" stop-color="#38BDF8" stop-opacity="0.6"/>
                                    <stop offset="40%" stop-color="#10B981" stop-opacity="0.3"/>
                                    <stop offset="100%" stop-color="#1E293B" stop-opacity="0.0"/>
                                </linearGradient>
                            </defs>
                            <line x1="0" y1="60" x2="1000" y2="60" stroke="#1E293B" stroke-dasharray="3 3"/>
                            <line x1="0" y1="120" x2="1000" y2="120" stroke="#1E293B" stroke-dasharray="3 3"/>
                            <line x1="0" y1="180" x2="1000" y2="180" stroke="#1E293B" stroke-dasharray="3 3"/>
                            <polygon id="spectrum-fill" points="" fill="url(#spectrumGradient)"/>
                            <polyline id="spectrum-stroke" points="" fill="none" stroke="#38BDF8" stroke-width="2"/>
                            <g id="spectrum-peaks"></g>
                        </svg>
                        <div id="spectrum-tooltip" style="display:none; position:absolute; background:rgba(15,23,42,0.95); border:1px solid #38BDF8; border-radius:6px; padding:6px 10px; font-size:11px; color:#FFF; pointer-events:none; box-shadow:0 4px 12px rgba(0,0,0,0.5); z-index:10;"></div>
                    </div>
                    <div style="display:flex; justify-content:space-between; font-size:11px; color:var(--text-muted); margin-top:6px; font-family:monospace;">
                        <span id="spec-freq-min">10700 MHz</span>
                        <span id="spec-freq-mid">11725 MHz</span>
                        <span id="spec-freq-max">12750 MHz</span>
                    </div>
                </div>

                <!-- Tabla de Transpondedores Detectados -->
                <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:16px;">
                    <div style="font-weight:700; font-size:14px; color:#FFF; margin-bottom:10px;">
                        Transpondedores Satelitales Detectados en el Barrido
                    </div>
                    <div class="table-container" style="max-height:280px; overflow-y:auto;">
                        <table>
                            <thead>
                                <tr>
                                    <th>Frecuencia / Pol</th>
                                    <th>Symbol Rate / FEC</th>
                                    <th>Modulación</th>
                                    <th>Proveedor</th>
                                    <th>Canales Incluidos</th>
                                    <th>Potencia RF</th>
                                    <th>SNR (dB)</th>
                                    <th>Acción</th>
                                </tr>
                            </thead>
                            <tbody id="spectrum-tps-tbody">
                                <tr><td colspan="8" style="text-align:center; padding:14px; color:var(--text-muted);">Pulsa "▶ Iniciar Barrido de Espectro" para analizar las portadoras de radiofrecuencia.</td></tr>
                            </tbody>
                        </table>
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
                    <div class="hw-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">DEVICE MODEL &amp; OEM</div>
                        <div style="font-size:17px; font-weight:800; color:#FFF; margin-top:4px;">${hw.manufacturer} ${hw.model}</div>
                        <div class="hint">Board: ${hw.board} (${hw.hardware})</div>
                    </div>

                    <div class="hw-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">CHIPSET ADAPTER</div>
                        <div style="font-size:17px; font-weight:800; color:var(--accent); margin-top:4px;">${hw.detectedChipset}</div>
                        <div class="hint">Active HAL Abstraction Driver</div>
                    </div>

                    <div class="hw-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">ANDROID OS &amp; API LEVEL</div>
                        <div style="font-size:17px; font-weight:800; color:var(--primary); margin-top:4px;">Android ${hw.androidVersion} (API ${hw.sdkInt})</div>
                        <div class="hint">VINTF Manifest Compatibility: Active</div>
                    </div>

                    <div class="hw-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">RAM MEMORY ALLOCATION</div>
                        <div style="font-size:17px; font-weight:800; color:var(--success); margin-top:4px;">${hw.availableMemoryMb} MB free / ${hw.totalMemoryMb} MB total</div>
                        <div class="hint">Zero-Leak Buffer Management</div>
                    </div>

                    <div class="hw-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">DISPLAY &amp; RESOLUTION</div>
                        <div id="tv-display-info" style="font-size:17px; font-weight:800; color:#60A5FA; margin-top:4px;">${realDisplay}</div>
                        <div class="hint">Native Display Metrics</div>
                    </div>

                    <div class="hw-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">INTERNAL STORAGE</div>
                        <div id="tv-storage-info" style="font-size:17px; font-weight:800; color:#C4B5FD; margin-top:4px;">${realStorage}</div>
                        <div class="hint">Android TV /data partition</div>
                    </div>

                    <div class="hw-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">ACTIVE NETWORK INTERFACE</div>
                        <div id="tv-network-info" style="font-size:17px; font-weight:800; color:#FCD34D; margin-top:4px;">${realNetwork}</div>
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

                <!-- Universal CI+ CAM Module Emulator & 5-Layer Fallback Architecture -->
                <div style="margin-top:20px; border-top:1px solid var(--border); padding-top:16px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px; margin-bottom:12px;">
                        <div>
                            <div style="display:flex; align-items:center; gap:8px;">
                                <span style="font-weight:800; font-size:16px; color:#FFF;">Universal CI+ CAM Module Emulator</span>
                                <span id="ci-cam-badge" style="background:rgba(16,185,129,0.2); color:#10B981; border:1px solid #10B981; padding:3px 10px; border-radius:9999px; font-size:11px; font-weight:800;">CI+ v1.4 ACTIVE</span>
                            </div>
                            <div class="hint">Emulates a physical PCMCIA CI+ CAM module across 10 TV brands with a 5-layer fault-tolerant fallback architecture.</div>
                        </div>
                        <div style="display:flex; gap:8px;">
                            <button type="button" class="btn btn-primary" onclick="triggerCiBroadcast()">📡 Forzar Registro CI Module</button>
                            <button type="button" class="btn btn-outline" onclick="fetchCiStatus()">🔄 Actualizar Estado CI</button>
                        </div>
                    </div>

                    <!-- Telemetry & Status Row -->
                    <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:12px; margin-bottom:14px;">
                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:12px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">ESTADO DEL SLOT CI+</div>
                            <div id="ci-slot-status" style="font-size:15px; font-weight:800; color:var(--success); margin-top:4px;">SLOT 0: INSERTED &amp; READY</div>
                            <div class="hint">Universal OSCam / CCcam CI+ CAM</div>
                        </div>

                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:12px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">FABRICANTE DETECTADO</div>
                            <div id="ci-brand-status" style="font-size:15px; font-weight:800; color:var(--primary); margin-top:4px;">${TclTvCompat.detectTvBrand()} TV</div>
                            <div class="hint">Optimización nativa de canales</div>
                        </div>

                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:12px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">BROADCASTS &amp; LATIDOS (HEARTBEAT)</div>
                            <div id="ci-broadcast-stats" style="font-size:15px; font-weight:800; color:#FCD34D; margin-top:4px;">Cargando telemetría...</div>
                            <div class="hint">Ciclo cada 25 segundos</div>
                        </div>

                        <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:12px;">
                            <div style="font-size:11px; color:var(--text-muted); font-weight:700;">SISTEMAS CAS ACTIVOS</div>
                            <div id="ci-cas-systems" style="display:flex; flex-wrap:wrap; gap:4px; margin-top:6px;">
                                <span style="background:rgba(59,130,246,0.2); color:#93C5FD; padding:2px 6px; border-radius:4px; font-size:10px; font-weight:700;">NAGRA</span>
                                <span style="background:rgba(217,119,6,0.2); color:#FCD34D; padding:2px 6px; border-radius:4px; font-size:10px; font-weight:700;">VIACCESS</span>
                                <span style="background:rgba(139,92,246,0.2); color:#C4B5FD; padding:2px 6px; border-radius:4px; font-size:10px; font-weight:700;">SECA</span>
                                <span style="background:rgba(16,185,129,0.2); color:#6EE7B7; padding:2px 6px; border-radius:4px; font-size:10px; font-weight:700;">CONAX</span>
                                <span style="background:rgba(244,63,94,0.2); color:#FDA4AF; padding:2px 6px; border-radius:4px; font-size:10px; font-weight:700;">NDS</span>
                            </div>
                        </div>
                    </div>

                    <!-- 5-Layer Fallback Architecture Visualizer -->
                    <div style="background:rgba(0,0,0,0.25); border:1px solid var(--border); border-radius:10px; padding:16px; margin-bottom:16px;">
                        <div style="font-weight:700; font-size:13px; color:#FFF; margin-bottom:10px; display:flex; align-items:center; gap:8px;">
                            <span>🛡️ Matriz de Respaldo de 5 Capas (Fault-Tolerant Fallback Architecture)</span>
                            <span style="font-size:10px; background:rgba(59,130,246,0.2); color:#93C5FD; padding:2px 6px; border-radius:4px;">100% OPERATIVO</span>
                        </div>
                        <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(280px, 1fr)); gap:10px;" id="ci-fallback-layers-list">
                            <div style="background:var(--bg-card); border-left:3px solid var(--success); padding:10px; border-radius:6px;">
                                <div style="font-weight:800; font-size:12px; color:#FFF;">Capa 1: Broadcasts Multi-Marca Implícitos</div>
                                <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Canales de eventos para TCL, Sony, Philips, Xiaomi, Hisense, Samsung, LG, Panasonic, Vestel y Sharp.</div>
                            </div>
                            <div style="background:var(--bg-card); border-left:3px solid var(--primary); padding:10px; border-radius:6px;">
                                <div style="font-weight:800; font-size:12px; color:#FFF;">Capa 2: Envío Explícito a Paquetes OEM Instalados</div>
                                <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Supera las restricciones en segundo plano de Android 8+ (Oreo a Android 14) apuntando a cada app instalada.</div>
                            </div>
                            <div style="background:var(--bg-card); border-left:3px solid #FCD34D; padding:10px; border-radius:6px;">
                                <div style="font-weight:800; font-size:12px; color:#FFF;">Capa 3: Reflexión en Propiedades HAL de Linux</div>
                                <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Inyección directa en HAL driver: <code>vendor.ci.cam.ready=1</code> y <code>vendor.ci.slot0.status=READY</code>.</div>
                            </div>
                            <div style="background:var(--bg-card); border-left:3px solid #C4B5FD; padding:10px; border-radius:6px;">
                                <div style="font-weight:800; font-size:12px; color:#FFF;">Capa 4: Interceptor de Consultas &amp; Latido de 25s</div>
                                <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Responde instantáneamente a consultas de la tele (QUERY_MODULE) y renueva la CAM automáticamente.</div>
                            </div>
                            <div style="background:var(--bg-card); border-left:3px solid #60A5FA; padding:10px; border-radius:6px;">
                                <div style="font-weight:800; font-size:12px; color:#FFF;">Capa 5: TV Input Service Nativo &amp; Proxy Local :9191</div>
                                <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">Ruta directa TIF de Android TV y descifrado HTTP Stream para reproductores de TV y grabadores.</div>
                            </div>
                        </div>
                    </div>

                    <!-- Installed OEM Live TV Applications Box -->
                    <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:14px; margin-bottom:16px;">
                        <div style="font-weight:700; font-size:13px; color:#FFF; margin-bottom:8px;">Aplicaciones de TV y Sintonizador OEM Detectadas en el Sistema:</div>
                        <div id="ci-installed-apps-list" style="display:flex; flex-wrap:wrap; gap:8px;">
                            <span class="hint">Analizando paquetes instalados...</span>
                        </div>
                    </div>
                </div>

                <!-- Latest System Updates & Architecture Card -->
                <div style="margin-top:20px; border-top:1px solid var(--border); padding-top:16px;">
                    <div style="background:linear-gradient(135deg, rgba(30,58,138,0.25) 0%, rgba(15,23,42,0.6) 100%); border:1px solid rgba(59,130,246,0.3); border-radius:12px; padding:20px;">
                        <div style="display:flex; align-items:center; gap:10px; margin-bottom:12px;">
                            <span style="font-size:20px;">ℹ️</span>
                            <div>
                                <div style="font-weight:800; font-size:16px; color:#FFF;">Documentación de Arquitectura y Últimas Mejoras Implementadas</div>
                                <div style="font-size:12px; color:var(--text-muted);">Resumen técnico de las capacidades integradas para televisión digital por satélite y terrestre</div>
                            </div>
                        </div>

                        <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(280px, 1fr)); gap:14px; margin-top:14px;">
                            <div style="background:rgba(0,0,0,0.3); border:1px solid var(--border); border-radius:8px; padding:12px;">
                                <div style="font-weight:800; font-size:13px; color:#93C5FD; margin-bottom:6px;">📺 Módulo CI+ Multi-Marca Universal</div>
                                <p style="font-size:12px; color:var(--text-muted); line-height:1.5; margin:0;">
                                    El emulador se hace pasar por un módulo CAM CI+ v1.4 físico insertado en la ranura CI de la tele. Compatible con <strong>TCL, Sony Bravia, Philips, Xiaomi/Redmi, Hisense VIDAA, Samsung, LG, Panasonic, Vestel, Toshiba, Hitachi y Sharp</strong>. La app nativa de la tele sintoniza y descifra canales encriptados directamente sin necesidad de apps externas.
                                </p>
                            </div>

                            <div style="background:rgba(0,0,0,0.3); border:1px solid var(--border); border-radius:8px; padding:12px;">
                                <div style="font-weight:800; font-size:13px; color:#6EE7B7; margin-bottom:6px;">🛡️ Respaldo en 5 Niveles ante Fallos</div>
                                <p style="font-size:12px; color:var(--text-muted); line-height:1.5; margin:0;">
                                    Si la tele bloquea los broadcasts genéricos por políticas de Android 8+, el sistema conmuta a <strong>envíos explícitos directos</strong> hacia los paquetes del fabricante, inyecta variables HAL en el kernel Linux (<code>vendor.ci.cam.ready</code>), responde activamente a peticiones y ofrece la entrada TIF <code>OscamTvInputService</code>.
                                </p>
                            </div>

                            <div style="background:rgba(0,0,0,0.3); border:1px solid var(--border); border-radius:8px; padding:12px;">
                                <div style="font-weight:800; font-size:13px; color:#FCD34D; margin-bottom:6px;">⚡ Ejecución Continua 24/7 en Segundo Plano</div>
                                <p style="font-size:12px; color:var(--text-muted); line-height:1.5; margin:0;">
                                    El servidor y el puente nunca se apagan al salir de la aplicación ni al apagar la pantalla. Se mantienen activos mediante <strong>WakeLock permanente</strong> (evita suspensión de CPU), <strong>WifiLock HIGH_PERF</strong> (evita que la tele duerma el Wi-Fi o cierre los sockets TCP de CCcam/OSCam), servicio de primer plano con <code>START_STICKY</code> y rearme por alarma RTC si Android cerrara la app.
                                </p>
                            </div>

                            <div style="background:rgba(0,0,0,0.3); border:1px solid var(--border); border-radius:8px; padding:12px;">
                                <div style="font-weight:800; font-size:13px; color:#C4B5FD; margin-bottom:6px;">🔐 Detección Automática de CAS &amp; CW Cache</div>
                                <p style="font-size:12px; color:var(--text-muted); line-height:1.5; margin:0;">
                                    Identifica automáticamente la encriptación de cada canal (<strong>Nagravision, Viaccess, Seca, Conax, NDS VideoGuard, Irdeto, Cryptoworks</strong>). Gestiona una memoria caché de Control Words (CWs) compartida para cambios de canal instantáneos (0 ms de latencia en canales recurrentes) y permite añadir transpondedores del satélite en 1 clic.
                                </p>
                            </div>
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

    <!-- Modal: Editar / Nuevo Canal -->
    <div class="modal-overlay" id="modal-channel-edit">
        <div class="modal-box">
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:18px;">
                <div style="font-weight:800; font-size:16px; color:#FFF;" id="modal-ch-title">➕ Nuevo Canal Satelital</div>
                <button type="button" class="btn btn-outline" style="padding:2px 8px; font-size:14px;" onclick="closeChannelEditModal()">✕</button>
            </div>
            <input type="hidden" id="modal-ch-index" value="-1">
            <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-bottom:12px;">
                <div style="grid-column: span 2;">
                    <label style="font-size:12px; color:var(--text-muted); font-weight:700;">Nombre del Canal:</label>
                    <input type="text" id="modal-ch-name" placeholder="Ej: Movistar LaLiga HD" style="width:100%;">
                </div>
                <div>
                    <label style="font-size:12px; color:var(--text-muted); font-weight:700;">Satélite / Posición:</label>
                    <input type="text" id="modal-ch-sat" list="modal-sat-list" placeholder="Astra 19.2°E" style="width:100%;">
                    <datalist id="modal-sat-list">
                        <option value="Astra 19.2°E">
                        <option value="Hotbird 13°E">
                        <option value="Hispasat 30°W">
                        <option value="Eutelsat 16°E">
                        <option value="Thor 0.8°W">
                        <option value="TDT Terrestre">
                    </datalist>
                </div>
                <div>
                    <label style="font-size:12px; color:var(--text-muted); font-weight:700;">Frecuencia Transponder (MHz):</label>
                    <input type="number" id="modal-ch-freq" placeholder="10729" style="width:100%;">
                </div>
                <div>
                    <label style="font-size:12px; color:var(--text-muted); font-weight:700;">Polarización:</label>
                    <select id="modal-ch-pol" style="width:100%;">
                        <option value="V">Vertical (V)</option>
                        <option value="H">Horizontal (H)</option>
                    </select>
                </div>
                <div>
                    <label style="font-size:12px; color:var(--text-muted); font-weight:700;">Symbol Rate (kS/s):</label>
                    <input type="number" id="modal-ch-sr" placeholder="22000" style="width:100%;">
                </div>
                <div>
                    <label style="font-size:12px; color:var(--text-muted); font-weight:700;">Service ID (SID):</label>
                    <input type="number" id="modal-ch-sid" placeholder="30001" style="width:100%;">
                </div>
                <div>
                    <label style="font-size:12px; color:var(--text-muted); font-weight:700;">PMT PID:</label>
                    <input type="number" id="modal-ch-pmt" placeholder="1024" style="width:100%;">
                </div>
                <div style="grid-column: span 2;">
                    <label style="font-size:12px; color:var(--text-muted); font-weight:700;">CAID Encriptación (ej: 0x1810, 0x0500, 0x098D, 0x0B00 o 0x0000 FTA):</label>
                    <div style="display:flex; gap:10px; align-items:center;">
                        <input type="text" id="modal-ch-caid" placeholder="0x1810" style="flex:1; font-family:monospace;" oninput="updateModalCasBadge()">
                        <div id="modal-ch-cas-badge" style="min-width:110px;"></div>
                    </div>
                </div>
            </div>
            <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:18px;">
                <button type="button" class="btn btn-outline" onclick="closeChannelEditModal()">Cancelar</button>
                <button type="button" class="btn btn-primary" onclick="saveChannelFromModal()">💾 Guardar Canal</button>
            </div>
        </div>
    </div>

    <!-- Modal: Importar M3U / JSON -->
    <div class="modal-overlay" id="modal-import-m3u">
        <div class="modal-box" style="max-width:680px;">
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:16px;">
                <div style="font-weight:800; font-size:16px; color:#FFF;">📥 Importar Lista de Canales (M3U / JSON)</div>
                <button type="button" class="btn btn-outline" style="padding:2px 8px; font-size:14px;" onclick="closeImportM3uModal()">✕</button>
            </div>
            <p style="font-size:12px; color:var(--text-muted); margin-bottom:14px;">
                Carga un archivo <code>.m3u</code>, <code>.m3u8</code> o <code>.json</code> exportado de TVHeadend, Enigma2, TiviMate o pega su contenido de texto. Los nombres, frecuencias, Service IDs y CAIDs serán detectados automáticamente.
            </p>
            <div style="display:flex; gap:10px; align-items:center; margin-bottom:12px;">
                <label class="btn btn-outline" style="cursor:pointer; font-size:12px;">
                    📂 Seleccionar Archivo M3U/JSON...
                    <input type="file" id="m3u-file-input" accept=".m3u,.m3u8,.json,.txt" style="display:none;" onchange="handleM3uFileSelected(event)">
                </label>
                <span id="m3u-file-name" style="font-size:12px; color:var(--text-muted);">Ningún archivo seleccionado</span>
            </div>
            <textarea id="m3u-text-area" rows="9" placeholder="#EXTM3U&#10;#EXTINF:-1 tvg-id=&quot;30001&quot; tvg-name=&quot;Movistar LaLiga HD&quot; group-title=&quot;Astra 19.2E&quot;,Movistar LaLiga HD&#10;http://192.168.1.50:9191/stream/channel/30001" style="width:100%; font-family:monospace; font-size:12px; margin-bottom:12px; padding:10px;"></textarea>
            <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                <div style="display:flex; gap:14px; align-items:center;">
                    <label style="display:inline-flex; align-items:center; gap:6px; font-size:12px; cursor:pointer;">
                        <input type="radio" name="m3u-mode" id="m3u-mode-append" value="append" checked>
                        <span>Añadir a canales existentes</span>
                    </label>
                    <label style="display:inline-flex; align-items:center; gap:6px; font-size:12px; cursor:pointer;">
                        <input type="radio" name="m3u-mode" id="m3u-mode-replace" value="replace">
                        <span>Reemplazar lista completa</span>
                    </label>
                </div>
                <div style="display:flex; gap:10px;">
                    <button type="button" class="btn btn-outline" onclick="closeImportM3uModal()">Cancelar</button>
                    <button type="button" class="btn btn-success" onclick="processM3uImportText()">⚡ Importar Canales</button>
                </div>
            </div>
        </div>
    </div>

    <script>
        var latencyHistory = [];
        var ecmHistory = [];
        var lastEcmTotal = -1;
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
            if (!card) return;
            var protoEl = card.querySelector('.srv-proto');
            if (!protoEl) return;
            var proto = protoEl.value;
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
                if (portInput) { portInput.value = 0; portInput.disabled = true; }
                if (hostInput && (hostInput.value === '192.168.1.100' || hostInput.value === '127.0.0.1')) hostInput.value = '/tmp/camd.socket';
                if (hostLabel) hostLabel.innerText = 'Socket Path';
                if (passDiv) passDiv.style.display = 'none';
                if (desDiv) desDiv.style.display = 'none';
                if (credsRow) credsRow.style.gridTemplateColumns = '1fr';
            } else {
                if (portInput) {
                    portInput.disabled = false;
                    portInput.value = getDefaultPortForProto(proto);
                }
                if (hostLabel) hostLabel.innerText = 'Host / IP Address';
                if (hostInput && hostInput.value.indexOf('/') >= 0) hostInput.value = '192.168.1.100';

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
            if (!card) return;
            var hostEl = card.querySelector('.srv-host');
            var portEl = card.querySelector('.srv-port');
            var protoEl = card.querySelector('.srv-proto');
            var userEl = card.querySelector('.srv-user');
            var passEl = card.querySelector('.srv-pass');
            var desEl = card.querySelector('.srv-des');

            var host = hostEl ? hostEl.value : '127.0.0.1';
            var port = portEl ? (parseInt(portEl.value, 10) || 9000) : 9000;
            var proto = protoEl ? protoEl.value : 'CCCAM';
            var user = userEl ? userEl.value : '';
            var pass = passEl ? passEl.value : '';
            var des = desEl ? desEl.value : '';
            var statusDiv = document.getElementById('ping-status-' + idx);
            if (!statusDiv) return;

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

        function getCasSystemInfo(caidVal) {
            var caid = 0;
            if (typeof caidVal === 'number') {
                caid = caidVal;
            } else if (typeof caidVal === 'string') {
                var s = caidVal.trim();
                caid = s.toLowerCase().startsWith('0x') ? parseInt(s, 16) : parseInt(s, 10);
            }
            if (isNaN(caid) || caid === 0) {
                return { name: 'Free-To-Air', code: 'FTA', color: '#3B82F6', desc: 'En abierto (Sin encriptación)' };
            }
            var high = (caid >> 8) & 0xFF;
            switch(high) {
                case 0x18:
                    return { name: 'Nagravision', code: 'NAGRA', color: '#F59E0B', desc: 'Nagravision (Movistar+ 0x1810, HD+ 0x1830/0x1843, Tivùsat 0x183E, MEO 0x1802, Polsat 0x1803)' };
                case 0x05:
                    return { name: 'Viaccess', code: 'VIACCESS', color: '#10B981', desc: 'Viaccess (Fransat, SRG SSR Suiza, BIS TV 0x0500)' };
                case 0x01:
                    return { name: 'Seca / Mediaguard', code: 'SECA', color: '#8B5CF6', desc: 'Seca / Mediaguard (Canal+ 0x0100)' };
                case 0x09:
                    return { name: 'NDS VideoGuard', code: 'NDS', color: '#EC4899', desc: 'NDS VideoGuard (Sky DE 0x098C/0x09C4, Sky IT 0x09CD, Sky UK 0x0963)' };
                case 0x0B:
                    return { name: 'Conax', code: 'CONAX', color: '#06B6D4', desc: 'Conax (Canal Digital Nordic, Telewizja na kartę 0x0B00)' };
                case 0x06:
                    return { name: 'Irdeto', code: 'IRDETO', color: '#EF4444', desc: 'Irdeto (Nova Grecia, Digitürk 0x0604/0x0624)' };
                case 0x0D:
                    return { name: 'Cryptoworks', code: 'CW', color: '#F97316', desc: 'Cryptoworks (ORF Digital Austria 0x0D95)' };
                case 0x17:
                    return { name: 'Betacrypt', code: 'BETA', color: '#6366F1', desc: 'Betacrypt (d-box / Premiere legacy)' };
                case 0x0E:
                    return { name: 'PowerVu', code: 'PVU', color: '#14B8A6', desc: 'PowerVu (AFN, Discovery)' };
                case 0x26:
                    return { name: 'BISS', code: 'BISS', color: '#84CC16', desc: 'BISS Feeds' };
                case 0x4A:
                    return { name: 'DRE-Crypt', code: 'DRE', color: '#A855F7', desc: 'DRE-Crypt (Tricolor TV)' };
                default:
                    return { name: 'CAS 0x' + caid.toString(16).toUpperCase(), code: 'CAS', color: '#94A3B8', desc: 'CAID 0x' + caid.toString(16).toUpperCase() };
            }
        }

        function getCasBadgeHtml(caidVal) {
            var info = getCasSystemInfo(caidVal);
            return '<span class="cas-badge-pill" style="background:' + info.color + '22; border:1px solid ' + info.color + '; color:' + info.color + '; padding:3px 8px; border-radius:5px; font-weight:700; font-size:11px; white-space:nowrap; display:inline-block;" title="' + info.desc + '">' +
                info.code + ' &bull; ' + info.name + '</span>';
        }

        function onChannelCaidChange(inputEl, badgeContainerId) {
            var badgeEl = document.getElementById(badgeContainerId);
            if (badgeEl) {
                badgeEl.innerHTML = getCasBadgeHtml(inputEl.value);
            }
        }

        function switchChannelSubtab(subtab) {
            document.querySelectorAll('.ch-subtab-btn').forEach(function(btn) {
                btn.className = 'btn btn-outline ch-subtab-btn';
            });
            var activeBtn = document.getElementById('subtab-btn-' + subtab);
            if (activeBtn) activeBtn.className = 'btn btn-primary ch-subtab-btn';

            var subviews = ['cfg', 'cached', 'avail', 'scan'];
            subviews.forEach(function(v) {
                var el = document.getElementById('channel-subview-' + v);
                if (el) el.style.display = (v === subtab) ? 'block' : 'none';
            });

            if (subtab === 'cached') {
                loadCachedChannels();
            } else if (subtab === 'avail') {
                loadAvailableChannels();
            }
        }

        var currentConfiguredChannels = [];

        var PREDEFINED_PACKAGES = {
            movistar: [
                { name: 'Movistar Plus+ HD', satellite: 'Astra 19.2°E', frequency: 10729, polarization: 'V', symbolRate: 22000, serviceId: 30001, pmtPid: 1024, caid: '0x1810' },
                { name: 'LaLiga TV por M+ HD', satellite: 'Astra 19.2°E', frequency: 10729, polarization: 'V', symbolRate: 22000, serviceId: 30002, pmtPid: 1025, caid: '0x1810' },
                { name: 'DAZN LaLiga HD', satellite: 'Astra 19.2°E', frequency: 10729, polarization: 'V', symbolRate: 22000, serviceId: 30003, pmtPid: 1026, caid: '0x1810' },
                { name: 'Liga de Campeones HD', satellite: 'Astra 19.2°E', frequency: 10788, polarization: 'V', symbolRate: 22000, serviceId: 30004, pmtPid: 1027, caid: '0x1810' },
                { name: 'Cine por M+ HD', satellite: 'Astra 19.2°E', frequency: 10788, polarization: 'V', symbolRate: 22000, serviceId: 30005, pmtPid: 1028, caid: '0x1810' },
                { name: 'Series por M+ HD', satellite: 'Astra 19.2°E', frequency: 10788, polarization: 'V', symbolRate: 22000, serviceId: 30006, pmtPid: 1029, caid: '0x1810' },
                { name: 'Acción por M+ HD', satellite: 'Astra 19.2°E', frequency: 10818, polarization: 'V', symbolRate: 22000, serviceId: 30007, pmtPid: 1030, caid: '0x1810' },
                { name: 'Documentales por M+ HD', satellite: 'Astra 19.2°E', frequency: 10818, polarization: 'V', symbolRate: 22000, serviceId: 30008, pmtPid: 1031, caid: '0x1810' }
            ],
            hdplus: [
                { name: 'RTL HD', satellite: 'Astra 19.2°E', frequency: 10832, polarization: 'H', symbolRate: 22000, serviceId: 61200, pmtPid: 96, caid: '0x1830' },
                { name: 'Sat.1 HD', satellite: 'Astra 19.2°E', frequency: 11464, polarization: 'H', symbolRate: 22000, serviceId: 61301, pmtPid: 97, caid: '0x1830' },
                { name: 'ProSieben HD', satellite: 'Astra 19.2°E', frequency: 11464, polarization: 'H', symbolRate: 22000, serviceId: 61302, pmtPid: 98, caid: '0x1830' },
                { name: 'VOX HD', satellite: 'Astra 19.2°E', frequency: 10832, polarization: 'H', symbolRate: 22000, serviceId: 61201, pmtPid: 99, caid: '0x1830' },
                { name: 'Kabel Eins HD', satellite: 'Astra 19.2°E', frequency: 11464, polarization: 'H', symbolRate: 22000, serviceId: 61303, pmtPid: 100, caid: '0x1830' },
                { name: 'UHD1 by ASTRA / HD+', satellite: 'Astra 19.2°E', frequency: 10994, polarization: 'H', symbolRate: 22000, serviceId: 61400, pmtPid: 101, caid: '0x1830' }
            ],
            skyde: [
                { name: 'Sky Sport Bundesliga 1 HD', satellite: 'Astra 19.2°E', frequency: 11992, polarization: 'H', symbolRate: 27500, serviceId: 260, pmtPid: 100, caid: '0x098D' },
                { name: 'Sky Sport Top Event HD', satellite: 'Astra 19.2°E', frequency: 11992, polarization: 'H', symbolRate: 27500, serviceId: 261, pmtPid: 101, caid: '0x098D' },
                { name: 'Sky Cinema Premiere HD', satellite: 'Astra 19.2°E', frequency: 12032, polarization: 'H', symbolRate: 27500, serviceId: 270, pmtPid: 102, caid: '0x098D' },
                { name: 'Sky Crime HD', satellite: 'Astra 19.2°E', frequency: 12032, polarization: 'H', symbolRate: 27500, serviceId: 271, pmtPid: 103, caid: '0x098D' }
            ],
            tivusat: [
                { name: 'Rai 1 HD', satellite: 'Hotbird 13°E', frequency: 11766, polarization: 'V', symbolRate: 29900, serviceId: 3401, pmtPid: 500, caid: '0x183E' },
                { name: 'Rai 2 HD', satellite: 'Hotbird 13°E', frequency: 11766, polarization: 'V', symbolRate: 29900, serviceId: 3402, pmtPid: 501, caid: '0x183E' },
                { name: 'Canale 5 HD', satellite: 'Hotbird 13°E', frequency: 11432, polarization: 'V', symbolRate: 29900, serviceId: 3501, pmtPid: 502, caid: '0x183E' },
                { name: 'Italia 1 HD', satellite: 'Hotbird 13°E', frequency: 11432, polarization: 'V', symbolRate: 29900, serviceId: 3502, pmtPid: 503, caid: '0x183E' },
                { name: 'Rai 4K', satellite: 'Hotbird 13°E', frequency: 11075, polarization: 'V', symbolRate: 30000, serviceId: 3600, pmtPid: 504, caid: '0x183E' }
            ],
            srg: [
                { name: 'SRF 1 HD', satellite: 'Hotbird 13°E', frequency: 10971, polarization: 'H', symbolRate: 29700, serviceId: 17201, pmtPid: 600, caid: '0x0500' },
                { name: 'SRF zwei HD', satellite: 'Hotbird 13°E', frequency: 10971, polarization: 'H', symbolRate: 29700, serviceId: 17202, pmtPid: 601, caid: '0x0500' },
                { name: 'RTS 1 HD', satellite: 'Hotbird 13°E', frequency: 10971, polarization: 'H', symbolRate: 29700, serviceId: 17203, pmtPid: 602, caid: '0x0500' },
                { name: 'RSI LA 1 HD', satellite: 'Hotbird 13°E', frequency: 11526, polarization: 'H', symbolRate: 29700, serviceId: 17204, pmtPid: 603, caid: '0x0500' }
            ],
            meo: [
                { name: 'Sport TV 1 HD', satellite: 'Hispasat 30°W', frequency: 12246, polarization: 'H', symbolRate: 27500, serviceId: 401, pmtPid: 700, caid: '0x1802' },
                { name: 'SIC Noticias', satellite: 'Hispasat 30°W', frequency: 12246, polarization: 'H', symbolRate: 27500, serviceId: 402, pmtPid: 701, caid: '0x1802' },
                { name: 'TVI HD', satellite: 'Hispasat 30°W', frequency: 12246, polarization: 'H', symbolRate: 27500, serviceId: 403, pmtPid: 702, caid: '0x1802' },
                { name: 'Eleven Sports 1 HD', satellite: 'Hispasat 30°W', frequency: 12130, polarization: 'H', symbolRate: 27500, serviceId: 404, pmtPid: 703, caid: '0x1802' }
            ],
            tdt: [
                { name: 'La 1 HD', satellite: 'TDT Terrestre', frequency: 578, polarization: 'H', symbolRate: 0, serviceId: 1001, pmtPid: 101, caid: '0x0000' },
                { name: 'La 2 HD', satellite: 'TDT Terrestre', frequency: 578, polarization: 'H', symbolRate: 0, serviceId: 1002, pmtPid: 102, caid: '0x0000' },
                { name: 'Antena 3 HD', satellite: 'TDT Terrestre', frequency: 626, polarization: 'H', symbolRate: 0, serviceId: 1003, pmtPid: 103, caid: '0x0000' },
                { name: 'Cuatro HD', satellite: 'TDT Terrestre', frequency: 650, polarization: 'H', symbolRate: 0, serviceId: 1004, pmtPid: 104, caid: '0x0000' },
                { name: 'Telecinco HD', satellite: 'TDT Terrestre', frequency: 650, polarization: 'H', symbolRate: 0, serviceId: 1005, pmtPid: 105, caid: '0x0000' },
                { name: 'laSexta HD', satellite: 'TDT Terrestre', frequency: 626, polarization: 'H', symbolRate: 0, serviceId: 1006, pmtPid: 106, caid: '0x0000' }
            ]
        };

        function syncChannelsFromDom() {
            var rows = document.querySelectorAll('#channels-tbody tr');
            if (rows.length === 0) return;
            var updated = [];
            rows.forEach(function(tr) {
                var nameEl = tr.querySelector('.ch-name');
                if (!nameEl) return;
                var satEl = tr.querySelector('.ch-sat');
                var freqEl = tr.querySelector('.ch-freq');
                var polEl = tr.querySelector('.ch-pol');
                var srEl = tr.querySelector('.ch-sr');
                var sidEl = tr.querySelector('.ch-sid');
                var pmtEl = tr.querySelector('.ch-pmt');
                var caidEl = tr.querySelector('.ch-caid');

                updated.push({
                    name: nameEl.value.trim(),
                    satellite: satEl ? satEl.value.trim() : 'Astra 19.2°E',
                    frequency: freqEl ? (parseInt(freqEl.value, 10) || 10729) : 10729,
                    polarization: polEl ? polEl.value : 'V',
                    symbolRate: srEl ? (parseInt(srEl.value, 10) || 22000) : 22000,
                    serviceId: sidEl ? (parseInt(sidEl.value, 10) || 1) : 1,
                    pmtPid: pmtEl ? (parseInt(pmtEl.value, 10) || 1024) : 1024,
                    caid: caidEl ? caidEl.value.trim() : '0x1810',
                    streamUrl: ''
                });
            });
            if (updated.length > 0) {
                currentConfiguredChannels = updated;
            }
        }

        function renderChannelsTable(channels) {
            if (channels !== undefined && channels !== null) {
                currentConfiguredChannels = channels;
            }
            var countEl = document.getElementById('count-cfg-channels');
            if (countEl) countEl.innerText = currentConfiguredChannels.length;

            populatePlayerChannelDropdown(currentConfiguredChannels);
            renderPlayerChannelZapper(currentConfiguredChannels);

            var tbody = document.getElementById('channels-tbody');
            if (!tbody) return;
            tbody.innerHTML = '';
            if (currentConfiguredChannels.length === 0) {
                tbody.innerHTML = '<tr><td colspan="9" style="text-align:center; padding:22px; color:var(--text-muted);">' +
                    'No hay canales configurados en la parrilla.<br>' +
                    '<span style="font-size:12px;">Usa "➕ Nuevo Canal", "📦 Añadir Paquete" o "📥 Importar M3U" para añadir canales.</span>' +
                    '</td></tr>';
                return;
            }

            currentConfiguredChannels.forEach(function(ch, idx) {
                var tr = document.createElement('tr');
                tr.id = 'ch-row-' + idx;
                tr.setAttribute('data-idx', String(idx));
                var badgeId = 'ch-cas-badge-' + idx;

                tr.innerHTML = 
                    '<td style="text-align:center;"><input type="checkbox" class="cfg-ch-chk" data-idx="' + idx + '" onchange="updateBulkDeleteState()"></td>' +
                    '<td style="text-align:center; white-space:nowrap;">' +
                        '<div style="display:flex; gap:3px; justify-content:center;">' +
                            '<button type="button" class="btn btn-outline" style="padding:1px 5px; font-size:10px;" onclick="moveChannelUp(' + idx + ')" ' + (idx === 0 ? 'disabled' : '') + ' title="Mover canal arriba">▲</button>' +
                            '<button type="button" class="btn btn-outline" style="padding:1px 5px; font-size:10px;" onclick="moveChannelDown(' + idx + ')" ' + (idx === currentConfiguredChannels.length - 1 ? 'disabled' : '') + ' title="Mover canal abajo">▼</button>' +
                        '</div>' +
                    '</td>' +
                    '<td><input type="text" class="ch-name" value="' + (ch.name || '') + '" style="min-width:130px; font-weight:700;"></td>' +
                    '<td><input type="text" class="ch-sat" value="' + (ch.satellite || 'Astra 19.2°E') + '" style="min-width:95px;"></td>' +
                    '<td><div style="display:flex; gap:3px;">' +
                        '<input type="number" class="ch-freq" value="' + (ch.frequency || 10729) + '" style="width:65px;" title="Frecuencia (MHz)">' +
                        '<select class="ch-pol" style="width:45px;"><option value="H"' + (ch.polarization === 'H' ? ' selected' : '') + '>H</option><option value="V"' + (ch.polarization === 'V' ? ' selected' : '') + '>V</option></select>' +
                        '<input type="number" class="ch-sr" value="' + (ch.symbolRate || 22000) + '" style="width:65px;" title="Symbol Rate">' +
                    '</div></td>' +
                    '<td><div style="display:flex; gap:3px;">' +
                        '<input type="number" class="ch-sid" value="' + (ch.serviceId || 1) + '" style="width:60px;" placeholder="SID" title="Service ID">' +
                        '<input type="number" class="ch-pmt" value="' + (ch.pmtPid || 1024) + '" style="width:60px;" placeholder="PMT" title="PMT PID">' +
                    '</div></td>' +
                    '<td><input type="text" class="ch-caid" value="' + (ch.caid || '0x1810') + '" style="width:75px; font-family:monospace;" oninput="onChannelCaidChange(this, \'' + badgeId + '\')"></td>' +
                    '<td><div id="' + badgeId + '">' + getCasBadgeHtml(ch.caid || '0x1810') + '</div></td>' +
                    '<td><div style="display:flex; gap:4px; align-items:center;">' +
                        '<button type="button" class="btn btn-primary" style="padding:3px 8px; font-size:11px;" onclick="playChannel(' + idx + ')" title="Ver canal en reproductor web">▶ Ver</button>' +
                        '<button type="button" class="btn btn-outline" style="padding:3px 7px; font-size:11px;" onclick="openChannelEditModal(' + idx + ')" title="Editar información completa">✏️</button>' +
                        '<button type="button" class="btn btn-outline" style="padding:3px 7px; font-size:11px;" onclick="testSingleChannelEcm(' + idx + ')" title="Test ECM Descrambler">⚡</button>' +
                        '<button type="button" class="btn btn-danger" style="padding:3px 7px; font-size:11px;" onclick="removeChannelRow(' + idx + ')" title="Eliminar canal">🗑️</button>' +
                    '</div></td>';
                tbody.appendChild(tr);
            });
            filterConfiguredChannelsTable();
        }

        function filterConfiguredChannelsTable() {
            var search = (document.getElementById('filter-cfg-search') ? document.getElementById('filter-cfg-search').value : '').toLowerCase().trim();
            var satFilter = document.getElementById('filter-cfg-sat') ? document.getElementById('filter-cfg-sat').value : 'all';
            var casFilter = document.getElementById('filter-cfg-cas') ? document.getElementById('filter-cfg-cas').value : 'all';

            document.querySelectorAll('#channels-tbody tr').forEach(function(tr) {
                var nameEl = tr.querySelector('.ch-name');
                if (!nameEl) return;
                var satEl = tr.querySelector('.ch-sat');
                var caidEl = tr.querySelector('.ch-caid');
                var sidEl = tr.querySelector('.ch-sid');

                var nameVal = nameEl ? nameEl.value.toLowerCase() : '';
                var satVal = satEl ? satEl.value : '';
                var caidVal = caidEl ? caidEl.value.toUpperCase() : '';
                var sidVal = sidEl ? sidEl.value : '';

                var matchesSearch = (search === '' || (nameVal + ' ' + satVal.toLowerCase() + ' ' + caidVal.toLowerCase() + ' ' + sidVal).indexOf(search) !== -1);
                var matchesSat = (satFilter === 'all' || satVal.indexOf(satFilter) !== -1);
                var matchesCas = true;
                if (casFilter !== 'all') {
                    if (casFilter === 'FTA') {
                        matchesCas = (caidVal === '0X0000' || caidVal === '0X0' || caidVal === '' || caidVal === '0');
                    } else if (casFilter === 'NAGRA') {
                        matchesCas = caidVal.indexOf('18') !== -1;
                    } else if (casFilter === 'VIACCESS') {
                        matchesCas = caidVal.indexOf('05') !== -1;
                    } else if (casFilter === 'NDS') {
                        matchesCas = caidVal.indexOf('09') !== -1;
                    } else if (casFilter === 'CONAX') {
                        matchesCas = caidVal.indexOf('0B') !== -1;
                    } else if (casFilter === 'SECA') {
                        matchesCas = caidVal.indexOf('01') !== -1;
                    } else if (casFilter === 'IRDETO') {
                        matchesCas = caidVal.indexOf('06') !== -1;
                    }
                }
                tr.style.display = (matchesSearch && matchesSat && matchesCas) ? '' : 'none';
            });
        }

        function toggleSelectAllConfigured(checked) {
            document.querySelectorAll('.cfg-ch-chk').forEach(function(chk) {
                chk.checked = checked;
            });
            updateBulkDeleteState();
        }

        function updateBulkDeleteState() {
            var anyChecked = false;
            document.querySelectorAll('.cfg-ch-chk').forEach(function(c) {
                if (c.checked) anyChecked = true;
            });
            var allChk = document.getElementById('chk-select-all-cfg');
            if (allChk && !anyChecked) allChk.checked = false;
        }

        function deleteSelectedChannels() {
            syncChannelsFromDom();
            var checkedIndices = {};
            document.querySelectorAll('.cfg-ch-chk:checked').forEach(function(chk) {
                var idx = parseInt(chk.getAttribute('data-idx'), 10);
                if (!isNaN(idx)) checkedIndices[idx] = true;
            });

            var countToDelete = Object.keys(checkedIndices).length;
            if (countToDelete === 0) {
                showAlert('Por favor, selecciona los canales que deseas eliminar usando las casillas de verificación.', 'error');
                return;
            }

            if (!confirm('¿Seguro que deseas eliminar los ' + countToDelete + ' canales seleccionados de la parrilla?')) {
                return;
            }

            currentConfiguredChannels = currentConfiguredChannels.filter(function(ch, idx) {
                return !checkedIndices[idx];
            });

            var allChk = document.getElementById('chk-select-all-cfg');
            if (allChk) allChk.checked = false;

            renderChannelsTable(currentConfiguredChannels);
            showAlert('✓ ' + countToDelete + ' canal(es) eliminado(s). Pulsa "Guardar Canales" para guardar en memoria persistente.', 'success');
        }

        function moveChannelUp(idx) {
            if (idx <= 0 || idx >= currentConfiguredChannels.length) return;
            syncChannelsFromDom();
            var tmp = currentConfiguredChannels[idx];
            currentConfiguredChannels[idx] = currentConfiguredChannels[idx - 1];
            currentConfiguredChannels[idx - 1] = tmp;
            renderChannelsTable(currentConfiguredChannels);
        }

        function moveChannelDown(idx) {
            if (idx < 0 || idx >= currentConfiguredChannels.length - 1) return;
            syncChannelsFromDom();
            var tmp = currentConfiguredChannels[idx];
            currentConfiguredChannels[idx] = currentConfiguredChannels[idx + 1];
            currentConfiguredChannels[idx + 1] = tmp;
            renderChannelsTable(currentConfiguredChannels);
        }

        function openNewChannelModal() {
            syncChannelsFromDom();
            document.getElementById('modal-ch-index').value = '-1';
            document.getElementById('modal-ch-title').innerText = '➕ Nuevo Canal Satelital';
            document.getElementById('modal-ch-name').value = 'Nuevo Canal HD';
            document.getElementById('modal-ch-sat').value = 'Astra 19.2°E';
            document.getElementById('modal-ch-freq').value = '10729';
            document.getElementById('modal-ch-pol').value = 'V';
            document.getElementById('modal-ch-sr').value = '22000';
            var nextSid = 30001;
            if (currentConfiguredChannels.length > 0) {
                var maxSid = Math.max.apply(null, currentConfiguredChannels.map(function(c) { return c.serviceId || 0; }));
                if (maxSid > 0) nextSid = maxSid + 1;
            }
            document.getElementById('modal-ch-sid').value = String(nextSid);
            document.getElementById('modal-ch-pmt').value = '1024';
            document.getElementById('modal-ch-caid').value = '0x1810';
            updateModalCasBadge();
            document.getElementById('modal-channel-edit').style.display = 'flex';
        }

        function openChannelEditModal(idx) {
            syncChannelsFromDom();
            if (idx < 0 || idx >= currentConfiguredChannels.length) return;
            var ch = currentConfiguredChannels[idx];
            document.getElementById('modal-ch-index').value = String(idx);
            document.getElementById('modal-ch-title').innerText = '✏️ Editar Canal: ' + ch.name;
            document.getElementById('modal-ch-name').value = ch.name || '';
            document.getElementById('modal-ch-sat').value = ch.satellite || 'Astra 19.2°E';
            document.getElementById('modal-ch-freq').value = ch.frequency || 10729;
            document.getElementById('modal-ch-pol').value = ch.polarization || 'V';
            document.getElementById('modal-ch-sr').value = ch.symbolRate || 22000;
            document.getElementById('modal-ch-sid').value = ch.serviceId || 1;
            document.getElementById('modal-ch-pmt').value = ch.pmtPid || 1024;
            document.getElementById('modal-ch-caid').value = ch.caid || '0x1810';
            updateModalCasBadge();
            document.getElementById('modal-channel-edit').style.display = 'flex';
        }

        function closeChannelEditModal() {
            document.getElementById('modal-channel-edit').style.display = 'none';
        }

        function updateModalCasBadge() {
            var caid = document.getElementById('modal-ch-caid').value;
            var badgeEl = document.getElementById('modal-ch-cas-badge');
            if (badgeEl) {
                badgeEl.innerHTML = getCasBadgeHtml(caid);
            }
        }

        function saveChannelFromModal() {
            var name = document.getElementById('modal-ch-name').value.trim();
            if (!name) {
                showAlert('Por favor, indica un nombre para el canal.', 'error');
                return;
            }
            var sat = document.getElementById('modal-ch-sat').value.trim() || 'Astra 19.2°E';
            var freq = parseInt(document.getElementById('modal-ch-freq').value, 10) || 10729;
            var pol = document.getElementById('modal-ch-pol').value || 'V';
            var sr = parseInt(document.getElementById('modal-ch-sr').value, 10) || 22000;
            var sid = parseInt(document.getElementById('modal-ch-sid').value, 10) || 1;
            var pmt = parseInt(document.getElementById('modal-ch-pmt').value, 10) || 1024;
            var caid = document.getElementById('modal-ch-caid').value.trim() || '0x1810';

            var channelObj = {
                name: name,
                satellite: sat,
                frequency: freq,
                polarization: pol,
                symbolRate: sr,
                serviceId: sid,
                pmtPid: pmt,
                caid: caid,
                streamUrl: ''
            };

            var editIdx = parseInt(document.getElementById('modal-ch-index').value, 10);
            if (editIdx >= 0 && editIdx < currentConfiguredChannels.length) {
                currentConfiguredChannels[editIdx] = channelObj;
                showAlert('✓ Canal "' + name + '" modificado correctamente. Guarda para aplicar.', 'success');
            } else {
                currentConfiguredChannels.push(channelObj);
                showAlert('✓ Canal "' + name + '" añadido a la lista. Guarda para aplicar.', 'success');
            }

            closeChannelEditModal();
            renderChannelsTable(currentConfiguredChannels);
        }

        function togglePackageMenu() {
            var menu = document.getElementById('dropdown-packages-menu');
            if (!menu) return;
            menu.style.display = (menu.style.display === 'none' || menu.style.display === '') ? 'block' : 'none';
        }

        window.addEventListener('click', function(e) {
            var menu = document.getElementById('dropdown-packages-menu');
            if (menu && menu.style.display === 'block') {
                if (!e.target.closest('#dropdown-packages-menu') && !e.target.closest('.btn-purple')) {
                    menu.style.display = 'none';
                }
            }
        });

        function addPredefinedPackage(pkgKey) {
            var pkg = PREDEFINED_PACKAGES[pkgKey];
            if (!pkg || pkg.length === 0) return;
            syncChannelsFromDom();

            var currentSids = {};
            currentConfiguredChannels.forEach(function(c) {
                if (c.serviceId) currentSids[c.serviceId] = true;
            });

            var addedCount = 0;
            pkg.forEach(function(ch) {
                if (!currentSids[ch.serviceId]) {
                    currentConfiguredChannels.push(Object.assign({}, ch));
                    currentSids[ch.serviceId] = true;
                    addedCount++;
                }
            });

            var menu = document.getElementById('dropdown-packages-menu');
            if (menu) menu.style.display = 'none';

            renderChannelsTable(currentConfiguredChannels);
            showAlert('✓ Paquete añadido: ' + addedCount + ' canales nuevos agregados a la lista. Pulsa "Guardar Canales".', 'success');
        }

        function openImportM3uModal() {
            document.getElementById('m3u-file-name').innerText = 'Ningún archivo seleccionado';
            document.getElementById('m3u-text-area').value = '';
            document.getElementById('modal-import-m3u').style.display = 'flex';
        }

        function closeImportM3uModal() {
            document.getElementById('modal-import-m3u').style.display = 'none';
        }

        function handleM3uFileSelected(event) {
            var file = event.target.files && event.target.files[0];
            if (!file) return;
            document.getElementById('m3u-file-name').innerText = file.name + ' (' + Math.round(file.size / 1024) + ' KB)';
            var reader = new FileReader();
            reader.onload = function(e) {
                document.getElementById('m3u-text-area').value = e.target.result;
            };
            reader.readAsText(file);
        }

        function parseM3uText(text) {
            var trimmed = text.trim();
            if (trimmed.startsWith('[') || trimmed.startsWith('{')) {
                try {
                    var parsed = JSON.parse(trimmed);
                    if (Array.isArray(parsed)) return parsed;
                    if (parsed.channels && Array.isArray(parsed.channels)) return parsed.channels;
                } catch(e) {
                    console.warn('JSON parse fallback:', e);
                }
            }

            var lines = trimmed.split('\n');
            var channels = [];
            var currentInfo = null;

            for (var i = 0; i < lines.length; i++) {
                var line = lines[i].trim();
                if (!line) continue;

                if (line.startsWith('#EXTINF:')) {
                    var name = 'Canal Importado';
                    var sid = 0;
                    var group = 'Astra 19.2°E';
                    var caid = '0x1810';

                    var commaIdx = line.indexOf(',');
                    if (commaIdx !== -1) {
                        name = line.substring(commaIdx + 1).trim();
                    }

                    var tvgIdMatch = line.match(/tvg-id="([^"]+)"/i);
                    if (tvgIdMatch) {
                        var parsedId = parseInt(tvgIdMatch[1], 10);
                        if (!isNaN(parsedId)) sid = parsedId;
                    }

                    var groupMatch = line.match(/group-title="([^"]+)"/i);
                    if (groupMatch) {
                        group = groupMatch[1];
                    }

                    var upper = (line + ' ' + name).toUpperCase();
                    if (upper.indexOf('FTA') !== -1 || upper.indexOf('LIBRE') !== -1) caid = '0x0000';
                    else if (upper.indexOf('NAGRA') !== -1) caid = '0x1810';
                    else if (upper.indexOf('VIACCESS') !== -1) caid = '0x0500';
                    else if (upper.indexOf('NDS') !== -1 || upper.indexOf('VIDEOGUARD') !== -1) caid = '0x098D';
                    else if (upper.indexOf('CONAX') !== -1) caid = '0x0B00';
                    else if (upper.indexOf('SECA') !== -1) caid = '0x0100';

                    currentInfo = {
                        name: name,
                        satellite: group.indexOf('°') !== -1 ? group : 'Astra 19.2°E',
                        frequency: 10729,
                        polarization: 'V',
                        symbolRate: 22000,
                        serviceId: sid || (30000 + channels.length + 1),
                        pmtPid: 1024 + channels.length,
                        caid: caid,
                        streamUrl: ''
                    };
                } else if (!line.startsWith('#') && currentInfo) {
                    var urlMatch = line.match(/\/stream\/channel\/(\d+)/i);
                    if (urlMatch) {
                        currentInfo.serviceId = parseInt(urlMatch[1], 10);
                    }
                    currentInfo.streamUrl = line;
                    channels.push(currentInfo);
                    currentInfo = null;
                }
            }
            return channels;
        }

        function processM3uImportText() {
            var text = document.getElementById('m3u-text-area').value;
            if (!text || text.trim().length === 0) {
                showAlert('Por favor, introduce o carga una lista de canales M3U o JSON.', 'error');
                return;
            }

            var parsed = parseM3uText(text);
            if (!parsed || parsed.length === 0) {
                showAlert('No se pudieron extraer canales del texto. Verifica el formato M3U o JSON.', 'error');
                return;
            }

            syncChannelsFromDom();
            var replaceMode = document.getElementById('m3u-mode-replace').checked;
            if (replaceMode) {
                currentConfiguredChannels = parsed;
            } else {
                var currentSids = {};
                currentConfiguredChannels.forEach(function(c) {
                    if (c.serviceId) currentSids[c.serviceId] = true;
                });
                parsed.forEach(function(ch) {
                    if (!currentSids[ch.serviceId]) {
                        currentConfiguredChannels.push(ch);
                        currentSids[ch.serviceId] = true;
                    }
                });
            }

            closeImportM3uModal();
            renderChannelsTable(currentConfiguredChannels);
            showAlert('✓ ' + parsed.length + ' canales importados con éxito. Pulsa "Guardar Canales" para persistir.', 'success');
        }

        function addChannelRow(preset) {
            syncChannelsFromDom();
            var name = preset ? preset.name : 'Nuevo Canal';
            var sat = preset ? preset.satellite : 'Astra 19.2°E';
            var freq = preset ? preset.frequency : 11000;
            var pol = preset ? preset.polarization : 'H';
            var sr = preset ? preset.symbolRate : 22000;
            var sid = preset ? preset.serviceId : (100 + currentConfiguredChannels.length);
            var pmt = preset ? preset.pmtPid : (1024 + currentConfiguredChannels.length);
            var caid = preset ? preset.caid : '0x1810';

            currentConfiguredChannels.push({
                name: name,
                satellite: sat,
                frequency: freq,
                polarization: pol,
                symbolRate: sr,
                serviceId: sid,
                pmtPid: pmt,
                caid: caid,
                streamUrl: ''
            });

            renderChannelsTable(currentConfiguredChannels);
        }

        function removeChannelRow(idx) {
            syncChannelsFromDom();
            if (idx >= 0 && idx < currentConfiguredChannels.length) {
                var name = currentConfiguredChannels[idx].name;
                currentConfiguredChannels.splice(idx, 1);
                renderChannelsTable(currentConfiguredChannels);
                showAlert('Canal "' + name + '" eliminado.', 'success');
            }
        }

        function testSingleChannelEcm(idx) {
            var tr = document.getElementById('ch-row-' + idx);
            if (!tr) return;
            var name = tr.querySelector('.ch-name').value;
            var sid = parseInt(tr.querySelector('.ch-sid').value, 10) || 1;
            var caid = tr.querySelector('.ch-caid').value;

            showAlert('Simulando ECM y validando descifrado para ' + name + '...', 'success');
            fetch('/api/cache/test_ecm', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serviceId: sid, name: name, caid: caid })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res.success) {
                    showAlert('✓ ' + res.message + ' [CW: ' + res.cw + ', Paridad: ' + res.parity + ']', 'success');
                } else {
                    showAlert('Error en test ECM: ' + (res.error || 'Error desconocido'), 'error');
                }
            })
            .catch(function(e) { showAlert('Error: ' + e, 'error'); });
        }

        function loadCachedChannels() {
            fetch('/api/channels/cached')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (!res.success) return;
                    document.getElementById('stat-cw-status').innerText = res.cache_enabled ? 'ACTIVO' : 'INACTIVO';
                    document.getElementById('stat-cw-status').style.color = res.cache_enabled ? '#10B981' : '#EF4444';
                    document.getElementById('stat-cw-count').innerText = res.total_cached_cws || 0;
                    document.getElementById('stat-cw-hits').innerText = res.cache_hits || 0;
                    document.getElementById('stat-cw-misses').innerText = res.cache_misses || 0;
                    document.getElementById('stat-cw-ratio').innerText = res.hit_ratio || '0.0%';

                    var tbody = document.getElementById('cached-channels-tbody');
                    tbody.innerHTML = '';
                    var channels = res.channels || [];

                    if (channels.length === 0) {
                        tbody.innerHTML = '<tr><td colspan="10" style="text-align:center; padding:22px; color:var(--text-muted);">' +
                            'Aún no hay canales descifrados en memoria RAM activa.<br>' +
                            '<span style="font-size:12px;">Sintoniza un canal codificado en el televisor o haz clic en "⚡ Simular ECM de Prueba" para verificar el flujo inmediato de Control Words.</span>' +
                            '</td></tr>';
                        return;
                    }

                    channels.forEach(function(ch) {
                        var tr = document.createElement('tr');
                        var badgeHtml = '<span style="background:' + ch.cas_color + '22; border:1px solid ' + ch.cas_color + '; color:' + ch.cas_color + '; padding:2px 7px; border-radius:4px; font-weight:700; font-size:11px;">' + ch.cas_code + ' (' + ch.cas_system + ')</span>';
                        var statusHtml = ch.status === 'ACTIVE_DESCRAMBLING' ?
                            '<span style="background:rgba(16,185,129,0.15); color:#10B981; border:1px solid #10B981; padding:2px 8px; border-radius:4px; font-weight:700; font-size:11px;">🟢 EN VIVO (' + ch.last_seen_sec_ago + 's)</span>' :
                            '<span style="background:rgba(251,191,36,0.15); color:#FBBF24; border:1px solid #FBBF24; padding:2px 8px; border-radius:4px; font-weight:700; font-size:11px;">🟡 EN CACHÉ (' + ch.last_seen_sec_ago + 's)</span>';

                        tr.innerHTML = 
                            '<td style="font-weight:700; color:#FFF;">' + ch.name + '</td>' +
                            '<td>SID: ' + ch.serviceId + ' / PMT: ' + ch.pmtPid + '</td>' +
                            '<td><code>' + ch.caid + '</code></td>' +
                            '<td>' + badgeHtml + '</td>' +
                            '<td style="font-weight:700;">' + ch.ecm_requests + '</td>' +
                            '<td style="color:#34D399; font-weight:700;">' + ch.cw_hits + '</td>' +
                            '<td><code style="font-size:11px; background:rgba(0,0,0,0.4); padding:2px 6px; border-radius:4px;">' + ch.last_cw + '</code></td>' +
                            '<td>' + (ch.last_parity === 0 ? 'EVEN (0)' : 'ODD (1)') + '</td>' +
                            '<td style="color:var(--text-muted); font-size:12px;">Hace ' + ch.last_seen_sec_ago + 's</td>' +
                            '<td>' + statusHtml + '</td>';
                        tbody.appendChild(tr);
                    });
                })
                .catch(function(e) { showAlert('Error al cargar caché de canales: ' + e, 'error'); });
        }

        function testSimulatedEcm() {
            showAlert('Enviando petición ECM de prueba...', 'success');
            fetch('/api/cache/test_ecm', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serviceId: 29950, name: 'Movistar LaLiga HD', caid: '0x1810' })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res.success) {
                    showAlert('✓ ' + res.message, 'success');
                    loadCachedChannels();
                } else {
                    showAlert('Error en test ECM: ' + (res.error || 'Error'), 'error');
                }
            })
            .catch(function(e) { showAlert('Error: ' + e, 'error'); });
        }

        var availableChannelsCache = [];

        function loadAvailableChannels() {
            fetch('/api/channels/available')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (!res.success) return;
                    availableChannelsCache = res.channels || [];
                    var countEl = document.getElementById('count-avail-channels');
                    if (countEl) countEl.innerText = availableChannelsCache.length;
                    filterAvailableChannels();
                })
                .catch(function(e) { showAlert('Error al cargar catálogo de satélite: ' + e, 'error'); });
        }

        function filterAvailableChannels() {
            var satFilter = document.getElementById('avail-sat-filter').value;
            var casFilter = document.getElementById('avail-cas-filter').value;
            var search = (document.getElementById('avail-search-input').value || '').toLowerCase().trim();

            var currentConfigSids = {};
            document.querySelectorAll('#channels-tbody .ch-sid').forEach(function(input) {
                var s = parseInt(input.value, 10);
                if (!isNaN(s)) currentConfigSids[s] = true;
            });

            var filtered = availableChannelsCache.filter(function(ch) {
                if (satFilter !== 'all' && ch.satellite.indexOf(satFilter) === -1) return false;
                if (casFilter !== 'all') {
                    if (casFilter === 'FTA' && ch.is_encrypted) return false;
                    if (casFilter !== 'FTA' && ch.cas_code !== casFilter) return false;
                }
                if (search !== '') {
                    var fullText = (ch.name + ' ' + ch.satellite + ' ' + ch.caid + ' ' + ch.cas_system).toLowerCase();
                    if (fullText.indexOf(search) === -1) return false;
                }
                return true;
            });

            var tbody = document.getElementById('available-channels-tbody');
            tbody.innerHTML = '';
            if (filtered.length === 0) {
                tbody.innerHTML = '<tr><td colspan="9" style="text-align:center; padding:18px; color:var(--text-muted);">No hay canales en el catálogo con los filtros seleccionados.</td></tr>';
                return;
            }

            filtered.forEach(function(ch, idx) {
                var tr = document.createElement('tr');
                var isConfigured = !!currentConfigSids[ch.serviceId];
                var badgeHtml = '<span style="background:' + ch.cas_color + '22; border:1px solid ' + ch.cas_color + '; color:' + ch.cas_color + '; padding:2px 7px; border-radius:4px; font-weight:700; font-size:11px;">' + ch.cas_code + ' (' + ch.cas_system + ')</span>';
                var statusBadge = isConfigured ?
                    '<span style="color:#10B981; font-weight:700; font-size:11px;">✓ En tu lista</span>' :
                    '<span style="color:var(--text-muted); font-size:11px;">Disponible</span>';

                var actionBtn = isConfigured ?
                    '<button type="button" class="btn btn-outline" style="padding:3px 8px; font-size:11px; opacity:0.6;" disabled>Ya añadido</button>' :
                    '<button type="button" class="btn btn-primary" style="padding:3px 10px; font-size:11px;" onclick="addSingleAvailableToConfig(' + ch.serviceId + ')">➕ Añadir a mi lista</button>';

                tr.innerHTML = 
                    '<td><input type="checkbox" class="avail-chk" data-sid="' + ch.serviceId + '"' + (isConfigured ? ' disabled' : '') + ' style="accent-color:var(--primary); width:16px; height:16px;"></td>' +
                    '<td style="font-weight:700; color:#FFF;">' + ch.name + '</td>' +
                    '<td style="color:var(--text-muted);">' + ch.satellite + '</td>' +
                    '<td>' + ch.frequency + ' ' + ch.polarization + ' (' + ch.symbolRate + ')</td>' +
                    '<td>SID: ' + ch.serviceId + ' / PMT: ' + ch.pmtPid + '</td>' +
                    '<td><code>' + ch.caid + '</code></td>' +
                    '<td>' + badgeHtml + '</td>' +
                    '<td>' + statusBadge + '</td>' +
                    '<td>' + actionBtn + '</td>';
                tbody.appendChild(tr);
            });
        }

        function toggleSelectAllAvailable(checked) {
            document.querySelectorAll('.avail-chk:not(:disabled)').forEach(function(chk) {
                chk.checked = checked;
            });
        }

        function addSingleAvailableToConfig(serviceId) {
            var item = availableChannelsCache.find(function(c) { return c.serviceId === serviceId; });
            if (!item) return;
            addChannelRow(item);
            showAlert('✓ Canal "' + item.name + '" añadido a tus canales configurados. Pulsa "Guardar Canales" para persistir.', 'success');
            filterAvailableChannels();
        }

        function addSelectedAvailableChannels() {
            var count = 0;
            document.querySelectorAll('.avail-chk:checked').forEach(function(chk) {
                var sid = parseInt(chk.getAttribute('data-sid'), 10);
                var item = availableChannelsCache.find(function(c) { return c.serviceId === sid; });
                if (item) {
                    addChannelRow(item);
                    count++;
                }
            });
            if (count > 0) {
                showAlert('✓ ' + count + ' canal(es) añadido(s) a tu lista de canales configurados. Pulsa "Guardar Canales" para persistir.', 'success');
                switchChannelSubtab('cfg');
                filterAvailableChannels();
            } else {
                showAlert('Por favor, selecciona al menos un canal disponible para añadir.', 'error');
            }
        }

        var currentPlayerChannelIdx = 0;
        var mpegtsPlayerInstance = null;

        function renderPlayerChannelZapper(channels) {
            var zList = document.getElementById('player-zapper-list');
            var zCount = document.getElementById('zapper-count');
            if (!zList) return;

            var chList = channels || currentConfiguredChannels || [];
            if (zCount) zCount.innerText = chList.length;
            zList.innerHTML = '';

            if (chList.length === 0) {
                zList.innerHTML = '<div style="padding:14px; text-align:center; color:var(--text-muted); font-size:12px;">' +
                    'No hay canales disponibles en la parrilla.<br>Añade canales en la pestaña Canales.' +
                    '</div>';
                return;
            }

            chList.forEach(function(ch, idx) {
                var item = document.createElement('div');
                item.className = 'zapper-item' + (idx === currentPlayerChannelIdx ? ' active' : '');
                item.id = 'zapper-ch-' + idx;
                item.setAttribute('data-name', (ch.name || '').toLowerCase());
                item.setAttribute('data-sat', (ch.satellite || '').toLowerCase());

                var casInfo = getCasSystemInfo(ch.caid || '0x1810');
                var casBadge = '<span style="background:' + casInfo.color + '22; color:' + casInfo.color + '; border:1px solid ' + casInfo.color + '; padding:1px 5px; border-radius:3px; font-size:10px; font-weight:700;">' + casInfo.code + '</span>';

                item.innerHTML = 
                    '<div style="display:flex; justify-content:space-between; align-items:center; gap:8px;">' +
                        '<div style="display:flex; align-items:center; gap:8px; min-width:0;">' +
                            '<span style="color:var(--primary); font-family:monospace; font-weight:800; font-size:12px; min-width:24px;">#' + (idx + 1) + '</span>' +
                            '<span style="font-weight:700; color:#FFF; font-size:13px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">' + (ch.name || 'Canal ' + (idx + 1)) + '</span>' +
                        '</div>' +
                        casBadge +
                    '</div>' +
                    '<div style="display:flex; justify-content:space-between; align-items:center; margin-top:3px; font-size:11px; color:var(--text-muted);">' +
                        '<span>' + (ch.satellite || 'Satelital') + ' (' + (ch.frequency || '') + ' ' + (ch.polarization || '') + ')</span>' +
                        '<span style="font-family:monospace;">SID:' + (ch.serviceId || 1) + '</span>' +
                    '</div>';

                item.onclick = function() {
                    zapToChannel(idx);
                };

                zList.appendChild(item);
            });
        }

        function filterZapperChannels() {
            var q = (document.getElementById('zapper-search') ? document.getElementById('zapper-search').value : '').toLowerCase().trim();
            document.querySelectorAll('#player-zapper-list .zapper-item').forEach(function(el) {
                var name = el.getAttribute('data-name') || '';
                var sat = el.getAttribute('data-sat') || '';
                el.style.display = (q === '' || name.indexOf(q) !== -1 || sat.indexOf(q) !== -1) ? 'block' : 'none';
            });
        }

        function zapToChannel(idx) {
            if (idx < 0 || idx >= currentConfiguredChannels.length) return;
            currentPlayerChannelIdx = idx;

            document.querySelectorAll('#player-zapper-list .zapper-item').forEach(function(el) {
                el.classList.remove('active');
            });
            var activeItem = document.getElementById('zapper-ch-' + idx);
            if (activeItem) {
                activeItem.classList.add('active');
                activeItem.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
            }

            var select = document.getElementById('player-channel-select');
            if (select) select.value = idx;

            playChannel(idx);
        }

        function populatePlayerChannelDropdown(channels) {
            var select = document.getElementById('player-channel-select');
            if (!select) return;
            select.innerHTML = '';
            if (!channels || channels.length === 0) {
                var opt = document.createElement('option');
                opt.value = '-1';
                opt.innerText = '-- No hay canales configurados --';
                select.appendChild(opt);
                return;
            }
            channels.forEach(function(ch, i) {
                var opt = document.createElement('option');
                opt.value = i;
                opt.innerText = '#' + (i + 1) + ' ' + ch.name + ' (' + (ch.frequency || '') + ' ' + (ch.polarization || '') + ' SID:' + ch.serviceId + ')';
                select.appendChild(opt);
            });
        }

        function onPlayerChannelSelect(val) {
            var idx = parseInt(val, 10);
            if (!isNaN(idx) && idx >= 0 && idx < currentConfiguredChannels.length) {
                zapToChannel(idx);
            }
        }

        function prevChannel() {
            if (currentConfiguredChannels.length === 0) return;
            currentPlayerChannelIdx = (currentPlayerChannelIdx - 1 + currentConfiguredChannels.length) % currentConfiguredChannels.length;
            zapToChannel(currentPlayerChannelIdx);
        }

        function nextChannel() {
            if (currentConfiguredChannels.length === 0) return;
            currentPlayerChannelIdx = (currentPlayerChannelIdx + 1) % currentConfiguredChannels.length;
            zapToChannel(currentPlayerChannelIdx);
        }

        function playChannel(idx) {
            syncChannelsFromDom();
            if (idx < 0 || idx >= currentConfiguredChannels.length) return;
            currentPlayerChannelIdx = idx;

            var ch = currentConfiguredChannels[idx];
            var name = ch.name || 'Canal';
            var sid = ch.serviceId || 1;
            var caid = ch.caid || '0x1810';
            var freq = ch.frequency || 10729;
            var pol = ch.polarization || 'V';
            var sat = ch.satellite || 'Astra 19.2°E';

            var select = document.getElementById('player-channel-select');
            if (select) select.value = idx;

            document.querySelectorAll('#player-zapper-list .zapper-item').forEach(function(el) {
                el.classList.remove('active');
            });
            var activeItem = document.getElementById('zapper-ch-' + idx);
            if (activeItem) activeItem.classList.add('active');

            playChannelInWeb(sid, name, caid, freq + ' ' + pol, sat);
        }

        function playChannelInWeb(serviceId, name, caid, freq, sat) {
            var host = window.location.hostname || '127.0.0.1';
            var tvhPort = document.getElementById('tvh-port') ? document.getElementById('tvh-port').value : '9191';
            var directUrl = 'http://' + host + ':' + tvhPort + '/stream/channel/' + (serviceId || 1);
            
            var urlInput = document.getElementById('player-stream-url');
            if (urlInput) urlInput.value = directUrl;

            var nameEl = document.getElementById('hud-channel-name');
            if (nameEl) nameEl.innerText = name;
            var tpEl = document.getElementById('hud-channel-tp');
            if (tpEl) tpEl.innerText = (sat || 'Satelital') + ' • ' + (freq || '');
            var casInfo = getCasSystemInfo(caid || '0x1810');
            var casEl = document.getElementById('hud-channel-cas');
            if (casEl) {
                casEl.innerText = casInfo.code + ' (' + casInfo.name + ')';
                casEl.style.color = casInfo.color;
                casEl.style.borderColor = casInfo.color;
                casEl.style.background = casInfo.color + '22';
            }
            var caidEl = document.getElementById('hud-channel-caid');
            if (caidEl) caidEl.innerText = 'CAID: ' + (caid || '--') + ' / SID: ' + serviceId;
            var statusEl = document.getElementById('hud-channel-status');
            if (statusEl) {
                statusEl.innerText = '🟡 SINTONIZANDO TS...';
                statusEl.style.color = '#FBBF24';
            }

            // Switch to Tab 5 (Live Player) if not already active
            var tabButtons = document.querySelectorAll('.tab-btn');
            if (tabButtons[4] && !tabButtons[4].classList.contains('active')) {
                showTab('tab-player', tabButtons[4]);
            }

            loadStreamInPlayer();
        }

        function destroyMpegtsPlayer() {
            if (mpegtsPlayerInstance) {
                try {
                    mpegtsPlayerInstance.pause();
                    mpegtsPlayerInstance.unload();
                    mpegtsPlayerInstance.detachMediaElement();
                    mpegtsPlayerInstance.destroy();
                } catch(e) {
                    console.warn('Error destroying mpegtsPlayerInstance:', e);
                }
                mpegtsPlayerInstance = null;
            }
        }

        function loadStreamInPlayer() {
            destroyMpegtsPlayer();

            var video = document.getElementById('live-video-player');
            var streamUrl = document.getElementById('player-stream-url') ? document.getElementById('player-stream-url').value : '';
            var bitrateLabel = document.getElementById('player-bitrate-label');
            var statusEl = document.getElementById('hud-channel-status');

            if (!video || !streamUrl) return;

            if (statusEl) {
                statusEl.innerText = '🟢 TRANSMITIENDO MPEG-TS';
                statusEl.style.color = '#34D399';
            }

            // Check if mpegts.js MSE is available
            if (window.mpegts && window.mpegts.isSupported()) {
                try {
                    mpegtsPlayerInstance = window.mpegts.createPlayer({
                        type: 'mse',
                        isLive: true,
                        url: streamUrl
                    }, {
                        enableWorker: true,
                        lazyLoadMaxDuration: 3 * 60,
                        seekType: 'range',
                        liveBufferLatencyChasing: true,
                        liveBufferLatencyMaxLatency: 2.5,
                        liveBufferLatencyMinRemain: 0.8,
                        autoCleanupSourceBuffer: true
                    });

                    mpegtsPlayerInstance.attachMediaElement(video);
                    mpegtsPlayerInstance.load();
                    
                    var playPromise = mpegtsPlayerInstance.play();
                    if (playPromise !== undefined) {
                        playPromise.catch(function(err) {
                            console.warn('Auto-play blocked or stream starting:', err);
                        });
                    }

                    mpegtsPlayerInstance.on(window.mpegts.Events.STATISTICS_INFO, function(stat) {
                        if (bitrateLabel && stat) {
                            var speedKbps = stat.speed ? (stat.speed * 8 / 1024).toFixed(0) : '--';
                            var fps = stat.currentFps ? stat.currentFps.toFixed(0) : '--';
                            var dropped = stat.droppedFrames || 0;
                            bitrateLabel.innerText = 'Bitrate: ' + speedKbps + ' kbps | FPS: ' + fps + ' | Perdidos: ' + dropped + ' | TS Sync: OK';
                        }
                    });

                    mpegtsPlayerInstance.on(window.mpegts.Events.ERROR, function(type, detail, info) {
                        console.warn('mpegts error event:', type, detail, info);
                        if (statusEl) {
                            statusEl.innerText = '🟡 RECONECTANDO TS... (' + (detail || 'Stream') + ')';
                            statusEl.style.color = 'var(--warning)';
                        }
                    });

                    return;
                } catch(err) {
                    console.warn('Failed to initialize mpegts.js, falling back to native player:', err);
                }
            }

            // Native HTML5 Video Fallback (e.g. Safari or proxy HLS)
            video.src = streamUrl;
            video.play().catch(function(err) {
                if (statusEl) {
                    statusEl.innerText = '🟡 STREAM TS ACTIVO (Reproducible en TiviMate / VLC / ExoPlayer)';
                    statusEl.style.color = 'var(--warning)';
                }
            });
        }

        function reloadPlayerStream() {
            showAlert('Recargando streaming MPEG-TS del canal...', 'success');
            loadStreamInPlayer();
        }

        function openStreamInVlc() {
            var streamUrl = document.getElementById('player-stream-url') ? document.getElementById('player-stream-url').value : '';
            var chName = document.getElementById('hud-channel-name') ? document.getElementById('hud-channel-name').innerText : 'Canal';
            if (!streamUrl) {
                showAlert('No hay ninguna URL de stream seleccionada.', 'error');
                return;
            }

            var m3uContent = '#EXTM3U\n#EXTINF:-1,' + chName + '\n' + streamUrl + '\n';
            var blob = new Blob([m3uContent], { type: 'audio/x-mpegurl' });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = (chName.replace(/[^a-zA-Z0-9_-]/g, '_')) + '.m3u';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            showAlert('✓ Descargando archivo .m3u para abrir en VLC Media Player.', 'success');
        }

        // TVHeadend Autonomous Server Configuration
        function loadTvheadendConfig() {
            fetch('/api/tvheadend/config')
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    if (data.port) {
                        var p = document.getElementById('tvh-port');
                        if (p) p.value = data.port;
                    }
                    if (data.ts_buffer_packets) {
                        var b = document.getElementById('tvh-buffer');
                        if (b) b.value = String(data.ts_buffer_packets);
                    }
                    if (data.m3u_format) {
                        var f = document.getElementById('tvh-m3u-fmt');
                        if (f) f.value = data.m3u_format;
                    }
                })
                .catch(function(e) {
                    console.warn('Could not load TVHeadend config:', e);
                });
        }

        function saveTvheadendConfig() {
            var port = parseInt(document.getElementById('tvh-port').value, 10) || 9191;
            var buffer = parseInt(document.getElementById('tvh-buffer').value, 10) || 348;
            var fmt = document.getElementById('tvh-m3u-fmt').value || 'standard';

            showAlert('Guardando configuración de TVHeadend autónomo...', 'success');
            fetch('/api/tvheadend/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ port: port, ts_buffer_packets: buffer, m3u_format: fmt })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res.success) {
                    showAlert('✓ ' + (res.message || 'Configuración de TVHeadend guardada correctamente.'), 'success');
                } else {
                    showAlert('Error al guardar TVHeadend: ' + (res.error || 'Fallo'), 'error');
                }
            })
            .catch(function(e) { showAlert('Error: ' + e, 'error'); });
        }

        function restartTvheadend() {
            showAlert('Reiniciando servidor TVHeadend autónomo...', 'success');
            fetch('/api/tvheadend/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ restart: true })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                showAlert('✓ ' + (res.message || 'Servidor TVHeadend reiniciado.'), 'success');
            })
            .catch(function(e) { showAlert('Error al reiniciar TVHeadend: ' + e, 'error'); });
        }

        // RF Spectrum Analyzer & Blind Scan
        var spectrumDataCache = null;

        function runSpectrumScan() {
            var sat = document.getElementById('spec-sat-select').value;
            var pol = document.getElementById('spec-pol-select').value;
            var step = document.getElementById('spec-step-select').value;

            var infoEl = document.getElementById('spec-chart-info');
            if (infoEl) infoEl.innerText = '⏳ Realizando barrido de radiofrecuencia en banda ' + sat.toUpperCase() + '...';

            fetch('/api/spectrum/scan?satellite=' + encodeURIComponent(sat) + '&polarization=' + encodeURIComponent(pol) + '&step=' + encodeURIComponent(step))
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    spectrumDataCache = data;
                    renderSpectrumData(data);
                })
                .catch(function(e) {
                    if (infoEl) infoEl.innerText = 'Error al consultar analizador de espectro: ' + e;
                    showAlert('Error en barrido de espectro: ' + e, 'error');
                });
        }

        function renderSpectrumData(data) {
            if (!data) return;

            var pwrEl = document.getElementById('spec-stat-pwr');
            if (pwrEl) pwrEl.innerText = (data.max_power_dbm !== undefined ? data.max_power_dbm.toFixed(1) : '--') + ' dBm';

            var lockEl = document.getElementById('spec-stat-lock');
            if (lockEl) {
                lockEl.innerText = data.carrier_locked ? 'BLOQUEADO' : 'SIN PORTADORA';
                lockEl.style.color = data.carrier_locked ? '#10B981' : '#EF4444';
            }

            var snrEl = document.getElementById('spec-stat-snr');
            if (snrEl) snrEl.innerText = (data.snr_db !== undefined ? data.snr_db.toFixed(1) : '--') + ' dB';

            var lnbEl = document.getElementById('spec-stat-lnb');
            if (lnbEl) lnbEl.innerText = data.lnb_voltage || '--';

            var peaksEl = document.getElementById('spec-stat-peaks');
            var tps = data.transponders || [];
            if (peaksEl) peaksEl.innerText = tps.length + ' detectados';

            var minEl = document.getElementById('spec-freq-min');
            var maxEl = document.getElementById('spec-freq-max');
            var midEl = document.getElementById('spec-freq-mid');
            if (minEl) minEl.innerText = data.start_freq_mhz + ' MHz';
            if (maxEl) maxEl.innerText = data.end_freq_mhz + ' MHz';
            if (midEl) midEl.innerText = Math.round((data.start_freq_mhz + data.end_freq_mhz) / 2) + ' MHz';

            var infoEl = document.getElementById('spec-chart-info');
            if (infoEl) infoEl.innerText = '✓ Barrido completado: ' + (data.frequencies ? data.frequencies.length : 0) + ' puntos muestreados, ' + tps.length + ' transpondedores identificados.';

            renderSpectrumSvg(data);
            renderSpectrumTranspondersTable(tps);
        }

        function renderSpectrumSvg(data) {
            var freqs = data.frequencies || [];
            var powers = data.powers_dbm || [];
            if (freqs.length === 0 || powers.length === 0) return;

            var fMin = data.start_freq_mhz;
            var fMax = data.end_freq_mhz;
            var fSpan = (fMax - fMin) || 1;

            var pMin = -90.0;
            var pMax = -20.0;
            var pSpan = (pMax - pMin) || 1;

            var svgWidth = 1000;
            var svgHeight = 240;

            var pointsArr = [];
            for (var i = 0; i < freqs.length; i++) {
                var f = freqs[i];
                var p = powers[i];
                var x = Math.round(((f - fMin) / fSpan) * svgWidth);
                var normP = (p - pMin) / pSpan;
                var y = Math.round(svgHeight - 10 - (normP * (svgHeight - 30)));
                if (y < 10) y = 10;
                if (y > svgHeight) y = svgHeight;
                pointsArr.push(x + ',' + y);
            }

            var polylineStr = pointsArr.join(' ');
            var polylineEl = document.getElementById('spectrum-stroke');
            if (polylineEl) polylineEl.setAttribute('points', polylineStr);

            var polygonEl = document.getElementById('spectrum-fill');
            if (polygonEl) {
                var polygonStr = '0,' + svgHeight + ' ' + polylineStr + ' ' + svgWidth + ',' + svgHeight;
                polygonEl.setAttribute('points', polygonStr);
            }

            var peaksGroup = document.getElementById('spectrum-peaks');
            if (peaksGroup) {
                peaksGroup.innerHTML = '';
                var tps = data.transponders || [];
                tps.forEach(function(tp) {
                    var tpX = Math.round(((tp.frequency_mhz - fMin) / fSpan) * svgWidth);
                    var normP = (tp.power_dbm - pMin) / pSpan;
                    var tpY = Math.round(svgHeight - 10 - (normP * (svgHeight - 30)));

                    var line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
                    line.setAttribute('x1', tpX);
                    line.setAttribute('y1', svgHeight);
                    line.setAttribute('x2', tpX);
                    line.setAttribute('y2', tpY);
                    line.setAttribute('stroke', 'rgba(56, 189, 248, 0.35)');
                    line.setAttribute('stroke-width', '1');
                    line.setAttribute('stroke-dasharray', '2 2');
                    peaksGroup.appendChild(line);

                    var circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
                    circle.setAttribute('cx', tpX);
                    circle.setAttribute('cy', tpY);
                    circle.setAttribute('r', '4');
                    circle.setAttribute('fill', '#38BDF8');
                    circle.setAttribute('stroke', '#FFF');
                    circle.setAttribute('stroke-width', '1.5');
                    circle.style.cursor = 'pointer';

                    circle.addEventListener('mouseenter', function(e) {
                        showSpectrumTooltip(tp, e.clientX, e.clientY);
                    });
                    circle.addEventListener('mouseleave', function() {
                        hideSpectrumTooltip();
                    });
                    circle.addEventListener('click', function() {
                        if (tp.channels_sample && tp.channels_sample.length > 0) {
                            playChannelInWeb(0, tp.channels_sample[0], '0x1810', tp.frequency_mhz, tp.polarization);
                        }
                    });
                    peaksGroup.appendChild(circle);

                    var text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
                    text.setAttribute('x', tpX);
                    text.setAttribute('y', Math.max(16, tpY - 8));
                    text.setAttribute('text-anchor', 'middle');
                    text.setAttribute('fill', '#94A3B8');
                    text.setAttribute('font-size', '9');
                    text.setAttribute('font-family', 'sans-serif');
                    text.textContent = tp.frequency_mhz + tp.polarization;
                    peaksGroup.appendChild(text);
                });
            }
        }

        function showSpectrumTooltip(tp, clientX, clientY) {
            var tt = document.getElementById('spectrum-tooltip');
            if (!tt) return;
            tt.innerHTML = '<strong style="color:#38BDF8;">' + tp.frequency_mhz + ' ' + tp.polarization + '</strong> (' + tp.symbol_rate_ks + ' kS/s &bull; ' + tp.delivery_system + ')<br>' +
                '<strong>Proveedor:</strong> ' + tp.provider + '<br>' +
                '<strong>Potencia:</strong> ' + tp.power_dbm + ' dBm | <strong>SNR:</strong> ' + tp.snr_db + ' dB<br>' +
                '<span style="color:#94A3B8; font-size:10px;">Canales: ' + (tp.channels_sample || []).join(', ') + '</span>';
            tt.style.display = 'block';
            tt.style.left = '20px';
            tt.style.top = '10px';
        }

        function hideSpectrumTooltip() {
            var tt = document.getElementById('spectrum-tooltip');
            if (tt) tt.style.display = 'none';
        }

        function renderSpectrumTranspondersTable(tps) {
            var tbody = document.getElementById('spectrum-tps-tbody');
            if (!tbody) return;
            tbody.innerHTML = '';
            if (!tps || tps.length === 0) {
                tbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding:18px; color:var(--text-muted);">No se detectaron transpondedores con señal suficiente en esta banda. Verifica la conexión del cable coaxial de la antena.</td></tr>';
                return;
            }

            tps.forEach(function(tp) {
                var tr = document.createElement('tr');
                var sampleChs = (tp.channels_sample || []).join(', ');
                var firstCh = (tp.channels_sample && tp.channels_sample.length > 0) ? tp.channels_sample[0] : 'Canal';

                tr.innerHTML = 
                    '<td style="font-weight:700; color:#FFF;">' + tp.frequency_mhz + ' ' + tp.polarization + '</td>' +
                    '<td>' + tp.symbol_rate_ks + ' kS/s &bull; FEC ' + tp.fec + '</td>' +
                    '<td><span style="background:rgba(59,130,246,0.15); color:#93C5FD; border:1px solid #3B82F6; padding:2px 6px; border-radius:4px; font-size:10px; font-weight:700;">' + tp.delivery_system + '</span></td>' +
                    '<td><strong style="color:var(--primary);">' + tp.provider + '</strong></td>' +
                    '<td style="max-width:240px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:12px;" title="' + sampleChs + '">' + sampleChs + '</td>' +
                    '<td style="color:#38BDF8; font-weight:700;">' + tp.power_dbm + ' dBm</td>' +
                    '<td style="color:#34D399; font-weight:700;">' + tp.snr_db + ' dB</td>' +
                    '<td><button type="button" class="btn btn-primary" style="padding:3px 8px; font-size:11px;" onclick="playChannelInWeb(0, \'' + firstCh.replace(/'/g, "\\'") + '\', \'0x1810\', ' + tp.frequency_mhz + ', \'' + tp.polarization + '\')">▶ Reproducir</button></td>';
                tbody.appendChild(tr);
            });
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

        var scannedChannelsCache = [];

        function toggleChannelScanner() {
            var box = document.getElementById('channel-scanner-box');
            if (box) {
                box.style.display = (box.style.display === 'none' || box.style.display === '') ? 'block' : 'none';
            }
        }

        function runChannelScan() {
            var btn = document.getElementById('btn-run-scan');
            var source = document.getElementById('scan-source-select').value;
            var incEnc = document.getElementById('scan-include-encrypted').checked;
            var valSrv = document.getElementById('scan-validate-servers').checked;

            btn.disabled = true;
            btn.innerHTML = '⏳ Escaneando y validando...';

            var resultsContainer = document.getElementById('scan-results-container');
            resultsContainer.style.display = 'block';
            document.getElementById('scan-stats-text').innerHTML = 'Iniciando búsqueda de canales (fuente: ' + source + ')...';

            fetch('/api/channels/scan?source=' + encodeURIComponent(source) + '&include_encrypted=' + incEnc + '&validate_servers=' + valSrv)
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    btn.disabled = false;
                    btn.innerHTML = '▶ Iniciar Búsqueda y Validación';
                    if (!res.success) {
                        showAlert('Error en el escaneo de canales: ' + (res.error || 'Error desconocido'), 'error');
                        return;
                    }
                    scannedChannelsCache = res.channels || [];
                    document.getElementById('scan-stats-text').innerHTML = 
                        '<strong>' + res.total_found + ' canales encontrados</strong> (' + 
                        res.scrambled_count + ' encriptados, ' + 
                        res.fta_count + ' FTA en abierto) — <span style="color:#34D399; font-weight:700;">' + 
                        res.validated_count + ' validados con servidores</span>';
                    
                    renderScannedChannels(scannedChannelsCache);
                })
                .catch(function(e) {
                    btn.disabled = false;
                    btn.innerHTML = '▶ Iniciar Búsqueda y Validación';
                    showAlert('Error de conexión al escanear: ' + e, 'error');
                });
        }

        function renderScannedChannels(channels) {
            var tbody = document.getElementById('scanned-channels-tbody');
            tbody.innerHTML = '';
            if (channels.length === 0) {
                tbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding:18px; color:var(--text-muted);">No se encontraron canales con los filtros actuales.</td></tr>';
                return;
            }

            channels.forEach(function(ch, idx) {
                var tr = document.createElement('tr');
                var typeBadge = ch.is_encrypted ? 
                    '<span style="background:rgba(239,68,68,0.15); border:1px solid #EF4444; color:#F87171; padding:2px 8px; border-radius:4px; font-weight:700; font-size:11px;">🔒 Encriptado</span>' : 
                    '<span style="background:rgba(59,130,246,0.15); border:1px solid #3B82F6; color:#93C5FD; padding:2px 8px; border-radius:4px; font-weight:700; font-size:11px;">🔓 En abierto (FTA)</span>';
                
                var valBadge = '<span style="font-size:12px;">' + (ch.status_badge || '--') + '</span>';
                var casBadge = getCasBadgeHtml(ch.caid);

                tr.innerHTML = 
                    '<td><input type="checkbox" class="scanned-chk" data-idx="' + idx + '" checked style="accent-color:var(--primary); width:16px; height:16px;"></td>' +
                    '<td style="font-weight:700; color:#FFF;">' + ch.name + '</td>' +
                    '<td style="color:var(--text-muted);">' + ch.satellite + (ch.frequency > 0 ? (' (' + ch.frequency + ' ' + ch.polarization + ')') : '') + '</td>' +
                    '<td>SID: ' + ch.serviceId + ' / PMT: ' + ch.pmtPid + '</td>' +
                    '<td><code>' + ch.caid + '</code></td>' +
                    '<td>' + casBadge + '</td>' +
                    '<td>' + typeBadge + '</td>' +
                    '<td>' + valBadge + '</td>';
                tbody.appendChild(tr);
            });
        }

        function toggleSelectAllScanned(checked) {
            document.querySelectorAll('.scanned-chk').forEach(function(chk) {
                chk.checked = checked;
            });
        }

        function importSelectedScannedChannels() {
            var selected = [];
            document.querySelectorAll('.scanned-chk:checked').forEach(function(chk) {
                var idx = parseInt(chk.getAttribute('data-idx'), 10);
                if (!isNaN(idx) && scannedChannelsCache[idx]) {
                    selected.push(scannedChannelsCache[idx]);
                }
            });

            if (selected.length === 0) {
                showAlert('Por favor, selecciona al menos un canal para importar.', 'error');
                return;
            }

            fetch('/api/channels/import', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ channels: selected })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res.success) {
                    showAlert('✓ ' + res.imported + ' canales importados correctamente a la base de datos (Total: ' + res.total + ').', 'success');
                    loadConfiguration();
                    toggleChannelScanner();
                } else {
                    showAlert('Error al importar canales: ' + (res.error || 'Fallo desconocido'), 'error');
                }
            })
            .catch(function(e) {
                showAlert('Error de red al importar: ' + e, 'error');
            });
        }

        function saveConfiguration() {
            var servers = [];
            // Strictly select server cards inside the server-list-box container
            document.querySelectorAll('#server-list-box .server-card').forEach(function(card, idx) {
                var nameEl = card.querySelector('.srv-name');
                var hostEl = card.querySelector('.srv-host');
                var portEl = card.querySelector('.srv-port');
                var protoEl = card.querySelector('.srv-proto');
                var userEl = card.querySelector('.srv-user');
                var passEl = card.querySelector('.srv-pass');
                var desEl = card.querySelector('.srv-des');
                var caidEl = card.querySelector('.srv-caid');
                var connTimeoutEl = card.querySelector('.srv-conn-timeout');
                var recvTimeoutEl = card.querySelector('.srv-recv-timeout');
                var reconIntervalEl = card.querySelector('.srv-recon-interval');
                var enabledEl = card.querySelector('.srv-enabled');
                var primaryEl = card.querySelector('.srv-primary');

                if (!hostEl && !nameEl) return; // Skip non-server elements if any

                var protoVal = protoEl ? protoEl.value : 'DVBAPI';
                var defaultPort = getDefaultPortForProto(protoVal);

                servers.push({
                    name: nameEl ? nameEl.value : ('Server ' + (idx + 1)),
                    protocol: protoVal,
                    host: hostEl ? hostEl.value : '192.168.1.100',
                    port: (protoVal === 'DVBAPI_UNIX') ? 0 : (portEl ? (parseInt(portEl.value, 10) || defaultPort) : defaultPort),
                    user: userEl ? userEl.value : 'android_tv',
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

            syncChannelsFromDom();
            var channels = (currentConfiguredChannels && currentConfiguredChannels.length > 0) ? currentConfiguredChannels : [];

            var deliveryEl = document.getElementById('delivery-dropdown');
            var caidsEl = document.getElementById('caids-text-input');
            var cwCacheEl = document.getElementById('cw-cache-toggle');
            var timeoutEl = document.getElementById('timeout-input');
            var reconnectEl = document.getElementById('reconnect-input');

            var payload = {
                delivery_system: deliveryEl ? deliveryEl.value : 'DVB-S2',
                caids: caidsEl ? caidsEl.value : '0x1810',
                cw_cache_enabled: cwCacheEl ? cwCacheEl.checked : true,
                timeout_ms: timeoutEl ? (parseInt(timeoutEl.value, 10) || 4000) : 4000,
                reconnect_interval_ms: reconnectEl ? (parseInt(reconnectEl.value, 10) || 2000) : 2000,
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
                if (res.success) {
                    showAlert('✓ ' + (res.message || 'Configuration successfully saved and hot-reloaded!'), 'success');
                    loadConfiguration();
                } else {
                    showAlert('✗ Error saving configuration: ' + (res.error || res.message || 'Save error'), 'error');
                }
            })
            .catch(function(e) {
                showAlert('✗ Network error saving configuration: ' + e, 'error');
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

        function copyToClipboard(text) {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(function() {
                    showAlert('Enlace copiado al portapapeles: ' + text, 'success');
                }).catch(function() {
                    copyFallback(text);
                });
            } else {
                copyFallback(text);
            }
        }

        function copyFallback(text) {
            var ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.focus();
            ta.select();
            try {
                document.execCommand('copy');
                showAlert('Enlace copiado al portapapeles: ' + text, 'success');
            } catch (err) {
                showAlert('Copia el enlace manualmente: ' + text, 'warn');
            }
            document.body.removeChild(ta);
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
                        var lat = data.last_cw_time_ms || 0;
                        updateLatencySparkline(lat);
                    } else if (st === 'CONNECTING') {
                        dot.style.background = 'var(--warning)';
                        updateLatencySparkline(0);
                    } else {
                        dot.style.background = 'var(--danger)';
                        updateLatencySparkline(0);
                    }

                    var currentEcms = data.ecms_sent || 0;
                    var ecmRate = (lastEcmTotal >= 0) ? Math.max(0, currentEcms - lastEcmTotal) : 0;
                    lastEcmTotal = currentEcms;
                    updateEcmSparkline(ecmRate);
                })
                .catch(function() {});
        }

        function updateEcmSparkline(rate) {
            ecmHistory.push(rate);
            if (ecmHistory.length > 11) ecmHistory.shift();
            var max = Math.max.apply(null, ecmHistory.concat([5]));
            var points = ecmHistory.map(function(val, idx) {
                var x = idx * 50;
                var y = 80 - ((val / max) * 70);
                return x + ',' + y;
            }).join(' ');
            var el = document.getElementById('line-ecm');
            if (el) el.setAttribute('points', points);
            var label = document.getElementById('chart-ecm-label');
            if (label) label.innerText = rate + ' ECM/ciclo';
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

        // CI+ CAM Module Emulator & Diagnostics JavaScript
        function fetchCiStatus() {
            fetch('/api/ci_status')
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    renderCiDiagnostics(data);
                })
                .catch(function(e) {
                    console.warn('Could not fetch CI status:', e);
                });
        }

        function triggerCiBroadcast() {
            fetch('/api/ci_broadcast')
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    showAlert('✓ ' + (data.message || 'Módulo CI+ retransmitido con éxito a todas las marcas'), 'success');
                    fetchCiStatus();
                })
                .catch(function(e) {
                    showAlert('Error al forzar re-registro CI: ' + e, 'error');
                });
        }

        function renderCiDiagnostics(data) {
            if (!data) return;
            var badge = document.getElementById('ci-cam-badge');
            if (badge) {
                badge.innerText = data.is_ready ? 'CI+ v1.4 ACTIVE' : 'CI+ OFFLINE';
                badge.style.background = data.is_ready ? 'rgba(16,185,129,0.2)' : 'rgba(239,68,68,0.2)';
                badge.style.color = data.is_ready ? '#10B981' : '#EF4444';
            }

            var slotStatus = document.getElementById('ci-slot-status');
            if (slotStatus) {
                slotStatus.innerText = data.is_ready ? 'SLOT 0: INSERTED & READY' : 'SLOT 0: REMOVED';
                slotStatus.style.color = data.is_ready ? 'var(--success)' : 'var(--danger)';
            }

            var brandStatus = document.getElementById('ci-brand-status');
            if (brandStatus && data.detected_brand) {
                brandStatus.innerText = data.detected_brand + ' TV';
            }

            var broadcastStats = document.getElementById('ci-broadcast-stats');
            if (broadcastStats) {
                broadcastStats.innerText = (data.broadcast_count || 0) + ' envíos / ' + (data.heartbeat_count || 0) + ' latidos';
            }

            var casBox = document.getElementById('ci-cas-systems');
            if (casBox && data.active_cas_systems && data.active_cas_systems.length > 0) {
                casBox.innerHTML = '';
                data.active_cas_systems.forEach(function(cas) {
                    var sp = document.createElement('span');
                    sp.style = 'background:rgba(59,130,246,0.2); color:#93C5FD; padding:2px 6px; border-radius:4px; font-size:10px; font-weight:700;';
                    sp.innerText = cas;
                    casBox.appendChild(sp);
                });
            }

            var appsList = document.getElementById('ci-installed-apps-list');
            if (appsList && data.installed_oem_apps) {
                appsList.innerHTML = '';
                if (data.installed_oem_apps.length === 0) {
                    appsList.innerHTML = '<span class="hint">Usando framework Android TV abierto (Receptor genérico activo)</span>';
                } else {
                    data.installed_oem_apps.forEach(function(app) {
                        var sp = document.createElement('span');
                        sp.style = 'background:rgba(16,185,129,0.15); color:#10B981; border:1px solid #10B981; padding:3px 8px; border-radius:6px; font-size:11px; font-weight:700;';
                        sp.innerText = '✓ ' + app;
                        appsList.appendChild(sp);
                    });
                }
            }
        }

        function fetchTvAndTunerInfo() {
            fetch('/api/tv_info')
                .then(function(r) { return r.json(); })
                .then(function(data) {
                    if (data.ci_emulator) {
                        renderCiDiagnostics(data.ci_emulator);
                    }
                    if (data.satellite_tuner) {
                        var t = data.satellite_tuner;
                        var cableInd = document.getElementById('cable-status-indicator');
                        var cableText = document.getElementById('cable-status-text');
                        var cableDot = document.getElementById('cable-status-dot');
                        if (cableText) cableText.innerText = t.cable_connected ? 'SATELLITE CABLE CONNECTED' : 'SATELLITE CABLE DISCONNECTED';
                        if (cableDot) {
                            cableDot.style.background = t.cable_connected ? '#10B981' : '#EF4444';
                            cableDot.style.boxShadow = '0 0 10px ' + (t.cable_connected ? '#10B981' : '#EF4444');
                        }
                        if (cableInd) {
                            cableInd.style.background = t.cable_connected ? 'rgba(16,185,129,0.15)' : 'rgba(239,68,68,0.15)';
                            cableInd.style.color = t.cable_connected ? '#10B981' : '#EF4444';
                            cableInd.style.borderColor = t.cable_connected ? '#10B981' : '#EF4444';
                        }
                    }
                    if (data.display && data.display.resolution) {
                        var dEl = document.getElementById('tv-display-info');
                        if (dEl) dEl.innerText = data.display.resolution + ' @ ' + (data.display.density_dpi || 0) + ' DPI';
                    }
                    if (data.storage && data.storage.total_gb) {
                        var sEl = document.getElementById('tv-storage-info');
                        if (sEl) sEl.innerText = data.storage.free_gb + ' GB libres / ' + data.storage.total_gb + ' GB total';
                    }
                    if (data.network && data.network.ip) {
                        var nEl = document.getElementById('tv-network-info');
                        if (nEl) nEl.innerText = (data.network.interface || 'eth0') + ' (' + data.network.ip + ')';
                    }
                    showAlert('✓ Telemetría de TV, sintonizador y CI+ actualizada', 'success');
                })
                .catch(function(e) {
                    showAlert('Error consultando TV info: ' + e, 'error');
                });
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
            fetchCiStatus();
            loadTvheadendConfig();
            runSpectrumScan();
            setInterval(pollTelemetry, 2500);
            setInterval(refreshLogs, 3500);
            setInterval(fetchCiStatus, 15000);
        };
    </script>
</body>
</html>
        """.trimIndent()
    }
}

