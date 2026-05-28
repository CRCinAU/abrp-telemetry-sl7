package com.abrp.telemetry

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        const val SEND_URL = "https://api.iternio.com/1/tlm/send"
    }

    private data class ApiResponse(val status: String?, val message: String?)

    fun sendTelemetry(userToken: String, apiKey: String, data: TelemetryData): Result<String> =
        post(userToken, apiKey, buildTlmJson(data))

    // Validation pings the API with a minimal payload — utc (required by ABRP) plus
    // the current SOC if we have a real reading. Caller passes the live SOC from the
    // vehicle snapshot; nulls or non-positive values are omitted so we never lie to
    // ABRP about a zero state of charge during validation.
    fun validateToken(userToken: String, apiKey: String, soc: Double?): Result<String> {
        val utc = System.currentTimeMillis() / 1000
        val tlm = buildString {
            append("{\"utc\":").append(utc)
            soc?.takeIf { it > 0.0 }?.let { append(",\"soc\":").append(it) }
            append("}")
        }
        return post(userToken, apiKey, tlm)
    }

    private fun post(userToken: String, apiKey: String, tlmJson: String): Result<String> {
        return try {
            val url = buildUrl(userToken, apiKey, tlmJson)
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"

            if (response.isSuccessful) {
                val parsed = try { gson.fromJson(body, ApiResponse::class.java) } catch (e: Exception) { null }
                when {
                    parsed?.status == "ok" -> Result.success("ok")
                    parsed?.status != null -> Result.failure(Exception(parsed.message ?: "API error: ${parsed.status}"))
                    else -> Result.failure(Exception("Unexpected response: $body"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildTlmJson(data: TelemetryData): String {
        val map = mutableMapOf<String, Any>(
            "utc" to data.utc,
            "speed" to data.speed,
            "is_charging" to data.is_charging,
            "is_dcfc" to data.is_dcfc,
            "is_parked" to data.is_parked
        )
        data.soc?.let { map["soc"] = it }
        data.lat?.let { map["lat"] = it }
        data.lon?.let { map["lon"] = it }
        data.elevation?.let { map["elevation"] = it }
        data.heading?.let { map["heading"] = it }
        data.soh?.let { map["soh"] = it }
        data.ext_temp?.let { map["ext_temp"] = it }
        data.power?.let { map["power"] = it }
        data.est_battery_range?.let { map["est_battery_range"] = it }
        data.car_model?.takeIf { it.isNotBlank() }?.let { map["car_model"] = it }
        return gson.toJson(map)
    }

    private fun buildUrl(token: String, apiKey: String, tlmJson: String): String {
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val encodedApiKey = URLEncoder.encode(apiKey, "UTF-8")
        val encodedTlm = URLEncoder.encode(tlmJson, "UTF-8")
        return "$SEND_URL?token=$encodedToken&api_key=$encodedApiKey&tlm=$encodedTlm"
    }
}
