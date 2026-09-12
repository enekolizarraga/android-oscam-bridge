package com.oscam.cas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Specialized compatibility and deep integration layer for OEM television playback applications,
 * with first-class optimization for TCL televisions (C645, C745, C845, C805, QM8, QM7, P635, P735).
 *
 * Provides detection of manufacturer Live TV apps, broadcast intent hooks, and hardware device node validation.
 */
object TclTvCompat {

    private const val TAG = "OscamCasBridge_TCL"

    // Supported TV Brands
    enum class TvBrand {
        TCL,
        SONY,
        PHILIPS,
        XIAOMI,
        HISENSE,
        PANASONIC,
        GENERIC_ANDROID_TV
    }

    data class OemAppInfo(
        val packageName: String,
        val appName: String,
        val brand: TvBrand,
        val isInstalled: Boolean
    )

    // Known OEM Live TV broadcast player packages
    private val KNOWN_OEM_PACKAGES = listOf(
        // TCL Packages
        Triple("com.tcl.tv", "TCL Live TV", TvBrand.TCL),
        Triple("com.tcl.live", "TCL Live Channels", TvBrand.TCL),
        Triple("com.tcl.ui.tuning", "TCL Channel Tuning UI", TvBrand.TCL),
        Triple("com.tcl.channel", "TCL Channel (FAST/DVB)", TvBrand.TCL),
        Triple("com.tcl.avitv", "TCL AV/Input TV Service", TvBrand.TCL),
        Triple("com.tcl.common.mediaplayer", "TCL Media Player", TvBrand.TCL),

        // Sony Bravia Packages
        Triple("com.sony.dtv.tvinput", "Sony Bravia TV Input", TvBrand.SONY),
        Triple("com.sony.dtv.broadcast", "Sony Broadcast Tuner", TvBrand.SONY),

        // Philips / TP Vision Packages
        Triple("org.droidtv.channels", "Philips Channel Manager", TvBrand.PHILIPS),
        Triple("org.droidtv.playtv", "Philips Play TV", TvBrand.PHILIPS),

        // Xiaomi / Mi TV Packages
        Triple("com.xiaomi.mitv.tvinput", "Xiaomi PatchWall TV Input", TvBrand.XIAOMI),
        Triple("com.xiaomi.mitv.livetv", "Xiaomi Live TV", TvBrand.XIAOMI),

        // Hisense Packages
        Triple("com.hisense.tv.input", "Hisense Live TV Input", TvBrand.HISENSE),

        // Google / Android Open Source Reference
        Triple("com.google.android.tv", "Google Live Channels", TvBrand.GENERIC_ANDROID_TV)
    )

    /**
     * Detects the manufacturer brand of the host television.
     */
    fun detectTvBrand(): TvBrand {
        val manufacturer = Build.MANUFACTURER.uppercase()
        val brand = Build.BRAND.uppercase()

        return when {
            manufacturer.contains("TCL") || brand.contains("TCL") || getSystemProperty("ro.product.brand").contains("tcl", ignoreCase = true) -> TvBrand.TCL
            manufacturer.contains("SONY") || brand.contains("SONY") -> TvBrand.SONY
            manufacturer.contains("TPV") || manufacturer.contains("PHILIPS") || brand.contains("PHILIPS") -> TvBrand.PHILIPS
            manufacturer.contains("XIAOMI") || brand.contains("XIAOMI") || brand.contains("REDMI") -> TvBrand.XIAOMI
            manufacturer.contains("HISENSE") || brand.contains("HISENSE") -> TvBrand.HISENSE
            manufacturer.contains("PANASONIC") || brand.contains("PANASONIC") -> TvBrand.PANASONIC
            else -> TvBrand.GENERIC_ANDROID_TV
        }
    }

    /**
     * Extracts specific TCL TV model information (e.g. C845, C805, C745, QM8).
     */
    fun getTclModelDetails(): String {
        val model = Build.MODEL
        val product = Build.PRODUCT
        val tclModel = getSystemProperty("ro.tcl.model")
        val board = getSystemProperty("ro.board.platform")

        return buildString {
            append("Model: ").append(if (tclModel.isNotBlank()) tclModel else model)
            append(" (Product: ").append(product)
            if (board.isNotBlank()) {
                append(", SoC: ").append(board)
            }
            append(")")
        }
    }

    /**
     * Inspects which OEM Live TV broadcast packages are installed on the device.
     */
    fun inspectInstalledOemApps(context: Context): List<OemAppInfo> {
        val pm = context.packageManager
        val results = mutableListOf<OemAppInfo>()

        for ((pkg, name, brand) in KNOWN_OEM_PACKAGES) {
            val installed = try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            results.add(OemAppInfo(pkg, name, brand, installed))
        }
        return results
    }

    /**
     * Registers a broadcast receiver listening for TCL-specific tuning and channel change actions.
     * Allows the bridge to intercept tuning events even when using the closed-source TCL Live TV app.
     */
    fun registerTclChannelListener(context: Context, onChannelChanged: (channelInfo: String) -> Unit): BroadcastReceiver? {
        if (detectTvBrand() != TvBrand.TCL) {
            return null
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                Log.i(TAG, "TCL Broadcast Intent intercepted: $action")

                val channelUri = intent.dataString ?: intent.getStringExtra("channel_uri") ?: ""
                val serviceId = intent.getIntExtra("service_id", -1)
                val frequency = intent.getLongExtra("frequency", 0L)

                val details = "Action=$action, URI=$channelUri, SID=$serviceId, Freq=$frequency"
                Log.i(TAG, "TCL Live TV Channel change: $details")
                onChannelChanged(details)
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.tcl.tv.action.CHANNEL_CHANGED")
            addAction("com.tcl.action.DVB_SERVICE_CHANGED")
            addAction("com.tcl.tv.action.TUNING_STARTED")
            addAction("com.tcl.tv.action.TUNING_COMPLETED")
            addAction("android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED")
        }

        try {
            context.registerReceiver(receiver, filter)
            Log.i(TAG, "TCL OEM channel broadcast listener registered successfully")
            return receiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register TCL broadcast receiver: ${e.message}")
            return null
        }
    }

    /**
     * Verifies that hardware descrambler character device nodes exist for TCL chipsets.
     */
    fun checkTclHardwareNodes(): Map<String, Boolean> {
        val nodes = listOf(
            "/dev/amstream_mpps", // Amlogic MPPS descrambler (TCL C845, C805, QM8, S905X4/T962)
            "/dev/dvb0.ca0",      // Standard Linux DVB-CA node
            "/dev/dvb0.demux0",   // DVB Demux node
            "/dev/rtd_ca0",       // Realtek CA descrambler (TCL P635, P735, C725)
            "/dev/mtk_ca0"        // MediaTek CA descrambler (TCL Pentonic models)
        )

        val statusMap = mutableMapOf<String, Boolean>()
        for (path in nodes) {
            val f = File(path)
            statusMap[path] = f.exists() && f.canRead()
        }
        return statusMap
    }

    /**
     * Reads an Android system property using reflection.
     */
    private fun getSystemProperty(key: String): String {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

