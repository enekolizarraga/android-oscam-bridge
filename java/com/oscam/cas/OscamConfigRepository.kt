package com.oscam.cas

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "oscam_cas_settings")

/**
 * Supported DVB broadcast delivery systems.
 * DVB-S/S2/S2X (Satellite) is the active default.
 */
enum class TunerDeliverySystem {
    DVBS,   ///< Satellite (DVB-S, DVB-S2, DVB-S2X) [Default]
    DVBT,   ///< Terrestrial (DVB-T, DVB-T2)
    DVBC,   ///< Cable (DVB-C, DVB-C2)
    HYBRID; ///< Hybrid / Multi-tuner auto-detection

    companion object {
        fun fromString(value: String): TunerDeliverySystem {
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DVBS
        }
    }
}

/**
 * Model representing an OSCam server profile (enables multi-server & fallback setups).
 */
data class OscamServerEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Primary Server",
    val host: String = "192.168.1.100",
    val port: Int = 9000,
    val user: String = "android_tv",
    val enabled: Boolean = true,
    val isPrimary: Boolean = true
)

/**
 * Model representing a Satellite/DVB channel entry with transponder details.
 */
data class OscamChannelEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Channel",
    val satellite: String = "Astra 19.2°E",
    val frequency: Int = 11000,
    val polarization: String = "H",
    val symbolRate: Int = 22000,
    val serviceId: Int = 1,
    val pmtPid: Int = 100,
    val caid: Int = 0x1810,
    val streamUrl: String = ""
)

/**
 * Model representing a saved Wake-on-LAN target device.
 */
data class OscamWolEntry(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "OSCam Receiver",
    val mac: String = "00:11:22:33:44:55",
    val broadcastIp: String = "255.255.255.255"
)

/**
 * Diagnostic snapshot of TV hardware SoC and framework capabilities.
 */
data class DeviceHardwareInfo(
    val model: String,
    val manufacturer: String,
    val board: String,
    val hardware: String,
    val socPlatform: String,
    val androidVersion: String,
    val sdkInt: Int,
    val detectedChipset: String,
    val totalMemoryMb: Long,
    val availableMemoryMb: Long
)

/**
 * Immutable configuration data model for the OSCam CAS Bridge.
 */
data class OscamConfig(
    val servers: List<OscamServerEntry> = listOf(OscamServerEntry()),
    val deliverySystem: TunerDeliverySystem = TunerDeliverySystem.DVBS,
    val caids: List<Int> = defaultCaidsFor(TunerDeliverySystem.DVBS),
    val autoStartOnBoot: Boolean = true,
    val connectTimeoutMs: Int = 4000,
    val reconnectIntervalMs: Int = 2000,
    val cwCacheEnabled: Boolean = true,
    val channels: List<OscamChannelEntry> = defaultChannels(),
    val wolProfiles: List<OscamWolEntry> = defaultWolProfiles()
) {
    // Primary active server getters for backward compatibility
    val primaryServer: OscamServerEntry
        get() = servers.firstOrNull { it.enabled && it.isPrimary }
            ?: servers.firstOrNull { it.enabled }
            ?: servers.firstOrNull()
            ?: OscamServerEntry()

    val serverHost: String get() = primaryServer.host
    val serverPort: Int get() = primaryServer.port
    val username: String get() = primaryServer.user

    fun getCaidsCsv(): String = caids.joinToString(", ") { "0x%04X".format(it) }

    companion object {
        fun defaultCaidsFor(system: TunerDeliverySystem): List<Int> {
            return when (system) {
                TunerDeliverySystem.DVBS -> listOf(
                    0x1810, // Movistar+ DVB-S2
                    0x1830, // HD+ Astra DVB-S2
                    0x1843, // HD+ Astra DVB-S2
                    0x0100, // Seca / Mediaguard
                    0x0500, // Viaccess (Fransat / SRG / BIS DVB-S2)
                    0x0B00, // Conax (Canal Digital DVB-S2)
                    0x0604, // Irdeto (Nova DVB-S2)
                    0x09CD  // NDS VideoGuard (Sky DVB-S2)
                )
                TunerDeliverySystem.DVBT -> listOf(
                    0x1801, // Nagra Terrestrial
                    0x0604, // Irdeto DVB-T2
                    0x0B00, // Conax Terrestrial
                    0x0500  // Viaccess DVB-T2
                )
                TunerDeliverySystem.DVBC -> listOf(
                    0x1801, // Nagravision Cable
                    0x0604, // Irdeto Cable
                    0x0B00, // Conax Cable
                    0x098C  // VideoGuard Cable
                )
                TunerDeliverySystem.HYBRID -> listOf(
                    0x1810, 0x1830, 0x1843, 0x1801, 0x0100, 0x0500, 0x0B00, 0x0604, 0x09CD
                )
            }
        }

        fun defaultChannels(): List<OscamChannelEntry> {
            return listOf(
                OscamChannelEntry(
                    name = "Movistar+ Estrenos HD",
                    satellite = "Astra 19.2°E",
                    frequency = 10729,
                    polarization = "V",
                    symbolRate = 22000,
                    serviceId = 30001,
                    pmtPid = 1024,
                    caid = 0x1810,
                    streamUrl = "http://127.0.0.1:9191/play?url=http://satip-receiver/stream?freq=10729&pol=v&sr=22000"
                ),
                OscamChannelEntry(
                    name = "HD+ RTL UHD",
                    satellite = "Astra 19.2°E",
                    frequency = 11214,
                    polarization = "H",
                    symbolRate = 22000,
                    serviceId = 13410,
                    pmtPid = 96,
                    caid = 0x1830,
                    streamUrl = "http://127.0.0.1:9191/play?url=http://satip-receiver/stream?freq=11214&pol=h&sr=22000"
                ),
                OscamChannelEntry(
                    name = "Canal+ Sport HD",
                    satellite = "Astra 19.2°E",
                    frequency = 12012,
                    polarization = "V",
                    symbolRate = 29700,
                    serviceId = 8801,
                    pmtPid = 100,
                    caid = 0x0100,
                    streamUrl = "http://127.0.0.1:9191/play?url=http://satip-receiver/stream?freq=12012&pol=v&sr=29700"
                ),
                OscamChannelEntry(
                    name = "Fransat TF1 HD",
                    satellite = "Eutelsat 5W",
                    frequency = 11096,
                    polarization = "V",
                    symbolRate = 29950,
                    serviceId = 401,
                    pmtPid = 4010,
                    caid = 0x0500,
                    streamUrl = "http://127.0.0.1:9191/play?url=http://satip-receiver/stream?freq=11096&pol=v&sr=29950"
                ),
                OscamChannelEntry(
                    name = "Sky Sport 1 HD",
                    satellite = "Astra 19.2°E",
                    frequency = 11720,
                    polarization = "H",
                    symbolRate = 27500,
                    serviceId = 130,
                    pmtPid = 98,
                    caid = 0x098C,
                    streamUrl = "http://127.0.0.1:9191/play?url=http://satip-receiver/stream?freq=11720&pol=h&sr=27500"
                )
            )
        }

        fun defaultWolProfiles(): List<OscamWolEntry> {
            return listOf(
                OscamWolEntry(
                    label = "Living Room Satellite Receiver",
                    mac = "00:11:22:33:44:55",
                    broadcastIp = "255.255.255.255"
                ),
                OscamWolEntry(
                    label = "Home OSCam Server (Docker/Linux)",
                    mac = "AA:BB:CC:DD:EE:FF",
                    broadcastIp = "192.168.1.255"
                )
            )
        }
    }
}

/**
 * Repository responsible for persistent configuration management.
 * Persists settings via AndroidX DataStore and exports a synchronized JSON
 * configuration file for the native C++ HAL daemon.
 */
class OscamConfigRepository(private val context: Context) {

    companion object {
        private const val TAG = "OscamCasBridge"

        val KEY_SERVERS_JSON = stringPreferencesKey("oscam_servers_json")
        val KEY_HOST = stringPreferencesKey("oscam_host")
        val KEY_PORT = intPreferencesKey("oscam_port")
        val KEY_USER = stringPreferencesKey("oscam_user")
        val KEY_DELIVERY_SYSTEM = stringPreferencesKey("oscam_delivery_system")
        val KEY_CAIDS = stringPreferencesKey("oscam_caids_csv")
        val KEY_AUTOSTART = booleanPreferencesKey("oscam_autostart")
        val KEY_TIMEOUT = intPreferencesKey("oscam_timeout_ms")
        val KEY_RECONNECT_INTERVAL = intPreferencesKey("oscam_reconnect_interval_ms")
        val KEY_CW_CACHE = booleanPreferencesKey("oscam_cw_cache")
        val KEY_CHANNELS_JSON = stringPreferencesKey("oscam_channels_json")
        val KEY_WOL_JSON = stringPreferencesKey("oscam_wol_json")

        // DEVICE-SPECIFIC: Vendor shared directory for hardware daemon (/vendor/bin/hw)
        private const val VENDOR_CONFIG_DIR = "/data/vendor/oscam"
        private const val VENDOR_CONFIG_FILE = "/data/vendor/oscam/config.json"
    }

    val configFlow: Flow<OscamConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading Preferences DataStore: ${exception.message}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val host = prefs[KEY_HOST] ?: "192.168.1.100"
            val port = prefs[KEY_PORT] ?: 9000
            val user = prefs[KEY_USER] ?: "android_tv"
            val deliveryStr = prefs[KEY_DELIVERY_SYSTEM] ?: TunerDeliverySystem.DVBS.name
            val delivery = TunerDeliverySystem.fromString(deliveryStr)
            val defaultCsv = OscamConfig.defaultCaidsFor(delivery).joinToString(", ") { "0x%04X".format(it) }
            val caidsCsv = prefs[KEY_CAIDS] ?: defaultCsv
            val autoStart = prefs[KEY_AUTOSTART] ?: true
            val timeout = prefs[KEY_TIMEOUT] ?: 4000
            val reconnect = prefs[KEY_RECONNECT_INTERVAL] ?: 2000
            val cwCache = prefs[KEY_CW_CACHE] ?: true

            val serversJson = prefs[KEY_SERVERS_JSON]
            val parsedServers = if (!serversJson.isNullOrEmpty()) {
                parseServersJson(serversJson)
            } else {
                listOf(OscamServerEntry(name = "Primary Server", host = host, port = port, user = user, enabled = true, isPrimary = true))
            }

            val channelsJson = prefs[KEY_CHANNELS_JSON]
            val parsedChannels = if (!channelsJson.isNullOrEmpty()) {
                parseChannelsJson(channelsJson)
            } else {
                OscamConfig.defaultChannels()
            }

            val wolJson = prefs[KEY_WOL_JSON]
            val parsedWol = if (!wolJson.isNullOrEmpty()) {
                parseWolJson(wolJson)
            } else {
                OscamConfig.defaultWolProfiles()
            }

            val parsedCaids = parseCaidsCsv(caidsCsv)

            OscamConfig(
                servers = parsedServers,
                deliverySystem = delivery,
                caids = parsedCaids,
                autoStartOnBoot = autoStart,
                connectTimeoutMs = timeout,
                reconnectIntervalMs = reconnect,
                cwCacheEnabled = cwCache,
                channels = parsedChannels,
                wolProfiles = parsedWol
            )
        }

    suspend fun saveConfig(config: OscamConfig) {
        withContext(Dispatchers.IO) {
            try {
                val primary = config.primaryServer
                val serversJson = serializeServersJson(config.servers)
                val channelsJson = serializeChannelsJson(config.channels)
                val wolJson = serializeWolJson(config.wolProfiles)

                context.dataStore.edit { prefs ->
                    prefs[KEY_SERVERS_JSON] = serversJson
                    prefs[KEY_CHANNELS_JSON] = channelsJson
                    prefs[KEY_WOL_JSON] = wolJson
                    prefs[KEY_HOST] = primary.host.trim()
                    prefs[KEY_PORT] = primary.port
                    prefs[KEY_USER] = primary.user.trim()
                    prefs[KEY_DELIVERY_SYSTEM] = config.deliverySystem.name
                    prefs[KEY_CAIDS] = config.getCaidsCsv()
                    prefs[KEY_AUTOSTART] = config.autoStartOnBoot
                    prefs[KEY_TIMEOUT] = config.connectTimeoutMs
                    prefs[KEY_RECONNECT_INTERVAL] = config.reconnectIntervalMs
                    prefs[KEY_CW_CACHE] = config.cwCacheEnabled
                }
                Log.i(TAG, "Configuration saved to DataStore (${config.servers.size} servers, ${config.channels.size} channels)")
                syncNativeConfigFile(config)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save configuration: ${e.message}", e)
                throw e
            }
        }
    }

    suspend fun getCurrentConfig(): OscamConfig {
        return configFlow.first()
    }

    fun getHardwareInfo(): DeviceHardwareInfo {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)

        val soc = Build.HARDWARE.ifEmpty { Build.BOARD }
        val chipsetDesc = when {
            soc.contains("amlogic", ignoreCase = true) || Build.BOARD.contains("gxl", ignoreCase = true) || Build.BOARD.contains("g12", ignoreCase = true) || Build.BOARD.contains("sm1", ignoreCase = true) || Build.BOARD.contains("sc2", ignoreCase = true) -> "Amlogic (meson/g12/sm1/sc2) [Hardware CA /dev/amstream]"
            soc.contains("mtk", ignoreCase = true) || soc.contains("mt5", ignoreCase = true) || soc.contains("mt9", ignoreCase = true) -> "MediaTek (/dev/mtk_demux hardware descrambler)"
            soc.contains("rtd", ignoreCase = true) || soc.contains("realtek", ignoreCase = true) -> "Realtek (/dev/rtk_dvb hardware descrambler)"
            soc.contains("bcm", ignoreCase = true) || soc.contains("broadcom", ignoreCase = true) -> "Broadcom (/dev/bcm_demux hardware descrambler)"
            soc.contains("syna", ignoreCase = true) || soc.contains("berlin", ignoreCase = true) || soc.contains("vs680", ignoreCase = true) -> "Synaptics VideoSmart (/dev/galois_demux)"
            soc.contains("novatek", ignoreCase = true) || soc.contains("nt72", ignoreCase = true) -> "Novatek (/dev/nvt_demux)"
            else -> "Generic Android TV SoC (Universal Hardware / Software Fallback)"
        }

        return DeviceHardwareInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            socPlatform = soc,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            detectedChipset = chipsetDesc,
            totalMemoryMb = totalMb,
            availableMemoryMb = availMb
        )
    }

    fun parseCaidsCsv(csv: String): List<Int> {
        return csv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                try {
                    if (token.startsWith("0x", ignoreCase = true)) {
                        token.substring(2).toInt(16)
                    } else {
                        token.toInt(10)
                    }
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Ignored invalid CAID token: '$token'")
                    null
                }
            }
            .ifEmpty { OscamConfig.defaultCaidsFor(TunerDeliverySystem.DVBS) }
    }

    private fun serializeServersJson(servers: List<OscamServerEntry>): String {
        val array = JSONArray()
        for (server in servers) {
            val obj = JSONObject().apply {
                put("id", server.id)
                put("name", server.name)
                put("host", server.host)
                put("port", server.port)
                put("user", server.user)
                put("enabled", server.enabled)
                put("is_primary", server.isPrimary)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseServersJson(jsonStr: String): List<OscamServerEntry> {
        val list = mutableListOf<OscamServerEntry>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    OscamServerEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "Server ${i + 1}"),
                        host = obj.optString("host", "192.168.1.100"),
                        port = obj.optInt("port", 9000),
                        user = obj.optString("user", "android_tv"),
                        enabled = obj.optBoolean("enabled", true),
                        isPrimary = obj.optBoolean("is_primary", i == 0)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing servers JSON: ${e.message}", e)
        }
        return list.ifEmpty { listOf(OscamServerEntry()) }
    }

    private fun serializeChannelsJson(channels: List<OscamChannelEntry>): String {
        val array = JSONArray()
        for (ch in channels) {
            val obj = JSONObject().apply {
                put("id", ch.id)
                put("name", ch.name)
                put("satellite", ch.satellite)
                put("frequency", ch.frequency)
                put("polarization", ch.polarization)
                put("symbolRate", ch.symbolRate)
                put("serviceId", ch.serviceId)
                put("pmtPid", ch.pmtPid)
                put("caid", ch.caid)
                put("streamUrl", ch.streamUrl)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseChannelsJson(jsonStr: String): List<OscamChannelEntry> {
        val list = mutableListOf<OscamChannelEntry>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    OscamChannelEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "Channel ${i + 1}"),
                        satellite = obj.optString("satellite", "Astra 19.2°E"),
                        frequency = obj.optInt("frequency", 11000),
                        polarization = obj.optString("polarization", "H"),
                        symbolRate = obj.optInt("symbolRate", 22000),
                        serviceId = obj.optInt("serviceId", 1),
                        pmtPid = obj.optInt("pmtPid", 100),
                        caid = obj.optInt("caid", 0x1810),
                        streamUrl = obj.optString("streamUrl", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing channels JSON: ${e.message}", e)
        }
        return list.ifEmpty { OscamConfig.defaultChannels() }
    }

    private fun serializeWolJson(wolProfiles: List<OscamWolEntry>): String {
        val array = JSONArray()
        for (w in wolProfiles) {
            val obj = JSONObject().apply {
                put("id", w.id)
                put("label", w.label)
                put("mac", w.mac)
                put("broadcastIp", w.broadcastIp)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseWolJson(jsonStr: String): List<OscamWolEntry> {
        val list = mutableListOf<OscamWolEntry>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    OscamWolEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        label = obj.optString("label", "Receiver ${i + 1}"),
                        mac = obj.optString("mac", "00:11:22:33:44:55"),
                        broadcastIp = obj.optString("broadcastIp", "255.255.255.255")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WoL JSON: ${e.message}", e)
        }
        return list.ifEmpty { OscamConfig.defaultWolProfiles() }
    }

    private fun syncNativeConfigFile(config: OscamConfig) {
        val primary = config.primaryServer
        val json = JSONObject().apply {
            put("server_host", primary.host)
            put("server_port", primary.port)
            put("username", primary.user)
            put("delivery_system", config.deliverySystem.name)
            put("timeout_ms", config.connectTimeoutMs)
            put("reconnect_interval_ms", config.reconnectIntervalMs)
            put("cw_cache_enabled", config.cwCacheEnabled)

            val caidArray = JSONArray()
            config.caids.forEach { caidArray.put(it) }
            put("caids", caidArray)

            val serversArray = JSONArray()
            config.servers.forEach { s ->
                val sObj = JSONObject().apply {
                    put("name", s.name)
                    put("host", s.host)
                    put("port", s.port)
                    put("user", s.user)
                    put("enabled", s.enabled)
                    put("is_primary", s.isPrimary)
                }
                serversArray.put(sObj)
            }
            put("servers", serversArray)

            val channelsArray = JSONArray()
            config.channels.forEach { ch ->
                val chObj = JSONObject().apply {
                    put("name", ch.name)
                    put("satellite", ch.satellite)
                    put("frequency", ch.frequency)
                    put("polarization", ch.polarization)
                    put("symbolRate", ch.symbolRate)
                    put("serviceId", ch.serviceId)
                    put("pmtPid", ch.pmtPid)
                    put("caid", ch.caid)
                }
                channelsArray.put(chObj)
            }
            put("channels", channelsArray)
        }

        val jsonString = json.toString(4)

        // 1. App internal private file
        val localFile = File(context.filesDir, "oscam_config.json")
        try {
            FileOutputStream(localFile).use { fos ->
                fos.write(jsonString.toByteArray(Charsets.UTF_8))
            }
            Log.d(TAG, "Local config file synchronized at: ${localFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write local config file: ${e.message}")
        }

        // 2. DEVICE-SPECIFIC: Vendor partition synchronization on rooted TV
        try {
            val vendorDir = File(VENDOR_CONFIG_DIR)
            if (vendorDir.exists() && vendorDir.canWrite()) {
                val vendorFile = File(VENDOR_CONFIG_FILE)
                FileOutputStream(vendorFile).use { fos ->
                    fos.write(jsonString.toByteArray(Charsets.UTF_8))
                }
                Log.i(TAG, "Vendor config file synchronized at /data/vendor/oscam/config.json")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Vendor config not directly writable without root sepolicy: ${e.message}")
        }
    }
}
