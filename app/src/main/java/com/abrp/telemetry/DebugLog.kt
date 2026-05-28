package com.abrp.telemetry

import android.util.Log
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Best-effort signal trace for debugging power-off / restart / resume behavior.
 *
 * Writes to /data/local/tmp/abrp-telemetry.txt — that directory is owned by `shell`
 * and is not writable by apps by default. To enable, ahead of time:
 *
 *   adb shell touch /data/local/tmp/abrp-telemetry.txt
 *   adb shell chmod 0666 /data/local/tmp/abrp-telemetry.txt
 *
 * Without those prep steps the file write silently fails; logcat still captures
 * every line under the "AbrpTelemetry" tag.
 */
object DebugLog {
    private const val TAG = "AbrpTelemetry"
    private const val PATH = "/data/local/tmp/abrp-telemetry.txt"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // One-shot diagnostic so a failed first-write doesn't spam logcat every line.
    @Volatile private var fileWriteWarned = false

    @Synchronized
    fun log(component: String, message: String) {
        val line = "${timeFormat.format(Date())} [$component] $message"
        Log.i(TAG, line)
        try {
            // FileWriter(path, append=true) creates the file if it does not exist —
            // assuming /data/local/tmp is writable by this app's uid. By default it
            // isn't (dir mode 0771, owner shell): run `adb shell chmod 0777 /data/local/tmp`
            // once, or pre-create the file with `chmod 0666`.
            FileWriter(PATH, true).use { it.write("$line\n") }
        } catch (e: Throwable) {
            if (!fileWriteWarned) {
                fileWriteWarned = true
                Log.w(TAG, "Cannot write $PATH (${e.javaClass.simpleName}: ${e.message}). " +
                    "Run `adb shell chmod 0777 /data/local/tmp` or pre-create the file 0666.")
            }
        }
    }
}
