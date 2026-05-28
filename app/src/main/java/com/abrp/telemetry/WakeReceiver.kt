package com.abrp.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Two entry points to start (or kick) the telemetry service:
 *
 *  - BOOT_COMPLETED: real head-unit reboot.
 *  - AlarmManager periodic ticks: PendingIntent targets this class explicitly.
 *
 * BYD ignition off/on is NOT a reboot — it's Suspend-To-RAM with a vendor-driven
 * forceStopPackage on top, which puts our package into stopped state and prevents
 * any broadcast (vendor or otherwise) from reaching us. That resume path is handled
 * out-of-process by [DaemonLauncher] (shell-user daemon → WakeActivity), not by
 * this receiver.
 *
 * In every case we only start the service if the user has telemetry enabled.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("telemetry_running", false)
        DebugLog.log("WakeReceiver", "received action=${intent.action ?: "<null>"} telemetry_running=$enabled serviceAlive=${TelemetryService.isRunning}")
        if (!enabled) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, TelemetryService::class.java).apply {
                action = TelemetryService.ACTION_START
            },
        )
    }
}
