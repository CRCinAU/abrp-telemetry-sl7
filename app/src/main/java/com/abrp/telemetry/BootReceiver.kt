package com.abrp.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("telemetry_running", false)) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TelemetryService::class.java).apply { action = TelemetryService.ACTION_START }
            )
        }
    }
}
