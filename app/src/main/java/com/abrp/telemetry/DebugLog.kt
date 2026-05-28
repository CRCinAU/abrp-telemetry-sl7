package com.abrp.telemetry

import android.util.Log

/**
 * Thin wrapper that tags every signal-trace entry as "AbrpTelemetry" in logcat so
 * the lifecycle of the service, alarms, send attempts, state changes, and the
 * resurrector daemon are easy to filter (`adb logcat -s AbrpTelemetry:V`).
 */
object DebugLog {
    private const val TAG = "AbrpTelemetry"

    fun log(component: String, message: String) {
        Log.i(TAG, "[$component] $message")
    }
}
