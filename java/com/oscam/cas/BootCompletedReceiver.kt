package com.oscam.cas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for auto-starting the persistent OSCam CAS Bridge service on Android TV boot.
 *
 * Log Tag: OscamCasBridge
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OscamCasBridge"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "BootCompletedReceiver received action: $action")

        if (Intent.ACTION_BOOT_COMPLETED == action ||
            "android.intent.action.QUICKBOOT_POWERON" == action ||
            "com.htc.intent.action.QUICKBOOT_POWERON" == action) {

            val repo = OscamConfigRepository(context.applicationContext)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val config = repo.getCurrentConfig()
                    if (config.autoStartOnBoot) {
                        Log.i(TAG, "Auto-start enabled: Launching OscamCasBinderService...")
                        val serviceIntent = Intent(context, OscamCasBinderService::class.java).apply {
                            this.action = OscamCasBinderService.ACTION_START
                        }
                        context.startForegroundService(serviceIntent)
                    } else {
                        Log.i(TAG, "Auto-start is disabled in user configuration")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in BootCompletedReceiver: ${e.message}", e)
                }
            }
        }
    }
}
