package com.abrp.telemetry

import android.content.Context
import android.util.Log
import com.ts.lib.caradapter.CarAdapterManager
import com.ts.lib.caradapter.body.CarBodyManager
import com.ts.lib.caradapter.body.ChargingAdapterManager
import com.ts.lib.caradapter.general.CarGeneralAdapterManager
import com.ts.lib.caradapter.hvac.HvacAdapterManager
import com.ts.lib.caradapter.interfaces.OnCarAdapterConnectListener
import com.ts.lib.caradapter.sensor.CarSensorAdapterManager

class VehicleDataManager(private val context: Context) {

    companion object {
        private const val TAG = "VehicleDataManager"
        private const val DCFC_THRESHOLD_KW = 7.5f
        private const val CHARGING_THRESHOLD_KW = 0.1f
        const val GEAR_PARK    = 1
        const val GEAR_REVERSE = 2  // inferred from sequence P=1,N=3,D=4; never observed — 360 camera activates on R and kills CarAdapterService before any poll can catch it
        const val GEAR_NEUTRAL = 3
        const val GEAR_DRIVE   = 4

        fun shiftLabel(shiftMode: Int): String = when (shiftMode) {
            GEAR_PARK    -> "P"
            GEAR_REVERSE -> "R"
            GEAR_NEUTRAL -> "N"
            GEAR_DRIVE   -> "D"
            else         -> "?"
        }
    }

    data class VehicleState(
        val socPercent: Float = 0f,
        val speedKmh: Float = 0f,
        val isCharging: Boolean = false,
        val isDcfc: Boolean = false,
        val outsideTempC: Float? = null,
        val odometerKm: Float = 0f,
        val chargeRateKw: Float = 0f,
        val rangeKm: Int = 0,
        val shiftMode: Int = -1,
        val isParked: Boolean = false,
        val connected: Boolean = false
    )

    @Volatile var state = VehicleState()
        private set

    var onStateChanged: ((VehicleState) -> Unit)? = null

    private var carAdapter: CarAdapterManager? = null
    private var notBoundPolls = 0
    val isRecovering: Boolean get() = notBoundPolls > 0

    @Volatile private var sensorMgr: CarSensorAdapterManager? = null
    @Volatile private var generalMgr: CarGeneralAdapterManager? = null
    @Volatile private var chargingMgr: ChargingAdapterManager? = null
    @Volatile private var hvacMgr: HvacAdapterManager? = null
    @Volatile private var bodyMgr: CarBodyManager? = null

    fun connect(): Boolean {
        return try {
            val adapter = CarAdapterManager.getInstance(context.applicationContext)
            Log.i(TAG, "connect: getInstance id=${System.identityHashCode(adapter)}")
            carAdapter = adapter
            adapter.setOnManagerConnChangedListener(object : OnCarAdapterConnectListener {
                override fun onBinderStateChanged(connected: Boolean) {
                    Log.i(TAG, "onBinderStateChanged: connected=$connected id=${System.identityHashCode(adapter)}")
                    if (!connected) {
                        sensorMgr = null
                        generalMgr = null
                        chargingMgr = null
                        hvacMgr = null
                        bodyMgr = null
                        state = state.copy(connected = false)
                        onStateChanged?.invoke(state)
                    }
                }
            })
            val result = tryGet("connect") { adapter.connect() }
            Log.i(TAG, "connect: adapter.connect() returned $result, isCarServiceBound=${adapter.isCarServiceBound()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "connect() failed: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    fun disconnect() {
        try {
            carAdapter?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error: ${e.message}")
        } finally {
            carAdapter = null
            sensorMgr = null
            generalMgr = null
            chargingMgr = null
            hvacMgr = null
            bodyMgr = null
            state = state.copy(connected = false)
        }
    }

    fun snapshot(): VehicleState {
        // Mark the cache as not-fresh before any early return so consumers
        // can tell stale-from-cache reads apart from a live poll. The SOC /
        // speed / parked / charging / etc. fields are kept around so the
        // UI's "last known value" display keeps working, but `connected=false`
        // is the contract: don't ship these to ABRP as fresh telemetry.
        fun staleReturn(): VehicleState {
            if (state.connected) state = state.copy(connected = false)
            return state
        }
        val ca = carAdapter ?: return staleReturn()
        if (!ca.isCarServiceBound()) {
            notBoundPolls++
            if (notBoundPolls % 2 == 1) {
                Log.w(TAG, "snapshot: service not bound (poll $notBoundPolls) — attempting reconnect")
                tryResetSingleton(ca)
                connect()
            }
            return staleReturn()
        }
        notBoundPolls = 0

        // Lazily acquire sub-managers on first successful poll
        if (sensorMgr == null) sensorMgr = tryGet("sensorMgr") { ca.getCarAdapterManager("sensor") as? CarSensorAdapterManager }
        if (generalMgr == null) generalMgr = tryGet("generalMgr") { ca.getCarAdapterManager("general") as? CarGeneralAdapterManager }
        if (chargingMgr == null) chargingMgr = tryGet("chargingMgr") { ca.getCarAdapterManager("charging") as? ChargingAdapterManager }
        if (hvacMgr == null) hvacMgr = tryGet("hvacMgr") { ca.getCarAdapterManager("hvac") as? HvacAdapterManager }
        if (bodyMgr == null) bodyMgr = tryGet("bodyMgr") { ca.getCarAdapterManager("body") as? CarBodyManager }

        val sm = sensorMgr
        val gm = generalMgr
        val cm = chargingMgr
        val hm = hvacMgr
        val bm = bodyMgr

        if (sm == null && gm == null && cm == null) {
            Log.w(TAG, "snapshot: all managers null after bind")
            return staleReturn()
        }

        val speed = if (sm != null) tryGet("speed") { sm.currentSpeed } ?: state.speedKmh else state.speedKmh
        // Outside air temp comes from HVAC manager, not sensor manager (sensor returns 0.0).
        // BYD's HVAC adapter reports the value as plain °C (Int). 0 is a "no reading"
        // sentinel — not literal 0°C — and the adapter has also been observed
        // returning 195 (and similar wildly out-of-range values) when the HVAC
        // module hasn't initialised yet, so clamp to a plausible outside-air range.
        val temp: Float? = if (hm != null) {
            tryGet("tempOut") { hm.tempratureOut }?.takeIf { it in -50..60 && it != 0 }?.toFloat()
                ?: state.outsideTempC
        } else state.outsideTempC
        val soc = if (gm != null) tryGet("soc") { gm.elecPercentageValue } ?: state.socPercent else state.socPercent
        val odo = if (gm != null) tryGet("odo") { gm.totalMileageValue } ?: state.odometerKm else state.odometerKm
        val range = if (gm != null) tryGet("range") { gm.elecDrivingRangeValue } ?: state.rangeKm else state.rangeKm
        val chargerState = if (cm != null) tryGet("chargerState") { cm.chargerState } ?: -1 else -1
        val isCharging = if (chargerState >= 0) chargerState > 0 else false
        val isDcfc = if (chargerState >= 0) chargerState == 2 else false
        // Only read charging power when actually charging — value is garbage when not charging
        val powerKw = if (isCharging && cm != null) tryGet("chargingPower") { cm.chargingPower } ?: 0f else 0f
        val shiftMode = if (bm != null) tryGet("shiftMode") { bm.shiftMode } ?: state.shiftMode else state.shiftMode
        // shiftMode 0 (charging artifact) and 1 (P) are both parked; -1 unknown falls back to speed
        val isParked = isCharging || if (shiftMode >= 0) shiftMode <= 1 else speed < 2f

        Log.w(TAG, "snapshot: soc=$soc speed=$speed chargerState=$chargerState powerKw=$powerKw temp=$temp odo=$odo range=$range shiftMode=$shiftMode isParked=$isParked")

        state = VehicleState(
            socPercent = soc,
            speedKmh = speed.coerceAtLeast(0f),
            isCharging = isCharging,
            isDcfc = isDcfc,
            outsideTempC = temp,
            odometerKm = odo,
            chargeRateKw = powerKw,
            rangeKm = range,
            shiftMode = shiftMode,
            isParked = isParked,
            connected = true
        )
        return state
    }

    private fun tryResetSingleton(ca: CarAdapterManager) {
        try {
            ca.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "tryResetSingleton: disconnect threw ${e.javaClass.simpleName}: ${e.message}")
        }
        // Attempt to null the static singleton field so getInstance() returns a fresh object
        val fields = CarAdapterManager::class.java.declaredFields
        Log.i(TAG, "tryResetSingleton: CarAdapterManager fields: ${fields.map { it.name }}")
        val singletonField = fields.firstOrNull { f ->
            java.lang.reflect.Modifier.isStatic(f.modifiers) &&
            CarAdapterManager::class.java.isAssignableFrom(f.type)
        }
        if (singletonField != null) {
            singletonField.isAccessible = true
            singletonField.set(null, null)
            Log.i(TAG, "tryResetSingleton: nulled field '${singletonField.name}' — fresh instance on next getInstance()")
        } else {
            Log.w(TAG, "tryResetSingleton: no static CarAdapterManager field found — singleton reset not possible")
        }
        carAdapter = null
        sensorMgr = null; generalMgr = null; chargingMgr = null; hvacMgr = null; bodyMgr = null
    }

    private inline fun <T> tryGet(name: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "$name failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
