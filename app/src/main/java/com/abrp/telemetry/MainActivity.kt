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
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abrp.telemetry.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    // Two TextViews stacked on the right side of the action bar.
    private var tvInstalled: TextView? = null
    private var tvLatest: TextView? = null
    private var isServiceRunning = false
    private var isTokenLocked = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val carModels = listOf(
        "Sealion 7 Comfort RWD (82 kWh)" to "byd:sealion:25:82:rwd",
        "Sealion 7 Design AWD (82 kWh)"  to "byd:sealion:25:82:awd",
        "Sealion 7 Excellence AWD (91 kWh)" to "byd:sealion:25:91:awd"
    )

    // @Volatile because triggerUpdateCheck's worker thread reads this field
    // after a 3s sleep, and onStop() (main thread) can null it during that
    // window. Volatile gives us a well-defined happens-before so the worker
    // can never observe a stale non-null pointer to a disconnected manager.
    @Volatile private var vehicleManager: VehicleDataManager? = null
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
            when (intent.action) {
                Updater.ACTION_STATUS -> {
                    intent.getStringExtra(Updater.EXTRA_STATUS)?.let { tvLatest?.text = it }
                }
                else -> {
                    intent.getStringExtra(TelemetryService.EXTRA_STATUS_MESSAGE)?.let { binding.tvStatus.text = it }
                    intent.getStringExtra(TelemetryService.EXTRA_LOG_MESSAGE)?.let { appendLog(it) }
                    if (intent.hasExtra(TelemetryService.EXTRA_IS_RUNNING)) {
                        isServiceRunning = intent.getBooleanExtra(TelemetryService.EXTRA_IS_RUNNING, true)
                        updateStartStopButton()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupActionBarStatus()
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
        // No auto-check on launch — TelemetryService's periodic alarm tick
        // calls Updater.maybeUpdate() with the 24h cooldown gating it. The
        // user can still force a check via the toggle flip or the "Check for
        // updates now" button.
    }

    /**
     * Force an auto-update check on a background thread. Used only for explicit
     * user gestures (the toggle, the Check button). The 3 s sleep lets the
     * vehicle bind settle so the isParked snapshot is reliable.
     */
    private fun triggerUpdateCheck(force: Boolean = false) {
        val ctx = applicationContext
        Thread {
            try { Thread.sleep(3_000) } catch (_: InterruptedException) { return@Thread }
            // == true so null (activity backgrounded mid-sleep — vehicleManager
            // got nulled) AND a freshly-bound-but-not-yet-reporting manager
            // both fall through to "not parked". Conservative: don't install
            // when we can't confirm the vehicle is stationary.
            val parked = vehicleManager?.snapshot()?.isParked == true
            Updater.maybeUpdate(ctx, parked, force = force)
        }.start()
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
        val filter = IntentFilter().apply {
            addAction(TelemetryService.BROADCAST_ACTION)
            addAction(Updater.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        // Re-sync button with the actual service state — catches any "stopped" broadcast
        // that fired while the receiver was unregistered (activity paused).
        isServiceRunning = TelemetryService.isRunning
        updateStartStopButton()
        // Installed: always reflects the *currently running* APK, regardless of
        // what Updater last persisted. Latest Release: comes from prefs, or a
        // placeholder if we've never run a check on this install.
        tvInstalled?.text = "Installed: v${BuildConfig.VERSION_NAME}"
        tvLatest?.text = Updater.lastLatestLine(this)
            .ifBlank { "Latest Release: not checked yet" }
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
        // GPS bearing is 0 when stationary — treat that as unknown so we don't flash
        // "0° N" while parked.
        binding.tvHeading.text = if (loc.hasBearing() && loc.bearing != 0f) {
            "${loc.bearing.toInt()}° ${cardinal(loc.bearing)}"
        } else {
            "--"
        }
        binding.tvElevation.text = if (loc.hasAltitude()) {
            "${loc.altitude.toInt()} m"
        } else {
            "--"
        }
    }

    private fun cardinal(bearing: Float): String {
        // 16-point compass label for the heading row.
        val labels = arrayOf("N","NNE","NE","ENE","E","ESE","SE","SSE",
                             "S","SSW","SW","WSW","W","WNW","NW","NNW")
        val idx = ((bearing + 11.25f) % 360f / 22.5f).toInt() and 0xF
        return labels[idx]
    }

    private fun loadPreferences() {
        // resolve() re-hydrates prefs from the JSON backup if prefs is empty
        // (e.g. just reinstalled), so the call gives us all fields in one shot.
        val settings = SettingsStore.resolve(this)
        binding.etUserToken.setText(settings.userToken)
        val entry = carModels.find { it.second == settings.carModel } ?: carModels[0]
        binding.actvCarModel.setText(entry.first, false)
        if (settings.userToken.isNotBlank()) lockTokenInput()
        binding.swAutoUpdate.isChecked = settings.autoUpdate
        // Collapse Settings if the user is already configured; otherwise leave
        // it open so a fresh install lands with the form ready to fill in.
        setSettingsCollapsed(settings.userToken.isNotBlank())
    }

    /** Replace the action bar's contents with a horizontal row carrying both the
     *  app name (left) and the version/update status (right). The default title
     *  is suppressed since the custom view provides the app name itself. */
    private fun setupActionBarStatus() {
        val bar = supportActionBar ?: return
        val view = layoutInflater.inflate(R.layout.actionbar_update_status, null)
        bar.setCustomView(view, ActionBar.LayoutParams(
            ActionBar.LayoutParams.MATCH_PARENT,
            ActionBar.LayoutParams.MATCH_PARENT,
        ))
        bar.setDisplayShowTitleEnabled(false)
        bar.setDisplayShowCustomEnabled(true)
        tvInstalled = view.findViewById(R.id.tvInstalled)
        tvLatest = view.findViewById(R.id.tvLatest)
    }

    private fun setSettingsCollapsed(collapsed: Boolean) {
        binding.settingsContent.visibility = if (collapsed) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvSettingsChevron.text = if (collapsed) "▶" else "▼"
    }

    private fun lockTokenInput() {
        isTokenLocked = true
        binding.tilUserToken.isEnabled = false
        binding.etUserToken.isEnabled = false
        binding.tilCarModel.isEnabled = false
        binding.actvCarModel.isEnabled = false
        binding.btnValidate.text = getString(R.string.edit_values)
    }

    private fun unlockTokenInput() {
        isTokenLocked = false
        binding.tilUserToken.isEnabled = true
        binding.etUserToken.isEnabled = true
        binding.tilCarModel.isEnabled = true
        binding.actvCarModel.isEnabled = true
        binding.btnValidate.text = getString(R.string.validate_and_save)
    }

    private fun savePreferences() {
        val token = binding.etUserToken.text.toString().trim()
        val selectedLabel = binding.actvCarModel.text.toString()
        val modelValue = carModels.find { it.first == selectedLabel }?.second ?: carModels[0].second
        val autoUpdate = binding.swAutoUpdate.isChecked
        getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("user_token", token)
            putString("car_model", modelValue)
            putBoolean("auto_update", autoUpdate)
            apply()
        }
        // Also mirror to the JSON backup so a future reinstall can restore the fields.
        SettingsStore.write(SettingsStore.Settings(
            userToken = token,
            carModel = modelValue,
            autoUpdate = autoUpdate,
        ))
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
        binding.settingsHeader.setOnClickListener {
            setSettingsCollapsed(binding.settingsContent.visibility == android.view.View.VISIBLE)
        }
        binding.btnCheckUpdate.setOnClickListener { triggerUpdateCheck(force = true) }
        binding.swAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("auto_update", isChecked).apply()
            // Mirror to the JSON backup off the UI thread — SettingsStore.resolve
            // reads SharedPreferences AND possibly /data/local/tmp, and write()
            // touches /data/local/tmp; both are disk I/O and StrictMode-grade
            // ANR risk on a contended head-unit FS.
            val ctx = applicationContext
            Thread {
                val existing = SettingsStore.resolve(ctx)
                SettingsStore.write(existing.copy(autoUpdate = isChecked))
            }.start()
            // Flipping the toggle on counts as a manual trigger — bypass the
            // 24h cooldown so the check runs immediately.
            if (isChecked) triggerUpdateCheck(force = true)
        }
    }

    private fun validateToken() {
        val userToken = binding.etUserToken.text.toString().trim()
        binding.tilUserToken.error = null

        if (userToken.isBlank()) { binding.tilUserToken.error = "User token is required"; return }

        binding.btnValidate.isEnabled = false
        binding.btnValidate.text = getString(R.string.validating)
        binding.tvStatus.text = getString(R.string.validating_status)

        // Capture the SOC from the live snapshot on the main thread; the validation
        // HTTP call goes off-thread but we want the reading taken now, not later.
        val soc = vehicleManager?.snapshot()?.socPercent?.toDouble()

        Thread {
            val result = ApiClient().validateToken(userToken, BuildConfig.ABRP_API_KEY, soc)
            runOnUiThread {
                binding.btnValidate.isEnabled = true
                result.fold(
                    onSuccess = {
                        // savePreferences() mirrors to the JSON file in one step now.
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
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
    }

    // Permission grants come back asynchronously. onCreate already kicked off
    // startGpsDisplay() before the user tapped Allow, so it bailed out at the
    // permission check — re-run it here so we start receiving fixes immediately
    // without needing the user to background+resume the app.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        val locationGranted = permissions.zip(grantResults.toTypedArray())
            .any { (p, r) -> p == Manifest.permission.ACCESS_FINE_LOCATION && r == PackageManager.PERMISSION_GRANTED }
        if (locationGranted && locationManager == null) startGpsDisplay()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}
