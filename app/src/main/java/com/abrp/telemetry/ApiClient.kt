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

    fun sendTelemetry(userToken: String, apiKey: String, data: TelemetryData): Result<String> {
        return try {
            val tlmJson = buildTlmJson(data)
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

    fun validateToken(userToken: String, apiKey: String): Result<String> {
        val testData = TelemetryData(
            utc = System.currentTimeMillis() / 1000,
            soc = 50.0,
            speed = 0.0,
            lat = 0.0,
            lon = 0.0,
            is_charging = 0,
            is_dcfc = 0,
            is_parked = 1
        )
        return sendTelemetry(userToken, apiKey, testData)
    }

    private fun buildTlmJson(data: TelemetryData): String {
        val map = mutableMapOf<String, Any>(
            "utc" to data.utc,
            "soc" to data.soc,
            "speed" to data.speed,
            "lat" to data.lat,
            "lon" to data.lon,
            "is_charging" to data.is_charging,
            "is_dcfc" to data.is_dcfc,
            "is_parked" to data.is_parked
        )
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
