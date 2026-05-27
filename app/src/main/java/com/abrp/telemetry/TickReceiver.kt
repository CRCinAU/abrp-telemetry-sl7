package com.abrp.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("telemetry_running", false)) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, TelemetryService::class.java).apply {
                action = TelemetryService.ACTION_TICK
            }
        )
    }
}
