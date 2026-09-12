package com.lizarragaeus.oscambridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Universal CI (Common Interface) / CI+ CAM Module Emulator for Android TV.
 *
 * Emulates a physical Conditional Access Module (CAM / PCMCIA CI+ v1.4 slot)
 * inside the TV firmware. Broadcasts standard and OEM-specific CI lifecycle intents
 * so that native television broadcast applications (TCL Live TV, Sony Bravia Tuner,
 * Philips Play TV, Hisense, and Android TV Live Channels) detect an active, inserted,
 * and operational CI module with full multi-CAID descrambling capabilities.
 *
 * Supports OSCam (dvbapi), CCcam v2.3.0, and Newcamd v5.25 backends.
 */
object CiModuleEmulator {

    private const val TAG = "OscamCasBridge_CI"
    private const val CAM_MODULE_NAME = "Universal OSCam / CCcam CI+ CAM"
    private const val CAM_MANUFACTURER = "DVB-CAS Bridge"
    private const val CAM_VERSION = "CI+ v1.4 / DVB-CSA2"
    private const val HEARTBEAT_INTERVAL_MS = 25000L

    private var appContext: Context? = null
    private var activeConfig: OscamConfig? = null
    private var isRunning = false
    private var queryReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                broadcastCamState(isReady = true)
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * Starts the virtual CI module emulator and registers system query hooks.
     */
    fun start(context: Context, config: OscamConfig) {
        if (isRunning) {
            updateConfig(config)
            return
        }

        appContext = context.applicationContext
        activeConfig = config
        isRunning = true

        Log.i(TAG, "Initializing Virtual CI+ CAM Module Emulator for ${TclTvCompat.detectTvBrand()} TV...")

        registerQueryReceiver(context)
        broadcastCamState(isReady = true)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)

        Log.i(TAG, "Virtual CI+ CAM Module Emulator active and broadcasting: '$CAM_MODULE_NAME'")
    }

    /**
     * Updates active CAID matrix and card profile from configuration.
     */
    fun updateConfig(config: OscamConfig) {
        activeConfig = config
        if (isRunning) {
            broadcastCamState(isReady = true)
        }
    }

    /**
     * Stops the CI emulator and unregisters listeners.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(heartbeatRunnable)

        appContext?.let { ctx ->
            queryReceiver?.let { rx ->
                try {
                    ctx.unregisterReceiver(rx)
                } catch (ignored: Exception) {}
            }
        }
        queryReceiver = null

        // Broadcast CAM removed
        broadcastCamState(isReady = false)
        Log.i(TAG, "Virtual CI+ CAM Module Emulator stopped.")
    }

    /**
     * Notifies the television OS that a specific channel is being descrambled by the CI CAM.
     */
    fun notifyChannelTuned(serviceId: Int, caid: Int) {
        val ctx = appContext ?: return
        try {
            val intent = Intent("com.tcl.tv.action.CI_CHANNEL_TUNED").apply {
                putExtra("service_id", serviceId)
                putExtra("caid", caid)
                putExtra("caid_hex", "0x%04X".format(caid))
                putExtra("cam_status", "DESCRAMBLING")
                putExtra("slot_id", 0)
            }
            ctx.sendBroadcast(intent)
            Log.d(TAG, "Notified TV OS: CI CAM descrambling SID $serviceId with CAID 0x%04X".format(caid))
        } catch (e: Exception) {
            Log.d(TAG, "Error notifying channel tuned: ${e.message}")
        }
    }

    /**
     * Broadcasts CI CAM insertion and ready states across known TV manufacturer intent channels.
     */
    fun broadcastCamState(isReady: Boolean) {
        val ctx = appContext ?: return
        val config = activeConfig ?: return

        val caidsIntArray = config.caids.toIntArray()
        val server = config.primaryServer
        val stateCode = if (isReady) 2 else 0 // 2 = READY / INITIALIZED, 0 = REMOVED

        try {
            // 1. TCL OEM Live TV CI Broadcasts
            val tclIntent = Intent("com.tcl.ci.CARD_INSERTED").apply {
                putExtra("slot_id", 0)
                putExtra("slot_index", 0)
                putExtra("card_state", stateCode)
                putExtra("status", stateCode)
                putExtra("card_status", if (isReady) "READY" else "REMOVED")
                putExtra("ci_state", if (isReady) "READY" else "REMOVED")
                putExtra("card_present", isReady)
                putExtra("module_name", CAM_MODULE_NAME)
                putExtra("cam_name", CAM_MODULE_NAME)
                putExtra("manufacturer", CAM_MANUFACTURER)
                putExtra("version", CAM_VERSION)
                putExtra("caids", caidsIntArray)
                putExtra("supported_caids", caidsIntArray)
                putExtra("server_protocol", server.protocol.name)
            }
            ctx.sendBroadcast(tclIntent)

            val tclStatusIntent = Intent("com.tcl.tv.action.CI_STATUS_CHANGED").apply {
                putExtra("ci_state", if (isReady) "READY" else "REMOVED")
                putExtra("card_present", isReady)
                putExtra("slot_id", 0)
                putExtra("module_name", CAM_MODULE_NAME)
            }
            ctx.sendBroadcast(tclStatusIntent)

            val tclCardStateIntent = Intent("com.tcl.action.CI_CARD_STATE").apply {
                putExtra("state", stateCode)
                putExtra("card_status", if (isReady) "READY" else "REMOVED")
                putExtra("slot_id", 0)
            }
            ctx.sendBroadcast(tclCardStateIntent)

            // 2. Android TV Framework standard CI/CAM Broadcasts
            val androidTvIntent = Intent("android.media.tv.action.SET_CIS_DATA").apply {
                putExtra("android.media.tv.extra.CI_SLOT_ID", 0)
                putExtra("android.media.tv.extra.CI_STATUS", stateCode)
                putExtra("android.media.tv.extra.CI_MODULE_NAME", CAM_MODULE_NAME)
            }
            ctx.sendBroadcast(androidTvIntent)

            // 3. Philips / TP Vision CAM Broadcasts
            val philipsIntent = Intent("org.droidtv.ci.CAM_INSERTED").apply {
                putExtra("slot_id", 0)
                putExtra("cam_status", if (isReady) "READY" else "REMOVED")
                putExtra("cam_name", CAM_MODULE_NAME)
            }
            ctx.sendBroadcast(philipsIntent)

            // 4. Sony Bravia DTV CI Broadcasts
            val sonyIntent = Intent("com.sony.dtv.ci.CAM_STATUS").apply {
                putExtra("slot_id", 0)
                putExtra("status", stateCode)
                putExtra("module_name", CAM_MODULE_NAME)
            }
            ctx.sendBroadcast(sonyIntent)

            // 5. Hisense CI Broadcasts
            val hisenseIntent = Intent("com.hisense.tv.ci.STATUS_CHANGED").apply {
                putExtra("slot_id", 0)
                putExtra("card_status", if (isReady) "READY" else "REMOVED")
                putExtra("module_name", CAM_MODULE_NAME)
            }
            ctx.sendBroadcast(hisenseIntent)

            Log.d(TAG, "Broadcasted Virtual CI CAM status: ready=$isReady (${caidsIntArray.size} CAIDs)")
        } catch (e: Exception) {
            Log.w(TAG, "Error broadcasting CI CAM state: ${e.message}")
        }
    }

    /**
     * Registers a broadcast listener for incoming TV queries regarding CI slot status.
     */
    private fun registerQueryReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction("com.tcl.ci.QUERY_MODULE")
            addAction("com.tcl.tv.action.GET_CI_STATUS")
            addAction("com.tcl.action.QUERY_CI_CARD")
            addAction("android.media.tv.action.QUERY_CI_INFO")
            addAction("org.droidtv.ci.QUERY_CAM")
        }

        queryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                Log.i(TAG, "TV OS queried CI CAM status: $action. Replying with active CAM presence.")
                broadcastCamState(isReady = true)
            }
        }

        try {
            context.registerReceiver(queryReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register CI query receiver: ${e.message}")
        }
    }
}

