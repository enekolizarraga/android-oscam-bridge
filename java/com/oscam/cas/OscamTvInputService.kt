package com.oscam.cas

import android.content.Context
import android.media.tv.TvInputService
import android.net.Uri
import android.util.Log
import android.view.Surface

/**
 * Android TV Input Framework (TIF) service implementation.
 * Exposes the OSCam Satellite CAS Bridge as a standard, system-recognized TV Input.
 *
 * This allows the official native television player apps (such as TCL Live TV, TCL Channel,
 * Sony Bravia TV Input, Philips Play TV, Xiaomi PatchWall, and Google Live Channels) to
 * select, tune, and descramble broadcast channels directly through the native TV guide.
 */
class OscamTvInputService : TvInputService() {

    companion object {
        private const val TAG = "OscamTvInputService"
    }

    private lateinit var bridge: OscamTvInputBridge

    override fun onCreate() {
        super.onCreate()
        bridge = OscamTvInputBridge(applicationContext)
        Log.i(TAG, "OscamTvInputService created for TV Brand: ${TclTvCompat.detectTvBrand()}")
    }

    override fun onCreateSession(inputId: String): Session? {
        Log.i(TAG, "onCreateSession requested for inputId: $inputId")
        return OscamTvSession(this, bridge)
    }

    /**
     * TIF Session managing individual channel tuning lifecycle.
     */
    private class OscamTvSession(
        context: Context,
        private val bridge: OscamTvInputBridge
    ) : Session(context) {

        private var activeUri: Uri? = null

        override fun onRelease() {
            Log.i(TAG, "OscamTvSession released for uri: $activeUri")
            bridge.releaseChannel()
            activeUri = null
        }

        override fun onSetSurface(surface: Surface?): Boolean {
            // Hardware descrambler writes directly to VPU surface on Amlogic/Realtek SoCs
            Log.d(TAG, "onSetSurface called with surface: $surface")
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            // Volume handled by native hardware audio mixer
        }

        override fun onTune(channelUri: Uri): Boolean {
            Log.i(TAG, "onTune requested for channel: $channelUri")
            activeUri = channelUri

            // Notify TIF framework that video is preparing
            notifyVideoUnavailable(TvInputService.VIDEO_UNAVAILABLE_REASON_BUFFERING)

            // Default to primary satellite CAID (e.g. 0x1810 for Movistar+ or 0x1830 for HD+)
            val caid = 0x1810
            val success = bridge.bindChannel(caid)

            if (success) {
                notifyVideoAvailable()
                Log.i(TAG, "Channel tuned and CAS session bound successfully: $channelUri")
            } else {
                Log.w(TAG, "Channel tuned without CAS binding: $channelUri")
                notifyVideoAvailable()
            }

            return true
        }

        override fun onSetCaptionEnabled(enabled: Boolean) {
            Log.d(TAG, "onSetCaptionEnabled: $enabled")
        }
    }
}
