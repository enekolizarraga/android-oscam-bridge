package com.lizarragaeus.oscambridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android TV settings activity optimized for D-Pad directional navigation.
 * Allows configuring OSCam server parameters, broadcast delivery systems (DVB-S2/T2/C),
 * diagnostics, and live metric monitoring.
 *
 * Log Tag: OscamCasBridge
 */
open class OscamCasSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OscamCasBridge"
    }

    private lateinit var repository: OscamConfigRepository
    private var binderService: OscamCasBinderService? = null
    private var isBound = false

    // UI Views
    private lateinit var spDeliverySystem: Spinner
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etCaids: EditText
    private lateinit var cbAutostart: CheckBox
    private lateinit var btnTest: Button
    private lateinit var btnSaveConnect: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvErrorDetails: TextView
    private lateinit var tvStatsInfo: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvWebUrl: TextView

    private val deliveryOptions = listOf(
        "DVB-S / DVB-S2 / DVB-S2X (Satellite) [Default]",
        "DVB-T / DVB-T2 (Terrestrial)",
        "DVB-C / DVB-C2 (Cable)",
        "Hybrid / Multi-Tuner"
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to OscamCasBinderService")
            val binder = service as OscamCasBinderService.LocalBinder
            binderService = binder.getService()
            isBound = true
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Disconnected from OscamCasBinderService")
            binderService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oscam_settings)

        repository = OscamConfigRepository(applicationContext)

        initViews()
        setupDeliverySpinner()
        loadPersistedConfig()
        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, OscamCasBinderService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun initViews() {
        spDeliverySystem = findViewById(R.id.sp_delivery_system)
        etHost = findViewById(R.id.et_host)
        etPort = findViewById(R.id.et_port)
        etUser = findViewById(R.id.et_user)
        etCaids = findViewById(R.id.et_caids)
        cbAutostart = findViewById(R.id.cb_autostart)
        btnTest = findViewById(R.id.btn_test_connection)
        btnSaveConnect = findViewById(R.id.btn_save_connect)
        btnStop = findViewById(R.id.btn_stop_service)
        tvStatusBadge = findViewById(R.id.tv_status_badge)
        tvErrorDetails = findViewById(R.id.tv_error_details)
        tvStatsInfo = findViewById(R.id.tv_stats_info)
        progressBar = findViewById(R.id.progress_test)
        tvWebUrl = findViewById(R.id.tv_web_url)

        val localIp = getLocalIpAddress()
        tvWebUrl.text = "💡 Configure from mobile/PC: http://$localIp:8080"
    }

    private fun setupDeliverySpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deliveryOptions)
        spDeliverySystem.adapter = adapter

        spDeliverySystem.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedSystem = when (position) {
                    1 -> TunerDeliverySystem.DVBT
                    2 -> TunerDeliverySystem.DVBC
                    3 -> TunerDeliverySystem.HYBRID
                    else -> TunerDeliverySystem.DVBS
                }
                // Only suggest CAIDs if field is empty or matching default
                if (etCaids.text.isNullOrEmpty()) {
                    etCaids.setText(OscamConfig.defaultCaidsFor(selectedSystem).joinToString(", ") { "0x%04X".format(it) })
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getLocalIpAddress(): String {
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
            Log.w(TAG, "Could not determine local IP: ${e.message}")
        }
        return "127.0.0.1"
    }

    private fun loadPersistedConfig() {
        lifecycleScope.launch {
            repository.configFlow.collectLatest { config ->
                etHost.setText(config.serverHost)
                etPort.setText(config.serverPort.toString())
                etUser.setText(config.username)
                etCaids.setText(config.getCaidsCsv())
                cbAutostart.isChecked = config.autoStartOnBoot

                val spinnerIdx = when (config.deliverySystem) {
                    TunerDeliverySystem.DVBS -> 0
                    TunerDeliverySystem.DVBT -> 1
                    TunerDeliverySystem.DVBC -> 2
                    TunerDeliverySystem.HYBRID -> 3
                }
                spDeliverySystem.setSelection(spinnerIdx)
            }
        }
    }

    private fun setupListeners() {
        btnTest.setOnClickListener {
            testConnection()
        }

        btnSaveConnect.setOnClickListener {
            saveAndConnect()
        }

        btnStop.setOnClickListener {
            binderService?.stopBridge()
            Toast.makeText(this, "OSCam Bridge Service stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testConnection() {
        val host = etHost.text.toString().trim()
        val portStr = etPort.text.toString().trim()

        if (host.isEmpty()) {
            etHost.error = "Enter OSCam server IP"
            etHost.requestFocus()
            return
        }

        val port = portStr.toIntOrNull() ?: 9000

        progressBar.visibility = View.VISIBLE
        btnTest.isEnabled = false
        tvErrorDetails.visibility = View.GONE

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val ok = withContext(Dispatchers.IO) {
                binderService?.testConnection(host, port, 3000)
                    ?: OscamNativeBridge.nativeTestConnection(host, port, 3000)
            }
            val elapsed = System.currentTimeMillis() - startTime
            progressBar.visibility = View.GONE
            btnTest.isEnabled = true

            if (ok) {
                Toast.makeText(this@OscamCasSettingsActivity, "Connection successful! (${elapsed}ms)", Toast.LENGTH_SHORT).show()
                updateStatusView(OscamNativeBridge.State.CONNECTED, "Test OK (${elapsed}ms)")
            } else {
                val err = OscamNativeBridge.nativeGetLastError().ifEmpty { "Host unreachable or connection refused" }
                tvErrorDetails.text = "Diagnostic failure: $err"
                tvErrorDetails.visibility = View.VISIBLE
                Toast.makeText(this@OscamCasSettingsActivity, "Connection failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndConnect() {
        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 9000
        val user = etUser.text.toString().trim().ifEmpty { "android_tv" }
        val caidsCsv = etCaids.text.toString().trim()
        val autostart = cbAutostart.isChecked

        val selectedSystem = when (spDeliverySystem.selectedItemPosition) {
            1 -> TunerDeliverySystem.DVBT
            2 -> TunerDeliverySystem.DVBC
            3 -> TunerDeliverySystem.HYBRID
            else -> TunerDeliverySystem.DVBS
        }

        if (host.isEmpty()) {
            etHost.error = "IP required"
            return
        }

        val caidList = repository.parseCaidsCsv(caidsCsv)

        lifecycleScope.launch {
            val current = repository.getCurrentConfig()
            val existingPrimary = current.primaryServer
            val updatedPrimary = existingPrimary.copy(
                host = host,
                port = port,
                user = user
            )
            val updatedServers = current.servers.toMutableList()
            val primaryIdx = updatedServers.indexOfFirst { it.isPrimary }
            if (primaryIdx >= 0) {
                updatedServers[primaryIdx] = updatedPrimary
            } else if (updatedServers.isNotEmpty()) {
                updatedServers[0] = updatedPrimary
            } else {
                updatedServers.add(updatedPrimary)
            }

            val newConfig = current.copy(
                servers = updatedServers,
                deliverySystem = selectedSystem,
                caids = caidList,
                autoStartOnBoot = autostart
            )

            repository.saveConfig(newConfig)
            Toast.makeText(this@OscamCasSettingsActivity, "Configuration saved", Toast.LENGTH_SHORT).show()

            val serviceIntent = Intent(this@OscamCasSettingsActivity, OscamCasBinderService::class.java).apply {
                action = OscamCasBinderService.ACTION_RESTART
            }
            startForegroundService(serviceIntent)
        }
    }

    private fun observeServiceState() {
        val service = binderService ?: return

        lifecycleScope.launch {
            service.connectionState.collectLatest { state ->
                updateStatusView(state)
            }
        }

        lifecycleScope.launch {
            service.bridgeStats.collectLatest { stats ->
                tvStatsInfo.text = "Resolved CWs: ${stats.cwReceivedCount} | ECMs Sent: ${stats.ecmSentCount} | Reconnects: ${stats.reconnectCount}"
            }
        }

        lifecycleScope.launch {
            service.lastError.collectLatest { error ->
                if (!error.isNullOrEmpty()) {
                    tvErrorDetails.text = "Last error: $error"
                    tvErrorDetails.visibility = View.VISIBLE
                } else {
                    tvErrorDetails.visibility = View.GONE
                }
            }
        }
    }

    private fun updateStatusView(state: OscamNativeBridge.State, customText: String? = null) {
        when (state) {
            OscamNativeBridge.State.CONNECTED -> {
                tvStatusBadge.text = customText ?: "CONNECTED"
                tvStatusBadge.setBackgroundColor(Color.parseColor("#2E7D32"))
            }
            OscamNativeBridge.State.CONNECTING -> {
                tvStatusBadge.text = "CONNECTING..."
                tvStatusBadge.setBackgroundColor(Color.parseColor("#F57F17"))
            }
            OscamNativeBridge.State.ERROR -> {
                tvStatusBadge.text = "ERROR"
                tvStatusBadge.setBackgroundColor(Color.parseColor("#C62828"))
            }
            OscamNativeBridge.State.DISCONNECTED -> {
                tvStatusBadge.text = "DISCONNECTED"
                tvStatusBadge.setBackgroundColor(Color.parseColor("#455A64"))
            }
        }
    }
}

