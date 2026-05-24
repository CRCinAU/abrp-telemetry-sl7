package com.abrp.telemetry

data class TelemetryData(
    val utc: Long,
    val soc: Double? = null,
    val speed: Double,
    val lat: Double? = null,
    val lon: Double? = null,
    val is_charging: Int,
    val is_dcfc: Int,
    val is_parked: Int,
    val elevation: Double? = null,
    val heading: Double? = null,
    val soh: Double? = null,
    val ext_temp: Double? = null,
    val power: Double? = null,
    val est_battery_range: Double? = null,
    val car_model: String? = null
)
