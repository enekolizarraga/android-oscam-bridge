package com.oscam.cas

import android.util.Log

/**
 * JNI wrapper for communicating with the native C++ library (liboscam_jni.so).
 * Manages the dvbapi client lifecycle, asynchronous event dispatching, and software descrambling.
 *
 * Log Tag: OscamCasBridge
 */
object OscamNativeBridge {
    private const val TAG = "OscamCasBridge"

    init {
        try {
            System.loadLibrary("oscam_jni")
            Log.i(TAG, "Native library liboscam_jni.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "ERROR: Could not load liboscam_jni.so: ${e.message}", e)
        }
    }

    enum class State(val value: Int) {
        DISCONNECTED(0),
        CONNECTING(1),
        CONNECTED(2),
        ERROR(3);

        companion object {
            fun fromInt(value: Int): State = values().firstOrNull { it.value == value } ?: DISCONNECTED
        }
    }

    data class BridgeStats(
        val ecmSentCount: Long = 0,
        val cwReceivedCount: Long = 0,
        val emmSentCount: Long = 0,
        val lastCwTimeMs: Long = 0,
        val reconnectCount: Long = 0
    )

    interface NativeCallback {
        fun onConnectionStateChanged(state: Int)
        fun onControlWordReceived(sessionHandle: Int, controlWord: ByteArray)
    }

    // Native methods implemented in com_oscam_cas_OscamCasPlugin.cpp
    external fun nativeInit(host: String, port: Int, caids: IntArray): Boolean
    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeGetStatus(): Int
    external fun nativeTestConnection(host: String, port: Int, timeoutMs: Int): Boolean
    external fun nativeGetStats(): LongArray
    external fun nativeGetLastError(): String
    external fun nativeRegisterCallback(callback: NativeCallback)
    external fun nativeUnregisterCallback()
    external fun nativeDescrambleBuffer(buffer: ByteArray, offset: Int, length: Int): Int
    external fun nativeSetSoftwareCw(pid: Int, parity: Int, cw: ByteArray)

    /**
     * Queries current strongly-typed state from the native engine.
     */
    fun getCurrentStatus(): State {
        return try {
            State.fromInt(nativeGetStatus())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query native state: ${e.message}", e)
            State.ERROR
        }
    }

    /**
     * Queries live statistics of resolved Control Words and processed packets.
     */
    fun getStats(): BridgeStats {
        return try {
            val raw = nativeGetStats()
            if (raw.size >= 5) {
                BridgeStats(
                    ecmSentCount = raw[0],
                    cwReceivedCount = raw[1],
                    emmSentCount = raw[2],
                    lastCwTimeMs = raw[3],
                    reconnectCount = raw[4]
                )
            } else {
                BridgeStats()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query native statistics: ${e.message}", e)
            BridgeStats()
        }
    }
}
