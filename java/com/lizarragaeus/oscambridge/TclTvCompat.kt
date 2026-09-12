package com.lizarragaeus.oscambridge

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
        SAMSUNG,
        LG,
        VESTEL,
        SHARP,
        GENERIC_ANDROID_TV
    }

    data class OemAppInfo(
        val packageName: String,
        val appName: String,
        val brand: TvBrand,
        val isInstalled: Boolean
    )

    // Known OEM Live TV broadcast player packages across all major Android TV manufacturers
    val KNOWN_OEM_PACKAGES = listOf(
        // TCL Packages (C645, C745, C845, C805, QM8, QM7, P635, P735, Thomson Android TV)
        Triple("com.tcl.tv", "TCL Live TV", TvBrand.TCL),
        Triple("com.tcl.live", "TCL Live Channels", TvBrand.TCL),
        Triple("com.tcl.ui.tuning", "TCL Channel Tuning UI", TvBrand.TCL),
        Triple("com.tcl.channel", "TCL Channel (FAST/DVB)", TvBrand.TCL),
        Triple("com.tcl.avitv", "TCL AV/Input TV Service", TvBrand.TCL),
        Triple("com.tcl.common.mediaplayer", "TCL Media Player", TvBrand.TCL),

        // Sony Bravia Packages
        Triple("com.sony.dtv.tvinput", "Sony Bravia TV Input", TvBrand.SONY),
        Triple("com.sony.dtv.broadcast", "Sony Broadcast Tuner", TvBrand.SONY),
        Triple("com.sony.dtv.bravialifeview", "Sony Bravia LifeView", TvBrand.SONY),
        Triple("com.sony.dtv.channellist", "Sony Channel List", TvBrand.SONY),

        // Philips / TP Vision Packages
        Triple("org.droidtv.channels", "Philips Channel Manager", TvBrand.PHILIPS),
        Triple("org.droidtv.playtv", "Philips Play TV", TvBrand.PHILIPS),
        Triple("org.droidtv.settings", "Philips TV Settings", TvBrand.PHILIPS),
        Triple("org.droidtv.tv", "Philips TV Service", TvBrand.PHILIPS),

        // Xiaomi / Redmi / Mi TV Packages
        Triple("com.xiaomi.mitv.tvinput", "Xiaomi PatchWall TV Input", TvBrand.XIAOMI),
        Triple("com.xiaomi.mitv.livetv", "Xiaomi Live TV", TvBrand.XIAOMI),
        Triple("com.mitv.tvinput", "Mi TV Input Manager", TvBrand.XIAOMI),

        // Hisense VIDAA / Android TV Packages
        Triple("com.hisense.tv.input", "Hisense Live TV Input", TvBrand.HISENSE),
        Triple("com.hisense.tv.channel", "Hisense Channel Manager", TvBrand.HISENSE),
        Triple("com.jamdeo.tv.livetv", "Hisense Jamdeo Live TV", TvBrand.HISENSE),

        // Samsung Android / Tizen-Bridge Packages
        Triple("com.samsung.tv.input", "Samsung TV Input", TvBrand.SAMSUNG),
        Triple("com.samsung.android.livetv", "Samsung Live TV", TvBrand.SAMSUNG),
        Triple("com.samsung.tv", "Samsung TV Service", TvBrand.SAMSUNG),

        // LG Android Companion / TV Packages
        Triple("com.lge.tv.input", "LG TV Input", TvBrand.LG),
        Triple("com.lge.livetv", "LG Live TV", TvBrand.LG),
        Triple("com.lge.tv", "LG TV Framework", TvBrand.LG),

        // Vestel / Toshiba / Hitachi / Telefunken / JVC Packages
        Triple("com.vestel.tv", "Vestel Live TV", TvBrand.VESTEL),
        Triple("com.vestel.live", "Vestel Channel Tuner", TvBrand.VESTEL),
        Triple("com.toshiba.tv", "Toshiba TV Tuner", TvBrand.VESTEL),
        Triple("com.toshiba.broadcast", "Toshiba Broadcast Manager", TvBrand.VESTEL),

        // Sharp Aquos Android TV Packages
        Triple("jp.co.sharp.android.tv", "Sharp Aquos Live TV", TvBrand.SHARP),
        Triple("com.sharp.tv.input", "Sharp TV Input", TvBrand.SHARP),
        Triple("com.sharp.broadcast", "Sharp Broadcast Service", TvBrand.SHARP),

        // Panasonic Packages
        Triple("com.panasonic.avc.dmp.tvinput", "Panasonic DTV Input", TvBrand.PANASONIC),
        Triple("com.panasonic.tv.broadcast", "Panasonic Broadcast Tuner", TvBrand.PANASONIC),
        Triple("com.panasonic.dtv", "Panasonic DTV Engine", TvBrand.PANASONIC),

        // Google / Android Open Source Reference
        Triple("com.google.android.tv", "Google Live Channels", TvBrand.GENERIC_ANDROID_TV),
        Triple("com.android.tv", "Android Open TV Framework", TvBrand.GENERIC_ANDROID_TV)
    )

    /**
     * Detects the manufacturer brand of the host television.
     */
    fun detectTvBrand(): TvBrand {
        val manufacturer = Build.MANUFACTURER.uppercase()
        val brand = Build.BRAND.uppercase()
        val product = Build.PRODUCT.uppercase()
        val sysBrand = getSystemProperty("ro.product.brand").uppercase()
        val sysManuf = getSystemProperty("ro.product.manufacturer").uppercase()

        return when {
            manufacturer.contains("TCL") || brand.contains("TCL") || sysBrand.contains("TCL") || sysManuf.contains("TCL") || brand.contains("THOMSON") -> TvBrand.TCL
            manufacturer.contains("SONY") || brand.contains("SONY") || sysBrand.contains("SONY") -> TvBrand.SONY
            manufacturer.contains("TPV") || manufacturer.contains("PHILIPS") || brand.contains("PHILIPS") || sysBrand.contains("PHILIPS") -> TvBrand.PHILIPS
            manufacturer.contains("XIAOMI") || brand.contains("XIAOMI") || brand.contains("REDMI") || brand.contains("POCO") -> TvBrand.XIAOMI
            manufacturer.contains("HISENSE") || brand.contains("HISENSE") || sysBrand.contains("HISENSE") -> TvBrand.HISENSE
            manufacturer.contains("PANASONIC") || brand.contains("PANASONIC") -> TvBrand.PANASONIC
            manufacturer.contains("SAMSUNG") || brand.contains("SAMSUNG") -> TvBrand.SAMSUNG
            manufacturer.contains("LG") || brand.contains("LG") || manufacturer.contains("LGE") -> TvBrand.LG
            manufacturer.contains("VESTEL") || brand.contains("VESTEL") || brand.contains("TOSHIBA") || manufacturer.contains("TOSHIBA") || brand.contains("HITACHI") || brand.contains("TELEFUNKEN") || brand.contains("JVC") -> TvBrand.VESTEL
            manufacturer.contains("SHARP") || brand.contains("SHARP") || product.contains("AQUOS") -> TvBrand.SHARP
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

    data class LastTuningInfo(
        var serviceId: Int = -1,
        var frequencyHz: Long = 0L,
        var uri: String = "",
        var timestamp: Long = 0L
    )

    var lastTuningEvent: LastTuningInfo = LastTuningInfo()

    /**
     * Registers a broadcast receiver listening for TCL-specific tuning and channel change actions.
     * Allows the bridge to intercept tuning events even when using the closed-source TCL Live TV app.
     */
    fun registerTclChannelListener(context: Context, onChannelChanged: (channelInfo: String) -> Unit): BroadcastReceiver? {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                Log.i(TAG, "TV Broadcast Intent intercepted: $action")

                val channelUri = intent.dataString ?: intent.getStringExtra("channel_uri") ?: ""
                val serviceId = intent.getIntExtra("service_id", -1)
                val frequency = intent.getLongExtra("frequency", 0L)

                lastTuningEvent = LastTuningInfo(
                    serviceId = serviceId,
                    frequencyHz = frequency,
                    uri = channelUri,
                    timestamp = System.currentTimeMillis()
                )

                val details = "Action=$action, URI=$channelUri, SID=$serviceId, Freq=$frequency"
                Log.i(TAG, "TV Channel change event: $details")
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
            "/dev/amstream_mpps",        // Amlogic MPPS descrambler (TCL C845, C805, QM8, S905X4/T962)
            "/dev/dvb0.ca0",             // Standard Linux DVB-CA node
            "/dev/dvb/adapter0/ca0",     // Standard Linux DVB Adapter 0 CA node
            "/dev/dvb0.ci0",             // Standard Linux DVB CI Slot node
            "/dev/dvb/adapter0/ci0",     // Standard Linux DVB Adapter 0 CI Slot node
            "/dev/ci0",                  // Common Interface hardware slot character device
            "/dev/dvb0.demux0",          // DVB Demux node
            "/dev/rtd_ca0",              // Realtek CA descrambler (TCL P635, P735, C725)
            "/dev/mtk_ca0"               // MediaTek CA descrambler (TCL Pentonic models)
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


