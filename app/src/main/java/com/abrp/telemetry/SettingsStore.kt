package com.abrp.telemetry

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * External JSON backup of user-configurable settings at
 * /data/local/tmp/abrp-telemetry-settings.json. Currently captures the ABRP
 * user token and the selected car model so they survive an app data wipe /
 * reinstall as long as the file is intact.
 *
 * Apps cannot create files under /data/local/tmp by default — pre-create it
 * with shell ownership and world-writable mode:
 *
 *   adb shell touch /data/local/tmp/abrp-telemetry-settings.json
 *   adb shell chmod 0666 /data/local/tmp/abrp-telemetry-settings.json
 */
object SettingsStore {
    private const val PATH = "/data/local/tmp/abrp-telemetry-settings.json"
    private const val PREFS = "abrp_prefs"
    private const val KEY_TOKEN = "user_token"
    private const val KEY_CAR_MODEL = "car_model"

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    data class Settings(val userToken: String, val carModel: String) {
        val isEmpty: Boolean get() = userToken.isBlank() && carModel.isBlank()
    }

    /** Persist a fresh snapshot of the settings to the JSON file. Best-effort. */
    fun write(settings: Settings): Boolean = try {
        val payload = linkedMapOf(
            KEY_TOKEN to settings.userToken,
            KEY_CAR_MODEL to settings.carModel,
        )
        File(PATH).writeText(gson.toJson(payload))
        DebugLog.log("SettingsStore", "wrote settings to $PATH")
        true
    } catch (e: Throwable) {
        DebugLog.log("SettingsStore", "could not write settings to $PATH (${e.javaClass.simpleName}: ${e.message})")
        false
    }

    private fun readFromFile(): Settings? = try {
        val text = File(PATH).readText().trim()
        if (text.isBlank()) {
            null
        } else {
            val map: Map<String, String> = gson.fromJson(text, mapType) ?: emptyMap()
            Settings(
                userToken = map[KEY_TOKEN].orEmpty(),
                carModel = map[KEY_CAR_MODEL].orEmpty(),
            ).takeIf { !it.isEmpty }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Returns the current settings. If prefs is empty (e.g. just reinstalled),
     * hydrates prefs from the JSON file before returning so subsequent reads
     * are fast and consistent.
     */
    fun resolve(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedToken = prefs.getString(KEY_TOKEN, "").orEmpty()
        val storedCar = prefs.getString(KEY_CAR_MODEL, "").orEmpty()
        if (storedToken.isNotBlank()) return Settings(storedToken, storedCar)

        val fromFile = readFromFile() ?: return Settings("", storedCar)
        prefs.edit().apply {
            if (fromFile.userToken.isNotBlank()) putString(KEY_TOKEN, fromFile.userToken)
            if (fromFile.carModel.isNotBlank()) putString(KEY_CAR_MODEL, fromFile.carModel)
            apply()
        }
        DebugLog.log("SettingsStore", "restored settings from $PATH into prefs")
        return fromFile
    }
}
