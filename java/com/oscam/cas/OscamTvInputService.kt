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

        private val repository = OscamConfigRepository(context)
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

            val config = repository.getCurrentConfig()
            val knownChannels = config.channels
            val primaryCaid = config.caids.firstOrNull() ?: 0x1810

            // Intelligent FTA vs Scrambled channel detection:
            // If the channel is Free-To-Air (unencrypted), DO NOT engage the bridge or descrambler.
            // The TV hardware demux & VPU play the clear stream natively.
            val isScrambled = bridge.isChannelScrambled(channelUri, knownChannels)
            if (!isScrambled) {
                Log.i(TAG, "Channel is FTA (Free-To-Air / en abierto). Bypassing CAS bridge: TV plays clear stream natively: $channelUri")
                bridge.releaseChannel()
                notifyVideoAvailable()
                return true
            }

            // Scrambled channel: engage MediaCas bridge and hardware descrambler
            Log.i(TAG, "Channel is SCRAMBLED. Engaging OSCam bridge with CAID 0x%04X: $channelUri".format(primaryCaid))
            val success = bridge.bindChannel(primaryCaid)

            if (success) {
                notifyVideoAvailable()
                Log.i(TAG, "Scrambled channel tuned and CAS session bound successfully: $channelUri")
            } else {
                Log.w(TAG, "Scrambled channel tuned without CAS binding: $channelUri")
                notifyVideoAvailable()
            }

            return true
        }

        override fun onSetCaptionEnabled(enabled: Boolean) {
            Log.d(TAG, "onSetCaptionEnabled: $enabled")
        }
    }
}

