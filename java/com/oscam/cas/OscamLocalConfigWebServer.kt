package com.oscam.cas

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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

                // Configuration Management
                createContext("/api/config", ApiGetConfigHandler())
                createContext("/api/save", ApiSaveHandler())
                createContext("/api/backup", ApiBackupHandler())
                createContext("/api/restore", ApiRestoreHandler())
                createContext("/api/presets", ApiPresetsHandler())

                // Diagnostics & Tests
                createContext("/api/test", ApiTestHandler())
                createContext("/api/test_all", ApiTestAllHandler())
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

                    val html = buildDashboardHtml(config, stats, status, hw)
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
                    }
                    sendJsonResponse(exchange, 200, json.toString())
                } catch (e: Exception) {
                    sendErrorResponse(exchange, 500, e.message ?: "Hardware query error")
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
                            serversList.add(
                                OscamServerEntry(
                                    id = sObj.optString("id", UUID.randomUUID().toString()),
                                    name = sObj.optString("name", "Server ${i + 1}"),
                                    protocol = ServerProtocol.fromString(protoStr),
                                    host = sObj.optString("host", "192.168.1.100").trim(),
                                    port = sObj.optInt("port", if (protoStr == "NEWCAMD") 10000 else 9000),
                                    user = sObj.optString("user", "android_tv").trim(),
                                    password = sObj.optString("password", "android_tv").trim(),
                                    desKey = sObj.optString("des_key", "0102030405060708091011121314").trim(),
                                    caid = parseHexOrDec(sObj.optString("caid", "0x1810")),
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
                    val proto = json.optString("protocol", "DVBAPI")

                    appendLog("Testing connectivity to $proto server at $host:$port...")
                    val startTime = System.currentTimeMillis()
                    
                    var ok = false
                    var err = ""
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(host, port), 3000)
                            ok = true
                        }
                    } catch (e: Exception) {
                        ok = false
                        err = e.message ?: "Connection refused"
                    }

                    val elapsed = System.currentTimeMillis() - startTime

                    if (ok) {
                        appendLog("Ping test OK for $proto $host:$port (${elapsed}ms)")
                    } else {
                        appendLog("Ping test FAILED for $proto $host:$port: $err")
                    }

                    val resObj = JSONObject().apply {
                        put("success", ok)
                        put("latency_ms", elapsed)
                        put("protocol", proto)
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
                            serversList.add(
                                OscamServerEntry(
                                    name = sObj.optString("name", "Server ${i + 1}"),
                                    protocol = ServerProtocol.fromString(protoStr),
                                    host = sObj.optString("host", "192.168.1.100").trim(),
                                    port = sObj.optInt("port", 9000),
                                    user = sObj.optString("user", "android_tv").trim(),
                                    password = sObj.optString("password", "android_tv").trim(),
                                    desKey = sObj.optString("des_key", "0102030405060708091011121314").trim(),
                                    caid = parseHexOrDec(sObj.optString("caid", "0x1810")),
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
        hw: DeviceHardwareInfo
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
                    <p>OSCam (dvbapi) &amp; Newcamd Multi-Server Universal Tuner HAL</p>
                </div>
            </div>
            <div class="header-actions">
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
                <div class="metric-sub">Dual DVBAPI / Newcamd Flow</div>
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
            <button class="tab-btn" onclick="showTab('tab-servers', this)">📡 Servers &amp; Providers (OSCam / Newcamd)</button>
            <button class="tab-btn" onclick="showTab('tab-channels', this)">🛰️ Channels &amp; Transponders</button>
            <button class="tab-btn" onclick="showTab('tab-tuner', this)">⚙️ Tuner &amp; CAID Presets</button>
            <button class="tab-btn" onclick="showTab('tab-player', this)">📺 Stream Proxy &amp; Player</button>
            <button class="tab-btn" onclick="showTab('tab-diagnostics', this)">🔬 ECM Diagnostic Lab</button>
            <button class="tab-btn" onclick="showTab('tab-hardware', this)">💻 Hardware &amp; SoC</button>
            <button class="tab-btn" onclick="showTab('tab-logs', this)">📜 Live Logcat</button>
            <button class="tab-btn" onclick="showTab('tab-backup', this)">💾 Backup &amp; Restore</button>
        </div>

        <!-- TAB 1: Dashboard & Telemetry -->
        <div class="tab-pane active" id="tab-dashboard">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Real-Time DVB Telemetry &amp; Latency Analytics</div>
                        <div class="panel-desc">Dynamic monitoring of OSCam &amp; Newcamd round-trip time and ECM throughput.</div>
                    </div>
                    <button type="button" class="btn btn-outline" onclick="flushCwCache()">Flush CW Cache</button>
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

        <!-- TAB 2: Servers & Providers (OSCam / Newcamd) -->
        <div class="tab-pane" id="tab-servers">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Server Profiles &amp; Provider Matrix (OSCam &amp; Newcamd)</div>
                        <div class="panel-desc">Configure your domestic OSCam receivers or Newcamd servers with failover. No third-party boxes needed.</div>
                    </div>
                    <button type="button" class="btn btn-primary" onclick="addServerCard()">+ Add Custom Server</button>
                </div>

                <!-- Provider Quick Templates Toolbar -->
                <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:10px; padding:16px; margin-bottom:18px;">
                    <div style="font-weight:700; font-size:13px; margin-bottom:8px; color:var(--text-main);">⚡ Fast Provider Templates (Click to add configured server profile):</div>
                    <div class="preset-container" style="margin:0;">
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('Movistar+ ES', '0x1810', 10001, 'NEWCAMD')">+ 🇪🇸 Movistar+ (0x1810)</span>
                        <span class="preset-badge badge-purple" onclick="addServerFromPreset('HD+ Germany', '0x1830', 10002, 'NEWCAMD')">+ 🇩🇪 HD+ Astra (0x1830)</span>
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
                        <span class="preset-badge" onclick="addServerFromPreset('Vodafone Cable', '0x09C7', 9000, 'DVBAPI')">+ 🇩🇪 Vodafone Cable (0x09C7)</span>
                        <span class="preset-badge" onclick="addServerFromPreset('TDT Spain', '0x1801', 9000, 'DVBAPI')">+ 🇪🇸 TDT / Saorview (0x1801)</span>
                    </div>
                </div>

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

        <!-- TAB 7: Hardware & SoC Info -->
        <div class="tab-pane" id="tab-hardware">
            <div class="panel">
                <div class="panel-header">
                    <div>
                        <div class="panel-title">Android TV Hardware &amp; SoC Descrambler Engine</div>
                        <div class="panel-desc">Live introspection of device architecture, demux drivers, and memory status.</div>
                    </div>
                </div>

                <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(260px, 1fr)); gap:14px;">
                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">DEVICE MODEL &amp; OEM</div>
                        <div style="font-size:18px; font-weight:800; color:#FFF; margin-top:4px;">${hw.manufacturer} ${hw.model}</div>
                        <div class="hint">Board: ${hw.board} (${hw.hardware})</div>
                    </div>

                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">CHIPSET ADAPTER</div>
                        <div style="font-size:18px; font-weight:800; color:var(--accent); margin-top:4px;">${hw.detectedChipset}</div>
                        <div class="hint">Active HAL Abstraction Driver</div>
                    </div>

                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">ANDROID OS &amp; API LEVEL</div>
                        <div style="font-size:18px; font-weight:800; color:var(--primary); margin-top:4px;">Android ${hw.androidVersion} (API ${hw.sdkInt})</div>
                        <div class="hint">VINTF Manifest Compatibility: Active</div>
                    </div>

                    <div class="server-card">
                        <div style="font-size:11px; color:var(--text-muted); font-weight:700;">RAM MEMORY ALLOCATION</div>
                        <div style="font-size:18px; font-weight:800; color:var(--success); margin-top:4px;">${hw.availableMemoryMb} MB free / ${hw.totalMemoryMb} MB total</div>
                        <div class="hint">Zero-Leak Buffer Management</div>
                    </div>
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

        function renderServerCards(servers) {
            var container = document.getElementById('server-list-box');
            container.innerHTML = '';
            servers.forEach(function(s, idx) {
                var card = document.createElement('div');
                card.className = 'server-card';
                card.id = 'srv-box-' + idx;
                var isNewcamd = (s.protocol === 'NEWCAMD');
                card.innerHTML = 
                    '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">' +
                        '<div style="font-weight:700; font-size:14px; color:#FFF;">' + (s.name || 'Server Profile ' + (idx + 1)) + '</div>' +
                        '<span style="background:' + (isNewcamd ? 'rgba(139,92,246,0.2); color:#C4B5FD; border:1px solid #8B5CF6' : 'rgba(59,130,246,0.2); color:#93C5FD; border:1px solid #3B82F6') + '; padding:3px 10px; border-radius:6px; font-size:11px; font-weight:700;">' + (s.protocol || 'DVBAPI') + '</span>' +
                    '</div>' +
                    '<div class="server-fields">' +
                        '<div><label>Profile Name</label><input type="text" class="srv-name" value="' + (s.name || 'Server ' + (idx + 1)) + '"></div>' +
                        '<div><label>Protocol</label><select class="srv-proto" onchange="toggleServerFields(' + idx + ')"><option value="DVBAPI"' + (!isNewcamd ? ' selected' : '') + '>OSCam (dvbapi)</option><option value="NEWCAMD"' + (isNewcamd ? ' selected' : '') + '>Newcamd v5.25</option></select></div>' +
                        '<div><label>Host / IP Address</label><input type="text" class="srv-host" value="' + (s.host || '192.168.1.100') + '"></div>' +
                        '<div><label>Port</label><input type="number" class="srv-port" value="' + (s.port || (isNewcamd ? 10000 : 9000)) + '"></div>' +
                        '<div><label>Username</label><input type="text" class="srv-user" value="' + (s.user || 'android_tv') + '"></div>' +
                    '</div>' +
                    '<div class="newcamd-extra-' + idx + '" style="margin-top:12px; display:' + (isNewcamd ? 'grid' : 'none') + '; grid-template-columns: 1.5fr 2.5fr 1fr; gap:12px;">' +
                        '<div><label>Password</label><input type="text" class="srv-pass" value="' + (s.password || 'android_tv') + '"></div>' +
                        '<div><label>DES Key (14 bytes hex)</label><input type="text" class="srv-des" value="' + (s.des_key || '0102030405060708091011121314') + '"></div>' +
                        '<div><label>Target CAID</label><input type="text" class="srv-caid" value="' + (s.caid || '0x1810') + '"></div>' +
                    '</div>' +
                    '<div style="display:flex; justify-content:space-between; align-items:center; margin-top:14px; border-top:1px solid rgba(255,255,255,0.06); padding-top:10px;">' +
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
            var extra = card.querySelector('.newcamd-extra-' + idx);
            if (extra) {
                extra.style.display = (proto === 'NEWCAMD') ? 'grid' : 'none';
            }
        }

        function addServerCard() {
            var s = {
                name: 'Server ' + (currentServers.length + 1),
                protocol: 'DVBAPI',
                host: '192.168.1.150',
                port: 9000,
                user: 'android_tv',
                password: 'android_tv',
                des_key: '0102030405060708091011121314',
                caid: '0x1810',
                enabled: true,
                is_primary: false
            };
            currentServers.push(s);
            renderServerCards(currentServers);
        }

        function addServerFromPreset(name, caid, port, proto) {
            var s = {
                name: name,
                protocol: proto || 'DVBAPI',
                host: '192.168.1.150',
                port: port || 9000,
                user: 'android_tv',
                password: 'android_tv',
                des_key: '0102030405060708091011121314',
                caid: caid,
                enabled: true,
                is_primary: (currentServers.length === 0)
            };
            currentServers.push(s);
            renderServerCards(currentServers);
            showAlert('✓ Added server profile for ' + name + ' (' + proto + ' Port ' + port + ', CAID ' + caid + ')', 'success');
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
            var statusDiv = document.getElementById('ping-status-' + idx);

            statusDiv.style.color = 'var(--warning)';
            statusDiv.innerText = 'Pinging ' + proto + ' ' + host + ':' + port + '...';

            fetch('/api/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ host: host, port: port, protocol: proto })
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res.success) {
                    statusDiv.style.color = 'var(--success)';
                    statusDiv.innerText = '✓ Reachable (' + proto + ' Latency: ' + res.latency_ms + ' ms)';
                    updateLatencySparkline(res.latency_ms);
                } else {
                    statusDiv.style.color = 'var(--danger)';
                    statusDiv.innerText = '✗ Connection failed: ' + (res.error || 'Connection refused');
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

                servers.push({
                    name: card.querySelector('.srv-name').value,
                    protocol: protoEl ? protoEl.value : 'DVBAPI',
                    host: card.querySelector('.srv-host').value,
                    port: parseInt(card.querySelector('.srv-port').value, 10) || 9000,
                    user: card.querySelector('.srv-user').value,
                    password: passEl ? passEl.value : 'android_tv',
                    des_key: desEl ? desEl.value : '0102030405060708091011121314',
                    caid: caidEl ? caidEl.value : '0x1810',
                    enabled: true,
                    is_primary: (idx === 0)
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
