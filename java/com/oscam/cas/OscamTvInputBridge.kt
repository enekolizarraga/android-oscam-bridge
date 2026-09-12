package com.oscam.cas

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

    /**
     * Forwards raw ECM packet from Tuner HAL to active MediaCas session.
     */
    fun processEcm(ecmData: ByteArray) {
        val session = activeSession
        if (session == null) {
            Log.w(TAG, "Cannot process ECM: No active MediaCas session")
            return
        }

        try {
            session.processEcm(ecmData)
            Log.d(TAG, "Delivered %d-byte ECM to MediaCas".format(ecmData.size))
        } catch (e: Exception) {
            Log.e(TAG, "Error delivering ECM to MediaCas: ${e.message}", e)
        }
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
