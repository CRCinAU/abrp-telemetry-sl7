package com.abrp.telemetry

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelemetryService : Service() {

    companion object {
        const val CHANNEL_ID = "abrp_telemetry_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.abrp.telemetry.START"
        const val ACTION_STOP = "com.abrp.telemetry.STOP"
        const val BROADCAST_ACTION = "com.abrp.telemetry.STATUS_UPDATE"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_IS_RUNNING = "is_running"
        const val INTERVAL_PARKED_MS  = 60_000L

        @Volatile var isRunning: Boolean = false
            private set
        const val INTERVAL_OTHER_MS   = 30_000L
        const val INTERVAL_DRIVING_MS = 10_000L
    }

    private lateinit var handler: Handler
    private val apiClient = ApiClient()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var running = false
    private var vehicleManager: VehicleDataManager? = null
    private var locationManager: LocationManager? = null
    @Volatile private var lastLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { lastLocation = location }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // Rescheduling is done inside sendTelemetry's background thread once the vehicle state is known.
    private val sendRunnable = Runnable { sendTelemetry() }

    private fun nextInterval(v: VehicleDataManager.VehicleState?): Long = when {
        v == null || v.isParked -> INTERVAL_PARKED_MS
        v.shiftMode == VehicleDataManager.GEAR_DRIVE -> INTERVAL_DRIVING_MS
        else -> INTERVAL_OTHER_MS
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            null -> start()  // sticky restart after system kill
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun start() {
        if (running) return
        running = true
        isRunning = true
        getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE).edit().putBoolean("telemetry_running", true).apply()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to vehicle…"))

        connectVehicleManager()

        startLocationUpdates()
        handler.post(sendRunnable)
        broadcast(statusMessage = "Telemetry started")
    }

    private fun stop() {
        running = false
        isRunning = false
        getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE).edit().putBoolean("telemetry_running", false).apply()
        handler.removeCallbacks(sendRunnable)
        cleanup()
        broadcast(statusMessage = "Telemetry stopped", isStopped = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun cleanup() {
        vehicleManager?.disconnect()
        vehicleManager = null
        locationManager?.removeUpdates(locationListener)
        locationManager = null
    }


    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            broadcast(logMessage = "WARNING: Location permission denied — GPS unavailable")
            return
        }
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                if (locationManager?.isProviderEnabled(provider) == true) {
                    locationManager?.requestLocationUpdates(provider, 2_000L, 5f, locationListener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                Log.w("TelemetryService", "Could not start $provider: ${e.message}")
            }
        }
    }

    private fun sendTelemetry() {
        val prefs = getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE)
        val userToken = prefs.getString("user_token", "") ?: ""
        val apiKey = BuildConfig.ABRP_API_KEY
        val carModel = prefs.getString("car_model", "")?.takeIf { it.isNotBlank() }

        if (userToken.isBlank() || apiKey.isBlank()) {
            broadcast(logMessage = "ERROR: Token or API key not configured")
            handler.postDelayed(sendRunnable, INTERVAL_PARKED_MS)
            return
        }

        val vm = vehicleManager

        Thread {
            var v = vm?.snapshot()
            var loc = lastLocation

            if ((v?.socPercent ?: 0f) == 0f && (loc?.latitude ?: 0.0) == 0.0 && (loc?.longitude ?: 0.0) == 0.0) {
                broadcast(logMessage = "[${timeFormat.format(Date())}] SOC and GPS both zero — retrying in 3s…")
                Thread.sleep(3_000L)
                v = vm?.snapshot()
                loc = lastLocation
                if ((v?.socPercent ?: 0f) == 0f && (loc?.latitude ?: 0.0) == 0.0 && (loc?.longitude ?: 0.0) == 0.0) {
                    broadcast(logMessage = "[${timeFormat.format(Date())}] Skipping send — no valid SOC or GPS data")
                    if (running) handler.postDelayed(sendRunnable, nextInterval(v))
                    return@Thread
                }
            }

            val data = TelemetryData(
                utc = System.currentTimeMillis() / 1000,
                soc = v?.socPercent?.toDouble() ?: 0.0,
                speed = v?.speedKmh?.toDouble() ?: ((loc?.speed ?: 0f) * 3.6).toDouble(),
                lat = loc?.latitude ?: 0.0,
                lon = loc?.longitude ?: 0.0,
                is_charging = if (v?.isCharging == true) 1 else 0,
                is_dcfc = if (v?.isDcfc == true) 1 else 0,
                is_parked = if (v?.isParked == true) 1 else 0,
                elevation = loc?.altitude?.takeIf { it != 0.0 },
                heading = loc?.bearing?.toDouble()?.takeIf { it != 0.0 },
                ext_temp = v?.outsideTempC?.toDouble(),
                power = v?.chargeRateKw?.toDouble()?.takeIf { it > 0.0 }?.let { if (v.isCharging) -it else it },
                est_battery_range = v?.rangeKm?.takeIf { it > 0 }?.toDouble(),
                car_model = carModel
            )

            val result = apiClient.sendTelemetry(userToken, apiKey, data)
            val time = timeFormat.format(Date())
            result.fold(
                onSuccess = {
                    val chg = when {
                        data.is_dcfc == 1 -> " ⚡DC"
                        data.is_charging == 1 -> " ⚡AC"
                        else -> ""
                    }
                    broadcast(
                        logMessage = "[$time] OK — soc=${"%.1f".format(data.soc)}%, spd=${data.speed.toInt()}km/h, lat=${"%.4f".format(data.lat)}, lon=${"%.4f".format(data.lon)}$chg",
                        statusMessage = intervalDescription(v)
                    )
                    updateNotification("Last sent: $time | SOC: ${"%.1f".format(data.soc)}%")
                },
                onFailure = { e ->
                    broadcast(
                        logMessage = "[$time] FAIL: ${e.message}",
                        statusMessage = intervalDescription(v)
                    )
                    updateNotification("Send error — check log")
                }
            )
            if (running) handler.postDelayed(sendRunnable, nextInterval(v))
        }.start()
    }

    private fun intervalDescription(v: VehicleDataManager.VehicleState?): String {
        val ms: Long; val reason: String
        when {
            v == null        -> { ms = INTERVAL_PARKED_MS;  reason = "no vehicle data" }
            v.isDcfc         -> { ms = INTERVAL_PARKED_MS;  reason = "DC fast charging" }
            v.isCharging     -> { ms = INTERVAL_PARKED_MS;  reason = "charging" }
            v.isParked       -> { ms = INTERVAL_PARKED_MS;  reason = "parked" }
            v.shiftMode == VehicleDataManager.GEAR_DRIVE
                             -> { ms = INTERVAL_DRIVING_MS; reason = "driving" }
            else             -> { ms = INTERVAL_OTHER_MS;   reason = "gear ${v.shiftMode}" }
        }
        return "Sending every ${ms / 1000}s — $reason"
    }

    private fun connectVehicleManager() {
        vehicleManager?.disconnect()
        vehicleManager = VehicleDataManager(this)
        if (!vehicleManager!!.connect()) {
            broadcast(logMessage = "WARNING: CarService unavailable — vehicle data will be missing")
        }
    }

    private fun broadcast(logMessage: String? = null, statusMessage: String? = null, isStopped: Boolean = false) {
        val intent = Intent(BROADCAST_ACTION)
        logMessage?.let { intent.putExtra(EXTRA_LOG_MESSAGE, it) }
        statusMessage?.let { intent.putExtra(EXTRA_STATUS_MESSAGE, it) }
        if (isStopped) intent.putExtra(EXTRA_IS_RUNNING, false)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ABRP Telemetry", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background telemetry transmission to ABRP"
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ABRP Telemetry")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
