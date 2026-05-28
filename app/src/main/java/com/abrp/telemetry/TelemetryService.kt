package com.abrp.telemetry

import android.Manifest
import android.app.AlarmManager
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelemetryService : Service() {

    companion object {
        // Service control (referenced by MainActivity + WakeReceiver)
        const val ACTION_START = "com.abrp.telemetry.START"
        const val ACTION_STOP  = "com.abrp.telemetry.STOP"

        // Status broadcast (consumed by MainActivity)
        const val BROADCAST_ACTION     = "com.abrp.telemetry.STATUS_UPDATE"
        const val EXTRA_LOG_MESSAGE    = "log_message"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_IS_RUNNING     = "is_running"

        // Adaptive send cadence
        const val INTERVAL_DRIVING_MS = 10_000L
        const val INTERVAL_OTHER_MS   = 30_000L
        const val INTERVAL_PARKED_MS  = 60_000L

        // Internal tuning
        private const val POLL_INTERVAL_MS   = 2_000L
        private const val MIN_SEND_GAP_MS    = 3_000L
        private const val ALARM_REQUEST_CODE = 1
        private const val CHANNEL_ID         = "abrp_telemetry_channel"
        private const val NOTIFICATION_ID    = 1001
        private const val PREFS_NAME         = "abrp_prefs"
        private const val PREF_RUNNING       = "telemetry_running"
        private const val TAG                = "TelemetryService"

        @Volatile var isRunning: Boolean = false
            private set
    }

    /** Booleans that change the send cadence or that ABRP cares about immediately. */
    private data class CadenceKey(
        val parked: Boolean,
        val drive: Boolean,
        val charging: Boolean,
        val dcfc: Boolean,
    ) {
        fun describe(): String = when {
            dcfc -> "DCFC"
            charging -> "charging"
            drive -> "driving"
            parked -> "parked"
            else -> "other"
        }
        companion object {
            fun of(v: VehicleDataManager.VehicleState) = CadenceKey(
                parked   = v.isParked,
                drive    = v.shiftMode == VehicleDataManager.GEAR_DRIVE,
                charging = v.isCharging,
                dcfc     = v.isDcfc,
            )
        }
    }

    private val api = ApiClient()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var running = false
    private var vehicleManager: VehicleDataManager? = null
    private var locationManager: LocationManager? = null
    @Volatile private var lastLocation: Location? = null

    private lateinit var pollHandler: Handler
    @Volatile private var lastCadenceKey: CadenceKey? = null
    @Volatile private var lastSendStartedMs: Long = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { lastLocation = location }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ────────────────────────────── Lifecycle ──────────────────────────────

    override fun onCreate() {
        super.onCreate()
        DebugLog.log("Service", "onCreate pid=${android.os.Process.myPid()}")
        pollHandler = Handler(Looper.getMainLooper())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.log("Service", "onStartCommand action=${intent?.action ?: "<null sticky>"} flags=$flags running=$running")
        // ACTION_STOP stops; anything else (ACTION_START, null on sticky restart,
        // alarm-triggered wakes) starts/refreshes.
        if (intent?.action == ACTION_STOP) stop() else start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DebugLog.log("Service", "onDestroy running=$running")
        super.onDestroy()
        cleanup()
    }

    // ────────────────────────────── Start / Stop ──────────────────────────────

    private fun start() {
        if (!running) {
            DebugLog.log("Service", "start: fresh setup (process started or was stopped)")
            running = true
            isRunning = true
            persistRunning(true)
            connectVehicle()
            startLocationUpdates()
            startStatePoller()
            broadcast(statusMessage = "Telemetry started")
            // Deploy / refresh the shell-user resurrector so we come back from
            // BYD's ignition-off forceStopPackage. Off-thread because dadb does I/O.
            val ctx = applicationContext
            Thread { DaemonLauncher.installAndStart(ctx) }.start()
        } else if (vehicleManager == null) {
            DebugLog.log("Service", "start: re-establishing vehicle manager (running but manager was torn down)")
            connectVehicle()
            startLocationUpdates()
        } else {
            DebugLog.log("Service", "start: already running — kicking a send")
        }
        startForeground(NOTIFICATION_ID, buildNotification("Telemetry active"))
        sendTelemetry()
    }

    private fun stop() {
        DebugLog.log("Service", "stop: user-requested shutdown")
        running = false
        isRunning = false
        persistRunning(false)
        stopStatePoller()
        cancelScheduledSend()
        cleanup()
        // Tear down the resurrector daemon so a Stop is a *full* stop with no
        // leftover process or files in /data/local/tmp.
        val ctx = applicationContext
        Thread { DaemonLauncher.uninstall(ctx) }.start()
        broadcast(statusMessage = "Telemetry stopped", isStopped = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    private fun cleanup() {
        vehicleManager?.disconnect()
        vehicleManager = null
        locationManager?.removeUpdates(locationListener)
        locationManager = null
    }

    // ────────────────────────────── Telemetry send ──────────────────────────────

    private fun sendTelemetry() {
        DebugLog.log("Send", "triggered")
        val userToken = TokenStore.resolve(this)
        val apiKey = BuildConfig.ABRP_API_KEY
        val carModel = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("car_model", "")?.takeIf { it.isNotBlank() }

        if (userToken.isBlank() || apiKey.isBlank()) {
            DebugLog.log("Send", "skipped: token or API key not configured")
            broadcast(logMessage = "ERROR: Token or API key not configured")
            scheduleNextSend(INTERVAL_PARKED_MS)
            return
        }

        val vm = vehicleManager
        lastSendStartedMs = SystemClock.elapsedRealtime()

        Thread {
            var v = vm?.snapshot()
            var loc = lastLocation

            if (isAllZero(v, loc)) {
                DebugLog.log("Send", "no SOC/GPS on first read — retrying in 3s")
                broadcast(logMessage = "[${nowStr()}] SOC and GPS both zero — retrying in 3s…")
                Thread.sleep(3_000L)
                v = vm?.snapshot()
                loc = lastLocation
                if (isAllZero(v, loc)) {
                    DebugLog.log("Send", "skipped: still no SOC/GPS after retry")
                    broadcast(logMessage = "[${nowStr()}] Skipping send — no valid SOC or GPS data")
                    if (running) scheduleNextSend(nextInterval(v))
                    return@Thread
                }
            }

            // Anchor the poller against what we're sending so the next state flip is caught.
            v?.let { if (it.connected) lastCadenceKey = CadenceKey.of(it) }

            val data = buildPayload(v, loc, carModel)
            val time = nowStr()
            api.sendTelemetry(userToken, apiKey, data).fold(
                onSuccess = {
                    val chg = when {
                        data.is_dcfc == 1     -> " ⚡DC"
                        data.is_charging == 1 -> " ⚡AC"
                        else                  -> ""
                    }
                    val socStr = data.soc?.let { "soc=${"%.1f".format(it)}%" } ?: "no SOC"
                    val gpsStr = if (data.lat != null)
                        ", lat=${"%.4f".format(data.lat)}, lon=${"%.4f".format(data.lon)}"
                    else ", no GPS"
                    DebugLog.log("Send", "OK $socStr spd=${data.speed.toInt()}km/h$gpsStr$chg")
                    broadcast(
                        logMessage    = "[$time] OK — $socStr, spd=${data.speed.toInt()}km/h$gpsStr$chg",
                        statusMessage = intervalDescription(v),
                    )
                    updateNotification("Last sent: $time | SOC: ${data.soc?.let { "%.1f".format(it) } ?: "--"}%")
                },
                onFailure = { e ->
                    DebugLog.log("Send", "FAIL ${e.message}")
                    broadcast(
                        logMessage    = "[$time] FAIL: ${e.message}",
                        statusMessage = intervalDescription(v),
                    )
                    updateNotification("Send error — check log")
                },
            )
            if (running) scheduleNextSend(nextInterval(v))
        }.start()
    }

    private fun buildPayload(
        v: VehicleDataManager.VehicleState?,
        loc: Location?,
        carModel: String?,
    ): TelemetryData {
        val hasGps = loc != null && !(loc.latitude == 0.0 && loc.longitude == 0.0)
        return TelemetryData(
            utc      = System.currentTimeMillis() / 1000,
            soc      = v?.socPercent?.toDouble()?.takeIf { it > 0.0 },
            speed    = v?.speedKmh?.toDouble() ?: ((loc?.speed ?: 0f) * 3.6).toDouble(),
            lat      = if (hasGps) loc!!.latitude  else null,
            lon      = if (hasGps) loc!!.longitude else null,
            is_charging = if (v?.isCharging == true) 1 else 0,
            is_dcfc     = if (v?.isDcfc == true)     1 else 0,
            is_parked   = if (v?.isParked == true)   1 else 0,
            elevation = loc?.altitude?.takeIf { it != 0.0 },
            heading   = loc?.bearing?.toDouble()?.takeIf { it != 0.0 },
            ext_temp  = v?.outsideTempC?.toDouble(),
            power     = v?.chargeRateKw?.toDouble()?.takeIf { it > 0.0 }
                ?.let { if (v.isCharging) -it else it },
            est_battery_range = v?.rangeKm?.takeIf { it > 0 }?.toDouble(),
            car_model = carModel,
        )
    }

    private fun isAllZero(v: VehicleDataManager.VehicleState?, loc: Location?): Boolean =
        (v?.socPercent ?: 0f) == 0f &&
        (loc?.latitude  ?: 0.0) == 0.0 &&
        (loc?.longitude ?: 0.0) == 0.0

    private fun nextInterval(v: VehicleDataManager.VehicleState?): Long = when {
        v == null || v.isParked                      -> INTERVAL_PARKED_MS
        v.shiftMode == VehicleDataManager.GEAR_DRIVE -> INTERVAL_DRIVING_MS
        else                                         -> INTERVAL_OTHER_MS
    }

    private fun intervalDescription(v: VehicleDataManager.VehicleState?): String {
        val ms: Long; val reason: String
        when {
            v == null    -> { ms = INTERVAL_PARKED_MS;  reason = "no vehicle data" }
            v.isDcfc     -> { ms = INTERVAL_PARKED_MS;  reason = "DC fast charging" }
            v.isCharging -> { ms = INTERVAL_PARKED_MS;  reason = "charging" }
            v.isParked   -> { ms = INTERVAL_PARKED_MS;  reason = "parked" }
            v.shiftMode == VehicleDataManager.GEAR_DRIVE
                         -> { ms = INTERVAL_DRIVING_MS; reason = "driving" }
            else         -> { ms = INTERVAL_OTHER_MS;   reason = "gear ${v.shiftMode}" }
        }
        return "Sending every ${ms / 1000}s — $reason"
    }

    // ────────────────────────────── State poller ──────────────────────────────

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            checkForStateChange()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun startStatePoller() {
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun stopStatePoller() {
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun checkForStateChange() {
        val v = vehicleManager?.snapshot() ?: return
        if (!v.connected) return  // skip stale/cached reads while the binder reconnects
        val newKey = CadenceKey.of(v)
        val oldKey = lastCadenceKey
        if (oldKey == null) { lastCadenceKey = newKey; return }
        if (oldKey == newKey) return
        if (SystemClock.elapsedRealtime() - lastSendStartedMs < MIN_SEND_GAP_MS) return
        lastCadenceKey = newKey
        DebugLog.log("Poll", "state change ${oldKey.describe()} → ${newKey.describe()} — short-cutting alarm")
        broadcast(logMessage = "[${nowStr()}] State change: ${oldKey.describe()} → ${newKey.describe()} — sending now")
        cancelScheduledSend()
        sendTelemetry()
    }

    // ────────────────────────────── Alarm scheduling ──────────────────────────────

    // Non-wakeup ELAPSED_REALTIME so we don't pull the device out of doze for sends.
    // Note: this alarm only drives the in-session cadence. It does NOT survive an
    // ignition cycle — BYD force-stops the package, which clears all our alarms.
    // The post-ignition resume is handled out-of-process by DaemonLauncher.
    private fun scheduleNextSend(delayMs: Long) {
        DebugLog.log("Alarm", "scheduleNextSend in ${delayMs}ms")
        val pi = tickPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + delayMs,
            pi,
        )
    }

    private fun cancelScheduledSend() {
        val pi = tickPendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
        DebugLog.log("Alarm", "cancelScheduledSend")
        getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    private fun tickPendingIntent(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        this, ALARM_REQUEST_CODE,
        Intent(this, WakeReceiver::class.java),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    // ────────────────────────────── Vehicle / Location wiring ──────────────────────────────

    private fun connectVehicle() {
        vehicleManager?.disconnect()
        vehicleManager = VehicleDataManager(this).also { vm ->
            if (!vm.connect()) {
                broadcast(logMessage = "WARNING: CarService unavailable — vehicle data will be missing")
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            broadcast(logMessage = "WARNING: Location permission denied — GPS unavailable")
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm
        // Seed lastLocation with the most recent known fix so the very first send
        // has GPS data instead of waiting for a listener callback. MainActivity has
        // been receiving fixes while the user was on screen, so a fresh fix is
        // almost always already cached at this point.
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                val fix = lm.getLastKnownLocation(provider) ?: return@forEach
                val current = lastLocation
                if (current == null || fix.time > current.time) lastLocation = fix
            } catch (_: Exception) {}
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 2_000L, 5f, locationListener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start $provider: ${e.message}")
            }
        }
    }

    // ────────────────────────────── Status broadcast ──────────────────────────────

    private fun broadcast(
        logMessage: String? = null,
        statusMessage: String? = null,
        isStopped: Boolean = false,
    ) {
        val intent = Intent(BROADCAST_ACTION).apply {
            logMessage?.let { putExtra(EXTRA_LOG_MESSAGE, it) }
            statusMessage?.let { putExtra(EXTRA_STATUS_MESSAGE, it) }
            if (isStopped) putExtra(EXTRA_IS_RUNNING, false)
        }
        sendBroadcast(intent)
    }

    // ────────────────────────────── Notification ──────────────────────────────

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ABRP Telemetry", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background telemetry transmission to ABRP"
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ABRP Telemetry")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ────────────────────────────── Misc helpers ──────────────────────────────

    private fun persistRunning(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_RUNNING, enabled).apply()
    }

    private fun nowStr(): String = timeFormat.format(Date())
}
