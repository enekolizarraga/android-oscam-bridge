package com.lizarragaeus.oscambridge

import android.content.Context
import android.media.MediaCas
import android.media.MediaCasException
import android.util.Log

/**
 * TV Input Framework (TIF) and MediaCas integration bridge.
 * Enables Android TV native TV applications (e.g. Google Live Channels, TCL Channel App,
 * Sony, Philips, Hisense native TV apps) to descramble DVB-S/S2/S2X and DVB-T/T2 channels
 * via the OSCam CAS HAL plugin without requiring modifications to the TV OEM app.
 *
 * Log Tag: OscamCasBridge
 */
class OscamTvInputBridge(private val context: Context) {

    companion object {
        private const val TAG = "OscamCasBridge"

        // Common satellite and terrestrial CAIDs
        val COMMON_SATELLITE_CAIDS = listOf(
            0x1810, // Nagravision (Movistar+ DVB-S2)
            0x1830, // Nagravision (HD+ Astra DVB-S2)
            0x1843, // Nagravision (HD+ Astra DVB-S2)
            0x0100, // Seca / Mediaguard (Canal+ legacy)
            0x0500, // Viaccess (SRG Swiss, BIS TV, Fransat DVB-S2/SX)
            0x0B00, // Conax (Canal Digital DVB-S2)
            0x0604, // Irdeto (Nova DVB-S2)
            0x09CD, // NDS VideoGuard (Sky DVB-S2)
            0x1801  // Nagra Terrestrial (DVB-T2)
        )
    }

    private var mediaCasInstance: MediaCas? = null
    private var activeSession: MediaCas.Session? = null
    private var tclReceiver: android.content.BroadcastReceiver? = null

    init {
        // Automatically register TCL-specific broadcast hooks if running on a TCL TV
        if (TclTvCompat.detectTvBrand() == TclTvCompat.TvBrand.TCL) {
            Log.i(TAG, "TCL TV detected: ${TclTvCompat.getTclModelDetails()}. Initializing OEM hooks...")
            tclReceiver = TclTvCompat.registerTclChannelListener(context) { details ->
                Log.i(TAG, "OscamTvInputBridge handling TCL event: $details")
            }
        }
    }

    /**
     * Gets detected TV brand name.
     */
    fun getTvBrand(): TclTvCompat.TvBrand = TclTvCompat.detectTvBrand()

    /**
     * Gets TV model details.
     */
    fun getModelDetails(): String = TclTvCompat.getTclModelDetails()

    /**
     * Inspects OEM broadcast TV apps installed on this television.
     */
    fun getOemApps(): List<TclTvCompat.OemAppInfo> = TclTvCompat.inspectInstalledOemApps(context)

    /**
     * Checks if Android MediaCas framework recognizes the specified CAID.
     */
    fun isCaidSupportedBySystem(caSystemId: Int): Boolean {
        val supported = MediaCas.isSystemIdSupported(caSystemId)
        Log.i(TAG, "Checking system MediaCas support for CAID 0x%04X: %b".format(caSystemId, supported))
        return supported
    }

    /**
     * Enumerates registered MediaCas plugins on the device.
     */
    fun getRegisteredCasPlugins(): List<MediaCas.PluginDescriptor> {
        val plugins = MediaCas.enumeratePlugins()
        for (plugin in plugins) {
            Log.i(TAG, "Registered CAS plugin: '${plugin.name}' (CAID: 0x%04X)".format(plugin.systemId))
        }
        return plugins.toList()
    }

    /**
     * Binds an active broadcast channel with a specific CAID.
     */
    fun bindChannel(caSystemId: Int): Boolean {
        try {
            Log.i(TAG, "Binding broadcast channel with CAID: 0x%04X".format(caSystemId))

            activeSession?.close()
            activeSession = null

            mediaCasInstance?.close()
            mediaCasInstance = MediaCas(context, caSystemId, null, MediaCas.SCRAMBLING_MODE_DVB_CSA1).apply {
                activeSession = openSession()
            }

            Log.i(TAG, "MediaCas session opened successfully for CAID 0x%04X".format(caSystemId))
            return true
        } catch (e: MediaCasException) {
            Log.e(TAG, "Failed to open MediaCas session: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception in bindChannel: ${e.message}", e)
            return false
        }
    }

    data class CachedControlWord(
        val controlWord: ByteArray,
        val timestampMs: Long,
        val parity: Int
    )

    private val cwCache = java.util.concurrent.ConcurrentHashMap<Long, CachedControlWord>()
    private val cacheHits = java.util.concurrent.atomic.AtomicLong(0)
    private val cacheMisses = java.util.concurrent.atomic.AtomicLong(0)

    fun getCacheHitCount(): Long = cacheHits.get()
    fun getCacheMissCount(): Long = cacheMisses.get()
    fun clearCache() = cwCache.clear()

    private fun computeEcmCrc(data: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value
    }

    /**
     * Records resolved Control Word in cache for instantaneous duplicate hits.
     */
    fun recordResolvedCw(ecmData: ByteArray, cw: ByteArray, parity: Int) {
        val hash = computeEcmCrc(ecmData)
        cwCache[hash] = CachedControlWord(cw.clone(), System.currentTimeMillis(), parity)
    }

    /**
     * Forwards raw ECM packet from Tuner HAL to active MediaCas session.
     * Checks in-memory CW cache first to eliminate unnecessary network roundtrips.
     */
    fun processEcm(ecmData: ByteArray) {
        val session = activeSession
        if (session == null) {
            Log.w(TAG, "Cannot process ECM: No active MediaCas session")
            return
        }

        val ecmHash = computeEcmCrc(ecmData)
        val cached = cwCache[ecmHash]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.timestampMs < 9500)) {
            cacheHits.incrementAndGet()
            Log.d(TAG, "ECM Cache HIT (CRC 0x%08X) - Reusing resolved CW without network roundtrip".format(ecmHash))
            OscamNativeBridge.nativeSetSoftwareCw(0, cached.parity, cached.controlWord)
            return
        }

        cacheMisses.incrementAndGet()

        try {
            session.processEcm(ecmData)
            Log.d(TAG, "Delivered %d-byte ECM to MediaCas (Cache Miss)".format(ecmData.size))
        } catch (e: Exception) {
            Log.e(TAG, "Error delivering ECM to MediaCas: ${e.message}", e)
        }
    }

    /**
     * Inspects channel scrambling status.
     * Determines whether the channel requires CAS descrambling or is Free-To-Air (FTA).
     *
     * @param channelUri The channel URI from Android TV Input Framework.
     * @param knownChannels Configured list of channel entries.
     * @return true if channel is scrambled (requires bridge), false if FTA (clear broadcast).
     */
    fun isChannelScrambled(
        channelUri: android.net.Uri,
        knownChannels: List<OscamChannelEntry> = emptyList()
    ): Boolean {
        // 1. Check known channel database (by service ID or URI)
        val channelIdStr = channelUri.lastPathSegment
        val channelId = channelIdStr?.toLongOrNull() ?: -1L

        for (ch in knownChannels) {
            if (ch.serviceId.toLong() == channelId || ch.streamUrl == channelUri.toString()) {
                if (ch.caid == 0) {
                    Log.i(TAG, "Channel '${ch.name}' (SID ${ch.serviceId}) explicitly configured as FTA (CAID 0x0000). Bypass bridge.")
                    return false
                }
                return true
            }
        }

        // 2. Query Android TV ContentProvider (TvContract.Channels)
        try {
            val projection = arrayOf(
                android.media.tv.TvContract.Channels._ID,
                android.media.tv.TvContract.Channels.COLUMN_DISPLAY_NAME,
                android.media.tv.TvContract.Channels.COLUMN_SERVICE_TYPE,
                android.media.tv.TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
            )
            context.contentResolver.query(channelUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.media.tv.TvContract.Channels.COLUMN_DISPLAY_NAME)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else "Unknown"

                    val internalDataIdx = cursor.getColumnIndex(android.media.tv.TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                    if (internalDataIdx >= 0) {
                        val providerData = cursor.getBlob(internalDataIdx)
                        if (providerData != null && providerData.size >= 16) {
                            // If PMT section is stored in provider data, parse with nativeIsPmtScrambled
                            val scrambled = OscamNativeBridge.nativeIsPmtScrambled(providerData)
                            Log.i(TAG, "Channel '$name' PMT parsed: scrambled=$scrambled")
                            return scrambled
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "TvContract query for channel $channelUri: ${e.message}")
        }

        // Default: If no CAIDs found, assume not scrambled (FTA) to protect clear channels
        return false
    }

    /**
     * Binds broadcast channel only if it is scrambled.
     * For FTA channels, releases active CAS sessions and allows direct TV hardware playback.
     */
    fun bindChannelIfScrambled(
        channelUri: android.net.Uri,
        targetCaid: Int,
        knownChannels: List<OscamChannelEntry> = emptyList()
    ): Boolean {
        val scrambled = isChannelScrambled(channelUri, knownChannels)
        if (!scrambled) {
            Log.i(TAG, "FTA / Clear channel detected ($channelUri). Bypassing CAS bridge; direct TV hardware decoding engaged.")
            releaseChannel()
            return true
        }

        Log.i(TAG, "Scrambled channel detected ($channelUri). Engaging MediaCas bridge with CAID 0x%04X.".format(targetCaid))
        return bindChannel(targetCaid)
    }

    /**
     * Releases active CAS session when tuning to another channel.
     */
    fun releaseChannel() {
        try {
            activeSession?.close()
            activeSession = null
            mediaCasInstance?.close()
            mediaCasInstance = null
            Log.i(TAG, "Broadcast CAS session released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing CAS session: ${e.message}")
        }
    }
}

