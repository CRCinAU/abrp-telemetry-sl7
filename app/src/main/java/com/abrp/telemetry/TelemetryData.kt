package com.abrp.telemetry

data class TelemetryData(
    val utc: Long,
    val soc: Double? = null,
    val speed: Double,
    val lat: Double? = null,
    val lon: Double? = null,
    // Nullable so we can omit them when we don't have fresh vehicle data —
    // sending is_parked=0 because the binder happens to be reconnecting is
    // actively wrong (ABRP plots the car as actively driving).
    val is_charging: Int? = null,
    val is_dcfc: Int? = null,
    val is_parked: Int? = null,
    val elevation: Double? = null,
    val heading: Double? = null,
    val soh: Double? = null,
    val ext_temp: Double? = null,
    val power: Double? = null,
    val est_battery_range: Double? = null,
    val car_model: String? = null
)
