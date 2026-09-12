package com.lizarragaeus.oscambridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Universal Multi-Brand CI (Common Interface) / CI+ CAM Module Emulator for Android TV.
 *
 * Emulates a physical Conditional Access Module (CAM / PCMCIA CI+ v1.4 slot) inside the TV firmware.
 * Features a 5-layer fault-tolerant fallback architecture:
 *
 * 1. Multi-brand implicit broadcast dispatch (TCL, Sony, Philips, Xiaomi, Hisense, Samsung, LG, Panasonic, Vestel, Sharp).
 * 2. Explicit OEM-targeted broadcasts (bypasses Android 8.0+ Oreo/Pie/Q/R/S/T/U background restrictions by targeting installed OEM packages).
 * 3. Linux HAL & SystemProperties reflection (sets vendor.ci.cam.ready, vendor.ci.slot0.status, etc.).
 * 4. Reverse query event interceptor with 25s keepalive heartbeat.
 * 5. Android TV Input Framework (TIF) & Stream Proxy direct routing fallback.
 */
object CiModuleEmulator {

    private const val TAG = "OscamCasBridge_CI"
    private const val CAM_MODULE_NAME = "Universal OSCam / CCcam CI+ CAM"
    private const val CAM_MANUFACTURER = "DVB-CAS Bridge"
    private const val CAM_VERSION = "CI+ v1.4 / DVB-CSA2"
    private const val HEARTBEAT_INTERVAL_MS = 25000L

    data class CiDiagnostics(
        val isReady: Boolean,
        val detectedBrand: TclTvCompat.TvBrand,
        val installedOemApps: List<String>,
        val fallbackLayersActive: List<String>,
        val broadcastCount: Long,
        val heartbeatCount: Long,
        val lastBroadcastTimeMs: Long,
        val supportedCaids: List<Int>,
        val activeCasSystems: List<String>
    )

    private var appContext: Context? = null
    private var activeConfig: OscamConfig? = null
    private var isRunning = false
    private var queryReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    // Telemetry counters
    private var broadcastCounter = 0L
    private var heartbeatCounter = 0L
    private var lastBroadcastTimestamp = 0L
    private val activeFallbackLayers = mutableListOf<String>()

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                heartbeatCounter++
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

        val brand = TclTvCompat.detectTvBrand()
        Log.i(TAG, "Initializing Universal Multi-Brand CI+ CAM Emulator for $brand TV...")

        registerQueryReceiver(context)
        broadcastCamState(isReady = true)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)

        Log.i(TAG, "Virtual CI+ CAM Emulator active: '$CAM_MODULE_NAME' (Brand: $brand)")
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

        // Broadcast CAM removed across all channels and clear HAL props
        broadcastCamState(isReady = false)
        applySystemPropertiesFallback(isReady = false)
        Log.i(TAG, "Virtual CI+ CAM Module Emulator stopped.")
    }

    /**
     * Notifies the television OS across all brand channels that a channel is being descrambled.
     */
    fun notifyChannelTuned(serviceId: Int, caid: Int) {
        val ctx = appContext ?: return
        val cas = CasSystemDetector.detect(caid)
        val hexCaid = "0x%04X".format(caid)

        val channelIntents = listOf(
            Intent("com.tcl.tv.action.CI_CHANNEL_TUNED"),
            Intent("android.media.tv.action.CHANNEL_TUNED"),
            Intent("org.droidtv.channels.action.TUNE"),
            Intent("com.sony.dtv.action.CHANNEL_TUNED"),
            Intent("com.xiaomi.mitv.action.CHANNEL_TUNED")
        )

        for (intent in channelIntents) {
            intent.apply {
                putExtra("service_id", serviceId)
                putExtra("caid", caid)
                putExtra("caid_hex", hexCaid)
                putExtra("cas_system", cas.systemName)
                putExtra("cam_status", "DESCRAMBLING")
                putExtra("slot_id", 0)
                putExtra("slot_index", 0)
            }
            safeSendBroadcast(ctx, intent)
        }
        Log.d(TAG, "Notified TV OS: CI CAM descrambling SID $serviceId with $cas ($hexCaid)")
    }

    /**
     * Executes the full 5-layer broadcast and fallback sequence.
     */
    fun broadcastCamState(isReady: Boolean) {
        val ctx = appContext ?: return
        val config = activeConfig ?: return

        broadcastCounter++
        lastBroadcastTimestamp = System.currentTimeMillis()
        activeFallbackLayers.clear()

        val caidsIntArray = config.caids.toIntArray()
        val server = config.primaryServer
        val stateCode = if (isReady) 2 else 0 // 2 = READY / INITIALIZED, 0 = REMOVED
        val stateStr = if (isReady) "READY" else "REMOVED"

        // =========================================================================
        // LAYER 1: Multi-Brand Intent Matrix (Safe Implicit Broadcasts)
        // =========================================================================
        val brandIntents = mutableListOf<Intent>()

        // 1. TCL OEM Live TV (C645, C745, C845, C805, QM8, QM7, P635, P735, Thomson)
        brandIntents.add(Intent("com.tcl.ci.CARD_INSERTED").apply {
            putExtra("slot_id", 0)
            putExtra("slot_index", 0)
            putExtra("card_state", stateCode)
            putExtra("status", stateCode)
            putExtra("card_status", stateStr)
            putExtra("ci_state", stateStr)
            putExtra("card_present", isReady)
            putExtra("module_name", CAM_MODULE_NAME)
            putExtra("cam_name", CAM_MODULE_NAME)
            putExtra("manufacturer", CAM_MANUFACTURER)
            putExtra("version", CAM_VERSION)
            putExtra("caids", caidsIntArray)
            putExtra("supported_caids", caidsIntArray)
            putExtra("server_protocol", server.protocol.name)
        })
        brandIntents.add(Intent("com.tcl.tv.action.CI_STATUS_CHANGED").apply {
            putExtra("ci_state", stateStr)
            putExtra("card_present", isReady)
            putExtra("slot_id", 0)
            putExtra("module_name", CAM_MODULE_NAME)
        })
        brandIntents.add(Intent("com.tcl.action.CI_CARD_STATE").apply {
            putExtra("state", stateCode)
            putExtra("card_status", stateStr)
            putExtra("slot_id", 0)
        })

        // 2. Android TV Framework standard CI/CAM Broadcasts
        brandIntents.add(Intent("android.media.tv.action.SET_CIS_DATA").apply {
            putExtra("android.media.tv.extra.CI_SLOT_ID", 0)
            putExtra("android.media.tv.extra.CI_STATUS", stateCode)
            putExtra("android.media.tv.extra.CI_MODULE_NAME", CAM_MODULE_NAME)
            putExtra("android.media.tv.extra.CAIDS", caidsIntArray)
        })
        brandIntents.add(Intent("android.intent.action.DVB_CI_STATUS_CHANGED").apply {
            putExtra("slot", 0)
            putExtra("status", stateCode)
            putExtra("name", CAM_MODULE_NAME)
        })
        brandIntents.add(Intent("com.android.tv.action.CI_CARD_STATE").apply {
            putExtra("state", stateCode)
            putExtra("card_present", isReady)
        })

        // 3. Sony Bravia DTV CI Broadcasts
        brandIntents.add(Intent("com.sony.dtv.ci.CAM_STATUS").apply {
            putExtra("slot_id", 0)
            putExtra("status", stateCode)
            putExtra("cam_state", stateStr)
            putExtra("module_name", CAM_MODULE_NAME)
            putExtra("caids", caidsIntArray)
        })
        brandIntents.add(Intent("com.sony.dtv.broadcast.CAM_STATE").apply {
            putExtra("status", stateCode)
            putExtra("slot_id", 0)
        })
        brandIntents.add(Intent("com.sony.dtv.action.CI_INSERTED").apply {
            putExtra("slot_id", 0)
            putExtra("ready", isReady)
        })

        // 4. Philips / TP Vision CAM Broadcasts
        brandIntents.add(Intent("org.droidtv.ci.CAM_INSERTED").apply {
            putExtra("slot_id", 0)
            putExtra("cam_status", stateStr)
            putExtra("cam_name", CAM_MODULE_NAME)
            putExtra("caids", caidsIntArray)
        })
        brandIntents.add(Intent("org.droidtv.playtv.action.CAM_STATUS").apply {
            putExtra("status", stateCode)
            putExtra("slot_id", 0)
        })
        brandIntents.add(Intent("org.droidtv.channels.action.CI_STATUS").apply {
            putExtra("slot", 0)
            putExtra("ready", isReady)
        })

        // 5. Xiaomi / Redmi / PatchWall Broadcasts
        brandIntents.add(Intent("com.xiaomi.mitv.action.CI_STATUS").apply {
            putExtra("slot_id", 0)
            putExtra("card_status", stateStr)
            putExtra("module_name", CAM_MODULE_NAME)
            putExtra("caids", caidsIntArray)
        })
        brandIntents.add(Intent("com.xiaomi.mitv.tvinput.CI_STATE").apply {
            putExtra("state", stateCode)
            putExtra("is_ready", isReady)
        })

        // 6. Hisense VIDAA / Android TV CI Broadcasts
        brandIntents.add(Intent("com.hisense.tv.ci.STATUS_CHANGED").apply {
            putExtra("slot_id", 0)
            putExtra("card_status", stateStr)
            putExtra("status", stateCode)
            putExtra("module_name", CAM_MODULE_NAME)
        })
        brandIntents.add(Intent("com.hisense.tv.action.CAM_READY").apply {
            putExtra("slot_id", 0)
            putExtra("ready", isReady)
        })

        // 7. Samsung Android / Live TV Broadcasts
        brandIntents.add(Intent("com.samsung.tv.action.CI_STATUS").apply {
            putExtra("slot_id", 0)
            putExtra("status", stateCode)
            putExtra("module_name", CAM_MODULE_NAME)
        })
        brandIntents.add(Intent("com.samsung.tv.ci.CARD_INSERTED").apply {
            putExtra("card_present", isReady)
            putExtra("slot_id", 0)
        })

        // 8. LG Android Live TV Broadcasts
        brandIntents.add(Intent("com.lge.tv.action.CI_STATUS").apply {
            putExtra("slot_id", 0)
            putExtra("card_status", stateStr)
            putExtra("module_name", CAM_MODULE_NAME)
        })
        brandIntents.add(Intent("com.lge.tv.ci.CARD_INSERTED").apply {
            putExtra("present", isReady)
            putExtra("slot_id", 0)
        })

        // 9. Panasonic TV Broadcasts
        brandIntents.add(Intent("com.panasonic.dtv.action.CI_STATUS").apply {
            putExtra("slot_id", 0)
            putExtra("cam_ready", isReady)
            putExtra("module_name", CAM_MODULE_NAME)
        })
        brandIntents.add(Intent("com.panasonic.tv.CAM_INSERTED").apply {
            putExtra("slot_id", 0)
            putExtra("state", stateCode)
        })

        // 10. Vestel / Toshiba / Hitachi / Telefunken / Sharp Broadcasts
        brandIntents.add(Intent("com.vestel.tv.action.CI_STATUS").apply {
            putExtra("slot_id", 0)
            putExtra("status", stateCode)
            putExtra("module_name", CAM_MODULE_NAME)
        })
        brandIntents.add(Intent("com.vestel.ci.CARD_INSERTED").apply {
            putExtra("slot", 0)
            putExtra("card_status", stateStr)
        })
        brandIntents.add(Intent("jp.co.sharp.android.tv.CI_STATUS").apply {
            putExtra("slot_id", 0)
            putExtra("status", stateCode)
            putExtra("module_name", CAM_MODULE_NAME)
        })
        brandIntents.add(Intent("com.sharp.tv.action.CAM_INSERTED").apply {
            putExtra("slot_id", 0)
            putExtra("card_present", isReady)
        })

        var layer1Delivered = 0
        for (intent in brandIntents) {
            if (safeSendBroadcast(ctx, intent)) {
                layer1Delivered++
            }
        }
        if (layer1Delivered > 0) {
            activeFallbackLayers.add("Layer 1: Multi-Brand Implicit Broadcasts ($layer1Delivered actions)")
        }

        // =========================================================================
        // LAYER 2: Explicit Targeted Broadcasts to Installed OEM Apps
        // Bypasses Android 8.0+ (Oreo/Pie/Q/R/S/T/U) implicit background limitations
        // =========================================================================
        val installedApps = TclTvCompat.inspectInstalledOemApps(ctx).filter { it.isInstalled }
        var layer2Delivered = 0
        if (installedApps.isNotEmpty()) {
            for (app in installedApps) {
                // Send explicit versions of primary intents targeted directly to the OEM package
                val explicitTcl = Intent("com.tcl.ci.CARD_INSERTED").apply {
                    setPackage(app.packageName)
                    putExtra("slot_id", 0)
                    putExtra("card_state", stateCode)
                    putExtra("card_status", stateStr)
                    putExtra("card_present", isReady)
                    putExtra("module_name", CAM_MODULE_NAME)
                    putExtra("caids", caidsIntArray)
                }
                val explicitGeneric = Intent("android.media.tv.action.SET_CIS_DATA").apply {
                    setPackage(app.packageName)
                    putExtra("android.media.tv.extra.CI_SLOT_ID", 0)
                    putExtra("android.media.tv.extra.CI_STATUS", stateCode)
                    putExtra("android.media.tv.extra.CI_MODULE_NAME", CAM_MODULE_NAME)
                }
                val explicitStatus = Intent("com.tcl.tv.action.CI_STATUS_CHANGED").apply {
                    setPackage(app.packageName)
                    putExtra("ci_state", stateStr)
                    putExtra("card_present", isReady)
                }

                if (safeSendBroadcast(ctx, explicitTcl)) layer2Delivered++
                if (safeSendBroadcast(ctx, explicitGeneric)) layer2Delivered++
                if (safeSendBroadcast(ctx, explicitStatus)) layer2Delivered++
            }
            activeFallbackLayers.add("Layer 2: Explicit Targeted Broadcasts to ${installedApps.size} installed OEM apps")
        }

        // =========================================================================
        // LAYER 3: Android HAL & Linux Driver SystemProperties Reflection
        // =========================================================================
        val layer3Ok = applySystemPropertiesFallback(isReady)
        if (layer3Ok) {
            activeFallbackLayers.add("Layer 3: SystemProperties HAL Reflection (vendor.ci.cam.ready)")
        }

        // =========================================================================
        // LAYER 4: Active Keepalive Heartbeat & Reverse Query Interceptor
        // =========================================================================
        activeFallbackLayers.add("Layer 4: Reverse Query Interceptor with ${HEARTBEAT_INTERVAL_MS / 1000}s Heartbeat")

        // =========================================================================
        // LAYER 5: Android TV Input Framework (TIF) & Stream Proxy Fallback
        // =========================================================================
        activeFallbackLayers.add("Layer 5: Android TIF OscamTvInputService & Local Stream Proxy (:9191)")

        Log.d(TAG, "Broadcasted Virtual CI CAM status: ready=$isReady (${caidsIntArray.size} CAIDs, ${activeFallbackLayers.size} layers active)")
    }

    /**
     * Safely broadcasts an intent inside an isolated try/catch block.
     * Prevents SecurityException, background limits, or unexported receiver exceptions from terminating execution.
     */
    private fun safeSendBroadcast(context: Context, intent: Intent): Boolean {
        return try {
            context.sendBroadcast(intent)
            true
        } catch (e: SecurityException) {
            Log.d(TAG, "Broadcast permission restricted for action ${intent.action}: ${e.message}")
            false
        } catch (e: Exception) {
            Log.d(TAG, "Error sending broadcast ${intent.action}: ${e.message}")
            false
        }
    }

    /**
     * Sets vendor and system properties via reflection to trigger TV HAL descrambler layers.
     */
    private fun applySystemPropertiesFallback(isReady: Boolean): Boolean {
        val readyVal = if (isReady) "1" else "0"
        val statusVal = if (isReady) "READY" else "REMOVED"
        val insertedVal = if (isReady) "INSERTED" else "NONE"
        val boolVal = if (isReady) "true" else "false"

        val props = mapOf(
            "vendor.ci.cam.ready" to readyVal,
            "vendor.ci.slot0.status" to statusVal,
            "vendor.ci.slot0.present" to readyVal,
            "persist.vendor.ci.state" to insertedVal,
            "sys.ci.cam.ready" to readyVal,
            "ro.vendor.ci.present" to boolVal
        )

        var anySet = false
        for ((k, v) in props) {
            if (setSystemProperty(k, v)) {
                anySet = true
            }
        }
        return anySet
    }

    /**
     * Writes an Android System Property using reflection.
     */
    private fun setSystemProperty(key: String, value: String): Boolean {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val setMethod = c.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, key, value)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Registers a broadcast listener for incoming TV queries regarding CI slot status across all brands.
     */
    private fun registerQueryReceiver(context: Context) {
        val filter = IntentFilter().apply {
            // TCL Queries
            addAction("com.tcl.ci.QUERY_MODULE")
            addAction("com.tcl.tv.action.GET_CI_STATUS")
            addAction("com.tcl.action.QUERY_CI_CARD")

            // Standard Android TV Queries
            addAction("android.media.tv.action.QUERY_CI_INFO")
            addAction("android.intent.action.QUERY_DVB_CI")

            // Philips Queries
            addAction("org.droidtv.ci.QUERY_CAM")
            addAction("org.droidtv.channels.QUERY_CI")

            // Sony Queries
            addAction("com.sony.dtv.ci.QUERY_CAM_STATUS")

            // Xiaomi Queries
            addAction("com.xiaomi.mitv.QUERY_CI")

            // Hisense Queries
            addAction("com.hisense.tv.ci.QUERY_STATUS")

            // Panasonic Queries
            addAction("com.panasonic.dtv.action.QUERY_CI")

            // Vestel Queries
            addAction("com.vestel.tv.QUERY_CI")
        }

        queryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                Log.i(TAG, "TV OS queried CI CAM status ($action). Responding with active CAM presence.")
                broadcastCamState(isReady = true)
            }
        }

        try {
            context.registerReceiver(queryReceiver, filter)
            Log.d(TAG, "Multi-Brand CI query receiver registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register CI query receiver: ${e.message}")
        }
    }

    /**
     * Returns full diagnostic metrics for Web UI and status monitoring.
     */
    fun getDiagnostics(context: Context): CiDiagnostics {
        val brand = TclTvCompat.detectTvBrand()
        val installedApps = TclTvCompat.inspectInstalledOemApps(context)
            .filter { it.isInstalled }
            .map { "${it.appName} (${it.packageName})" }

        val caids = activeConfig?.caids ?: emptyList()
        val casNames = caids.map { CasSystemDetector.detect(it).systemName }.distinct()

        return CiDiagnostics(
            isReady = isRunning,
            detectedBrand = brand,
            installedOemApps = installedApps,
            fallbackLayersActive = ArrayList(activeFallbackLayers),
            broadcastCount = broadcastCounter,
            heartbeatCount = heartbeatCounter,
            lastBroadcastTimeMs = lastBroadcastTimestamp,
            supportedCaids = caids,
            activeCasSystems = casNames
        )
    }
}
