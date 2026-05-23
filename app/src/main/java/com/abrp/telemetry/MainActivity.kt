package com.abrp.telemetry

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abrp.telemetry.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false
    private var isTokenLocked = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val carModels = listOf(
        "Sealion 7 Comfort RWD (82 kWh)" to "byd:sealion:25:82:rwd",
        "Sealion 7 Design AWD (82 kWh)"  to "byd:sealion:25:82:awd",
        "Sealion 7 Excellence AWD (91 kWh)" to "byd:sealion:25:91:awd"
    )

    private var vehicleManager: VehicleDataManager? = null
    private var locationManager: LocationManager? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            vehicleManager?.snapshot()?.let { updateVehicleUI(it) }
            val interval = if (vehicleManager?.isRecovering == true) 1_000L else 2_000L
            refreshHandler.postDelayed(this, interval)
        }
    }

    private val locationListener = LocationListener { loc -> updateGpsUI(loc) }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            intent.getStringExtra(TelemetryService.EXTRA_STATUS_MESSAGE)?.let { status ->
                binding.tvStatus.text = status
                if (status == "Telemetry stopped") {
                    isServiceRunning = false
                    updateStartStopButton()
                }
            }
            intent.getStringExtra(TelemetryService.EXTRA_LOG_MESSAGE)?.let { appendLog(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        setupCarModelDropdown()
        loadPreferences()
        setupListeners()
        isServiceRunning = getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE).getBoolean("telemetry_running", false)
        updateStartStopButton()
        requestRequiredPermissions()
    }

    override fun onStart() {
        super.onStart()
        connectVehicleDisplay()
        startGpsDisplay()
        refreshHandler.postDelayed(refreshRunnable, 2_000L)
        if (isServiceRunning) {
            ContextCompat.startForegroundService(this,
                Intent(this, TelemetryService::class.java).apply { action = TelemetryService.ACTION_START })
        }
    }

    override fun onStop() {
        super.onStop()
        refreshHandler.removeCallbacks(refreshRunnable)
        vehicleManager?.disconnect()
        vehicleManager = null
        locationManager?.removeUpdates(locationListener)
        locationManager = null
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TelemetryService.BROADCAST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun connectVehicleDisplay() {
        vehicleManager = VehicleDataManager(this).also { vm ->
            vm.onStateChanged = { state -> runOnUiThread { updateVehicleUI(state) } }
        }
        if (vehicleManager?.connect() == false) {
            binding.tvCarConnection.text = "Car API unavailable"
            binding.tvCarConnection.setTextColor(ContextCompat.getColor(this, R.color.btn_stop))
        }
    }

    private fun updateVehicleUI(state: VehicleDataManager.VehicleState) {
        binding.tvSoc.text = "${"%.1f".format(state.socPercent)}%"
        binding.tvSpeed.text = "${state.speedKmh.toInt()} km/h"
        binding.tvCharging.text = when {
            state.isDcfc -> "DC Fast Charge (${"%.1f".format(state.chargeRateKw)} kW)"
            state.isCharging -> "AC Charging (${"%.1f".format(state.chargeRateKw)} kW)"
            else -> "Not charging"
        }
        binding.tvTemp.text = state.outsideTempC?.let { "${"%.1f".format(it)}°C" } ?: "--"
        binding.tvOdometer.text = "${"%.0f".format(state.odometerKm)} km"
        binding.tvRange.text = if (state.rangeKm > 0) "${state.rangeKm} km" else "--"
        binding.tvCarConnection.text = if (state.connected) "Connected" else "Disconnected"
        binding.tvCarConnection.setTextColor(
            ContextCompat.getColor(this, if (state.connected) R.color.btn_start else R.color.btn_stop)
        )
    }

    private fun startGpsDisplay() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        // Show last known location immediately
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            locationManager?.getLastKnownLocation(p)?.let { updateGpsUI(it) }
        }
        // Request live updates
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            try {
                if (locationManager?.isProviderEnabled(p) == true) {
                    locationManager?.requestLocationUpdates(p, 2000L, 5f, locationListener, mainLooper)
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateGpsUI(loc: Location) {
        binding.tvGps.text = "${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE)
        val savedToken = prefs.getString("user_token", "")
        binding.etUserToken.setText(savedToken)
        val savedModel = prefs.getString("car_model", null)
        val entry = carModels.find { it.second == savedModel } ?: carModels[0]
        binding.actvCarModel.setText(entry.first, false)
        if (!savedToken.isNullOrBlank()) lockTokenInput()
    }

    private fun lockTokenInput() {
        isTokenLocked = true
        binding.tilUserToken.isEnabled = false
        binding.etUserToken.isEnabled = false
        binding.btnValidate.text = getString(R.string.edit_values)
    }

    private fun unlockTokenInput() {
        isTokenLocked = false
        binding.tilUserToken.isEnabled = true
        binding.etUserToken.isEnabled = true
        binding.btnValidate.text = getString(R.string.validate_and_save)
    }

    private fun savePreferences() {
        getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("user_token", binding.etUserToken.text.toString().trim())
            val selectedLabel = binding.actvCarModel.text.toString()
            val modelValue = carModels.find { it.first == selectedLabel }?.second ?: carModels[0].second
            putString("car_model", modelValue)
            apply()
        }
    }

    private fun setupCarModelDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, carModels.map { it.first })
        binding.actvCarModel.setAdapter(adapter)
    }

    private fun setupListeners() {
        binding.btnValidate.setOnClickListener { if (isTokenLocked) unlockTokenInput() else validateToken() }
        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning) stopTelemetryService() else startTelemetryService()
        }
        binding.btnClearLog.setOnClickListener { binding.tvLog.text = "" }
    }

    private fun validateToken() {
        val userToken = binding.etUserToken.text.toString().trim()
        binding.tilUserToken.error = null

        if (userToken.isBlank()) { binding.tilUserToken.error = "User token is required"; return }

        binding.btnValidate.isEnabled = false
        binding.btnValidate.text = getString(R.string.validating)
        binding.tvStatus.text = getString(R.string.validating_status)

        Thread {
            val result = ApiClient().validateToken(userToken, BuildConfig.ABRP_API_KEY)
            runOnUiThread {
                binding.btnValidate.isEnabled = true
                result.fold(
                    onSuccess = {
                        savePreferences()
                        lockTokenInput()
                        binding.tvStatus.text = getString(R.string.token_valid)
                        appendLog("[${timeFormat.format(Date())}] Token validated successfully")
                        Toast.makeText(this, R.string.token_valid, Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        binding.btnValidate.text = getString(R.string.validate_and_save)
                        binding.tvStatus.text = "Validation failed"
                        appendLog("[${timeFormat.format(Date())}] Validation failed: ${e.message}")
                        Toast.makeText(this, "Validation failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }.start()
    }

    private fun startTelemetryService() {
        if (binding.etUserToken.text.isNullOrBlank()) {
            Toast.makeText(this, R.string.set_token_first, Toast.LENGTH_SHORT).show()
            return
        }
        savePreferences()
        ContextCompat.startForegroundService(this,
            Intent(this, TelemetryService::class.java).apply { action = TelemetryService.ACTION_START })
        isServiceRunning = true
        updateStartStopButton()
        binding.tvStatus.text = getString(R.string.starting)
    }

    private fun stopTelemetryService() {
        startService(Intent(this, TelemetryService::class.java).apply { action = TelemetryService.ACTION_STOP })
        isServiceRunning = false
        updateStartStopButton()
    }

    private fun updateStartStopButton() {
        binding.btnStartStop.text = if (isServiceRunning) getString(R.string.stop_telemetry) else getString(R.string.start_telemetry)
        binding.btnStartStop.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isServiceRunning) R.color.btn_stop else R.color.btn_start)
        )
    }

    private fun appendLog(message: String) {
        val current = binding.tvLog.text.toString()
        val trimmed = current.lines().take(100).joinToString("\n")
        binding.tvLog.text = if (current.isBlank()) message else "$message\n$trimmed"
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.car.permission.CAR_VENDOR_EXTENSION"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }
}
