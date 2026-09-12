package com.lizarragaeus.oscambridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.pow

/**
 * Foreground Service responsible for maintaining persistent dvbapi connectivity with OSCam server.
 *
 * Capabilities:
 *  - Runs in foreground with persistent Android TV notification to prevent OOM termination.
 *  - Automatic reconnection using exponential backoff.
 *  - Active network connectivity monitoring with ConnectivityManager.NetworkCallback.
 *  - Two-way communication with native C++ engine via OscamNativeBridge.
 *  - Embedded local Web configuration server (port 8080) for remote mobile/PC setup.
 *  - Embedded local Stream Descrambler server (port 9191) for external recordings and streams.
 *  - Binder interface for TV Settings Activity and Tuner framework.
 *
 * Log Tag: OscamCasBridge
 */
open class OscamCasBinderService : Service(), OscamNativeBridge.NativeCallback {

    companion object {
        private const val TAG = "OscamCasBridge"
        private const val NOTIFICATION_CHANNEL_ID = "oscam_cas_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.lizarragaeus.oscambridge.action.START"
        const val ACTION_STOP = "com.lizarragaeus.oscambridge.action.STOP"
        const val ACTION_RESTART = "com.lizarragaeus.oscambridge.action.RESTART"
        const val ACTION_RELOAD = "com.lizarragaeus.oscambridge.action.RELOAD"

        // Legacy action strings for backward compatibility
        const val LEGACY_ACTION_START = "com.oscam.cas.action.START"
        const val LEGACY_ACTION_STOP = "com.oscam.cas.action.STOP"
        const val LEGACY_ACTION_RESTART = "com.oscam.cas.action.RESTART"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var repository: OscamConfigRepository
    private lateinit var connectivityManager: ConnectivityManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private var webServer: OscamLocalConfigWebServer? = null
    private var streamServer: StreamDescramblerServer? = null

    // Observable states
    private val _connectionState = MutableStateFlow(OscamNativeBridge.State.DISCONNECTED)
    val connectionState: StateFlow<OscamNativeBridge.State> = _connectionState.asStateFlow()

    private val _bridgeStats = MutableStateFlow(OscamNativeBridge.BridgeStats())
    val bridgeStats: StateFlow<OscamNativeBridge.BridgeStats> = _bridgeStats.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var reconnectJob: Job? = null
    private var statsMonitorJob: Job? = null
    private var isServiceRunning = false
    private var isNetworkAvailable = false

    inner class LocalBinder : Binder() {
        fun getService(): OscamCasBinderService = this@OscamCasBinderService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OscamCasBinderService::onCreate initializing 24/7 background service...")

        repository = OscamConfigRepository(applicationContext)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Permanent WakeLock and WifiLock to prevent Android TV deep sleep / network throttling
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OscamCasBridge:ServiceWakeLock").apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize WakeLock: ${e.message}")
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wifiManager?.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "OscamCasBridge:WifiLock"
            )?.apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize WifiLock: ${e.message}")
        }

        acquireLocks()
        createNotificationChannel()
        registerNetworkCallback()
        OscamNativeBridge.nativeRegisterCallback(this)

        // Start embedded web configuration server (port 8080)
        startEmbeddedWebServer()

        // Start embedded stream descrambler proxy (port 9191)
        startStreamDescramblerServer()

        // Start Virtual CI+ CAM Module Emulator for television CI slot detection
        serviceScope.launch {
            try {
                val config = repository.getCurrentConfig()
                CiModuleEmulator.start(applicationContext, config)
            } catch (e: Exception) {
                Log.w(TAG, "Could not initialize CiModuleEmulator: ${e.message}")
            }
        }
    }

    private fun startEmbeddedWebServer() {
        try {
            webServer = OscamLocalConfigWebServer(
                context = applicationContext,
                repository = repository,
                onConfigUpdatedCallback = { newConfig ->
                    Log.i(TAG, "Configuration updated via Web UI: ${newConfig.serverHost}:${newConfig.serverPort}. Restarting bridge...")
                    CiModuleEmulator.updateConfig(newConfig)
                    restartBridge()
                },
                port = 8080
            )
            webServer?.start()
            val localIp = getLocalIpAddress()
            Log.i(TAG, "Web UI available at: http://$localIp:8080")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting embedded Web UI: ${e.message}", e)
        }
    }

    private fun startStreamDescramblerServer() {
        try {
            streamServer = StreamDescramblerServer(
                context = applicationContext,
                repository = repository,
                port = 9191
            ).apply { start() }
            val localIp = getLocalIpAddress()
            Log.i(TAG, "StreamDescramblerServer / Embedded TVHeadend ready at http://$localIp:9191")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting StreamDescramblerServer: ${e.message}", e)
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine local IP address: ${e.message}")
        }
        return "127.0.0.1"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "OscamCasBinderService::onStartCommand action=$action")

        val localIp = getLocalIpAddress()
        startForeground(NOTIFICATION_ID, buildNotification("OSCam/CCcam CI+ CAS Bridge Active | Web UI: http://$localIp:8080"))

        when (action) {
            ACTION_START, LEGACY_ACTION_START -> startBridge()
            ACTION_STOP, LEGACY_ACTION_STOP -> stopBridge()
            ACTION_RESTART, LEGACY_ACTION_RESTART, ACTION_RELOAD -> restartBridge()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "OscamCasSettingsActivity unbound from service. Keeping 24/7 background service & Web UI running.")
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        Log.i(TAG, "OscamCasSettingsActivity rebound to active background service.")
    }

    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
                Log.d(TAG, "Acquired permanent WakeLock for 24/7 background service")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire error: ${e.message}")
        }
        try {
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
                Log.d(TAG, "Acquired permanent WifiLock (HIGH_PERF) for 24/7 network connectivity")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock acquire error: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (ignored: Exception) {}
    }

    fun startBridge() {
        if (isServiceRunning) {
            Log.d(TAG, "Service is already running")
            return
        }

        isServiceRunning = true
        acquireLocks()
        val localIp = getLocalIpAddress()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to OSCam... Web UI: http://$localIp:8080"))

        serviceScope.launch {
            try {
                val config = repository.getCurrentConfig()
                val primary = config.primaryServer
                Log.i(TAG, "Starting OSCam bridge -> ${primary.host}:${primary.port} [Proto: ${primary.protocol.displayName}, Sys: ${config.deliverySystem}]")

                // Ensure CI+ CAM Module Emulator is broadcasting supported CAIDs to TV OS
                CiModuleEmulator.start(applicationContext, config)

                val caidIntArray = config.caids.toIntArray()
                val initOk = OscamNativeBridge.nativeInitEx(
                    primary.host,
                    primary.port,
                    primary.protocol.id,
                    primary.user,
                    primary.password,
                    primary.desKey,
                    caidIntArray
                )

                if (!initOk) {
                    val err = OscamNativeBridge.nativeGetLastError().ifEmpty { "Native initialization failed" }
                    _lastError.value = err
                    _connectionState.value = OscamNativeBridge.State.ERROR
                    updateNotification("Error: $err")
                    return@launch
                }

                _connectionState.value = OscamNativeBridge.State.CONNECTING
                updateNotification("Connecting [${primary.protocol.displayName}] to ${primary.host}:${primary.port} (http://$localIp:8080)")

                val startOk = OscamNativeBridge.nativeStart()
                if (!startOk) {
                    val err = OscamNativeBridge.nativeGetLastError().ifEmpty { "Failed to start OSCam client" }
                    _lastError.value = err
                    _connectionState.value = OscamNativeBridge.State.ERROR
                    updateNotification("Start failed: $err")
                    scheduleReconnect()
                } else {
                    startStatsMonitoring()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in startBridge: ${e.message}", e)
                _lastError.value = e.message
                _connectionState.value = OscamNativeBridge.State.ERROR
                scheduleReconnect()
            }
        }
    }

    fun stopBridge() {
        Log.i(TAG, "Stopping dvbapi bridge...")
        isServiceRunning = false
        reconnectJob?.cancel()
        statsMonitorJob?.cancel()
        CiModuleEmulator.stop()

        serviceScope.launch {
            OscamNativeBridge.nativeStop()
            _connectionState.value = OscamNativeBridge.State.DISCONNECTED
            updateNotification("Bridge stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        releaseLocks()
    }

    fun restartBridge() {
        serviceScope.launch {
            Log.i(TAG, "Restarting bridge with updated configuration...")
            OscamNativeBridge.nativeStop()
            delay(500)
            isServiceRunning = false
            startBridge()
        }
    }

    suspend fun testConnection(host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                OscamNativeBridge.nativeTestConnection(host, port, timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in testConnection: ${e.message}", e)
                false
            }
        }
    }

    suspend fun testConnectionEx(
        host: String,
        port: Int,
        protocol: Int = ServerProtocol.DVBAPI.id,
        user: String = "android_tv",
        password: String = "android_tv",
        desKey: String = "0102030405060708091011121314",
        timeoutMs: Int = 3000
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                OscamNativeBridge.nativeTestConnectionEx(host, port, protocol, user, password, desKey, timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in testConnectionEx: ${e.message}", e)
                "Error: ${e.message}"
            }
        }
    }

    suspend fun queryWebIfStatus(host: String, port: Int, user: String, pass: String): String {
        return withContext(Dispatchers.IO) {
            try {
                OscamNativeBridge.nativeQueryWebIfStatus(host, port, user, pass)
            } catch (e: Exception) {
                "WebIF Error: ${e.message}"
            }
        }
    }

    fun failoverNext(): Boolean {
        val ok = OscamNativeBridge.nativeFailoverNext()
        if (ok) {
            val desc = OscamNativeBridge.nativeGetActiveServerDescription()
            Log.i(TAG, "Failover activated: $desc")
            updateNotification("Active: $desc")
        }
        return ok
    }

    override fun onConnectionStateChanged(state: Int) {
        val newState = OscamNativeBridge.State.fromInt(state)
        Log.i(TAG, "Native callback: ConnectionState = $newState")
        _connectionState.value = newState

        val localIp = getLocalIpAddress()
        when (newState) {
            OscamNativeBridge.State.CONNECTED -> {
                reconnectJob?.cancel()
                updateNotification("Connected to OSCam (DVBAPI active) | http://$localIp:8080")
                wakeLock?.acquire(30 * 60 * 1000L)
            }
            OscamNativeBridge.State.CONNECTING -> {
                updateNotification("Connecting to OSCam... | http://$localIp:8080")
            }
            OscamNativeBridge.State.DISCONNECTED -> {
                updateNotification("Disconnected from OSCam | http://$localIp:8080")
                if (isServiceRunning && isNetworkAvailable) {
                    scheduleReconnect()
                }
            }
            OscamNativeBridge.State.ERROR -> {
                val err = OscamNativeBridge.nativeGetLastError()
                _lastError.value = err
                updateNotification("Connection error: $err | http://$localIp:8080")
                if (isServiceRunning && isNetworkAvailable) {
                    scheduleReconnect()
                }
            }
        }
    }

    override fun onControlWordReceived(sessionHandle: Int, controlWord: ByteArray) {
        Log.d(TAG, "CW received for session $sessionHandle (${controlWord.size} bytes)")
        _bridgeStats.value = OscamNativeBridge.getStats()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return

        reconnectJob = serviceScope.launch {
            val baseDelay = 1000L
            val maxDelay = 30000L
            var attempt = 1

            while (isActive && isServiceRunning && _connectionState.value != OscamNativeBridge.State.CONNECTED) {
                if (!isNetworkAvailable) {
                    Log.w(TAG, "Reconnect paused: No network connection available.")
                    delay(3000)
                    continue
                }

                val backoffMs = min(baseDelay * (2.0.pow(attempt - 1)).toLong(), maxDelay)
                Log.w(TAG, "Reconnect attempt #$attempt in ${backoffMs}ms...")
                updateNotification("Reconnecting in ${backoffMs / 1000}s (attempt #$attempt)...")

                delay(backoffMs)

                val config = repository.getCurrentConfig()
                val primary = config.primaryServer
                OscamNativeBridge.nativeInitEx(
                    primary.host,
                    primary.port,
                    primary.protocol.id,
                    primary.user,
                    primary.password,
                    primary.desKey,
                    config.caids.toIntArray()
                )
                val started = OscamNativeBridge.nativeStart()

                if (started) {
                    Log.i(TAG, "Reconnect succeeded on attempt #$attempt")
                    break
                }

                attempt++
            }
        }
    }

    private fun startStatsMonitoring() {
        statsMonitorJob?.cancel()
        statsMonitorJob = serviceScope.launch {
            while (isActive && isServiceRunning) {
                _bridgeStats.value = OscamNativeBridge.getStats()
                delay(2000)
            }
        }
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network connection detected")
                isNetworkAvailable = true
                if (isServiceRunning && _connectionState.value != OscamNativeBridge.State.CONNECTED) {
                    scheduleReconnect()
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network connection lost")
                isNetworkAvailable = false
            }
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "OSCam CAS Bridge Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains persistent dvbapi descrambling connection with OSCam server"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, OscamCasSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Android OSCam CAS Bridge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: Android task cleared from recents. Keeping 24/7 background service alive.")
        try {
            val restartServiceIntent = Intent(applicationContext, OscamCasBinderService::class.java).apply {
                action = ACTION_START
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmService?.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                restartPendingIntent
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not set restart alarm onTaskRemoved: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "OscamCasBinderService::onDestroy releasing resources")
        super.onDestroy()
        CiModuleEmulator.stop()
        serviceScope.cancel()
        webServer?.stop()
        streamServer?.stop()
        OscamNativeBridge.nativeUnregisterCallback()
        releaseLocks()
    }
}

