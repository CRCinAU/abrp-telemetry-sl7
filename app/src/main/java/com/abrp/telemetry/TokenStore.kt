package com.abrp.telemetry

import android.content.Context
import java.io.File

/**
 * External backup of the ABRP user token at /data/local/tmp/abrp-user-token.txt.
 *
 * Written when validation succeeds; read on app/service startup if no token is in prefs.
 * Lets the token survive an app data wipe / reinstall as long as the file is intact.
 *
 * Same permissions caveat as DebugLog — the user must prep the file:
 *
 *   adb shell touch /data/local/tmp/abrp-user-token.txt
 *   adb shell chmod 0666 /data/local/tmp/abrp-user-token.txt
 */
object TokenStore {
    private const val PATH = "/data/local/tmp/abrp-user-token.txt"
    private const val PREFS = "abrp_prefs"
    private const val KEY = "user_token"

    // File.writeText creates the file if it doesn't exist — assuming the parent dir is
    // writable by this app's uid. /data/local/tmp is dir mode 0771 (owner shell) by
    // default; either `adb shell chmod 0777 /data/local/tmp` once, or pre-create the
    // file with `chmod 0666` to make this succeed.
    fun write(token: String): Boolean = try {
        File(PATH).writeText(token.trim())
        DebugLog.log("TokenStore", "wrote token to $PATH")
        true
    } catch (e: Throwable) {
        DebugLog.log("TokenStore", "could not write token to $PATH (${e.javaClass.simpleName}: ${e.message})")
        false
    }

    fun readFromFile(): String? = try {
        File(PATH).readText().trim().takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /**
     * Returns the token from prefs, falling back to the file if prefs is empty.
     * Side effect: when the file is used as a fallback, the token is also written
     * back into prefs so subsequent reads are fast and consistent.
     */
    fun resolve(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, "").orEmpty()
        if (stored.isNotBlank()) return stored
        val fromFile = readFromFile() ?: return ""
        prefs.edit().putString(KEY, fromFile).apply()
        DebugLog.log("TokenStore", "restored token from $PATH into prefs")
        return fromFile
    }
}
