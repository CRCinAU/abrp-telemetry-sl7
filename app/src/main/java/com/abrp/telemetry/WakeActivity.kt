package com.abrp.telemetry

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Headless stub activity. The shell-user resurrector daemon `am start`s this when it
 * notices our app process is gone — an explicit activity launch is the cleanest way
 * to un-stop the package from BYD's `forceStopPackage()` (see DaemonLauncher).
 *
 * We check the user's `telemetry_running` pref here so the daemon's resurrect doesn't
 * override a user-initiated Stop. If telemetry is wanted, we kick the foreground
 * service; either way we finish() immediately so no UI is shown.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val running = getSharedPreferences("abrp_prefs", Context.MODE_PRIVATE)
            .getBoolean("telemetry_running", false)
        DebugLog.log("WakeActivity", "launched by daemon; telemetry_running=$running")
        if (running) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TelemetryService::class.java).apply {
                    action = TelemetryService.ACTION_START
                },
            )
        }
        finish()
    }
}
