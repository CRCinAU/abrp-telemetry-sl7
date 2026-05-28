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
        // Log.w not Log.i: the Sealion 7 sets persist.log.tag=W system-wide, so
        // anything below Warning is filtered out at the OS level before reaching
        // logcat. These are lifecycle traces, not real warnings — the priority
        // is just to make sure they actually show up.
        Log.w(TAG, "[$component] $message")
    }
}
