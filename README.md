# ABRP Telemetry — BYD Sealion 7

Sends live vehicle telemetry to [A Better Route Planner](https://abetterrouteplanner.com/) from a BYD Sealion 7 running DiLink 5.0 (Android Automotive OS, Android 11).

Data is read directly from the car's internal TS vendor SDK (`CarAdapterService`) rather than via OBD, providing SOC, speed, range, charging state, power, and temperatures.

## Requirements

- BYD Sealion 7 with DiLink 5.0 (AAOS Android 11)
- ADB access to the car (`adb connect <car-ip>:5555`)
- Android SDK with `platform-tools` on your build machine
- A free [ABRP](https://abetterrouteplanner.com/) account and user token
- An Iternio developer API key (contact@iternio.com) — **or use the pre-built release APK** (see [Releases](../../releases)), which has a key bundled and requires no account with Iternio

## First-time setup

### 1. Pull system JARs from the car

These are required at compile time but are never bundled into the APK — they come from the car's system partition at runtime.

```bash
adb connect <car-ip>:5555
adb pull /system/framework/android.car.jar app/libs/android.car.jar
adb pull /system/framework/ts-framework.jar app/libs/ts-framework-raw.jar
```

`ts-framework.jar` ships as DEX, not standard Java bytecode, so it must be converted before use:

```bash
# using dex2jar (https://github.com/pxb1988/dex2jar)
d2j-dex2jar.sh app/libs/ts-framework-raw.jar -o app/libs/ts-framework.jar
rm app/libs/ts-framework-raw.jar
```

### 2. Configure your API key

Add your Iternio developer API key to `local.properties` (this file is gitignored):

```
abrp.api_key=your_key_here
```

The key is baked into the build via `BuildConfig.ABRP_API_KEY`. Builds without a key will compile but telemetry sends will fail.

> **No API key?** Download the pre-built release APK from the [Releases](../../releases) page instead — it has a key bundled and skips this step entirely.

### 3. Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

On first launch:

1. Enter your **ABRP user token** (ABRP app → Settings → Live Data → Copy Token)
2. Select your **car model** from the dropdown (defaults to Sealion 7 Comfort RWD)
3. Tap **Validate and Save** — the app contacts ABRP to confirm the token is valid, then saves it and locks both the token and car model fields
4. Tap **Start Telemetry**

To change the token or car model later, tap **Edit Values** to re-enable both fields, then validate again.

The app follows the head unit's system **dark mode** automatically.

### Send interval

The telemetry send rate adapts to driving state:

| State | Interval |
|---|---|
| Parked (P gear, or charging) | 60 s |
| Drive gear (D) | 10 s |
| Any other state (N, R, unknown) | 30 s |

State transitions (parking, shifting into D, plugging in to charge, DC fast charging start/stop) are detected within 2 s by a background poller — the app doesn't wait the full scheduled interval to pick them up.

If SOC and GPS coordinates are both zero when a send is due (e.g. the car API or GPS hasn't connected yet), the app retries once after 3 seconds and skips the send entirely if data is still unavailable.

### Resume across ignition cycles

BYD's `com.ts.appservice.power` issues `forceStopPackage()` on third-party apps at ignition off. A stopped package can't be woken by broadcasts (vendor broadcasts don't set `FLAG_INCLUDE_STOPPED_PACKAGES`) and sticky services don't survive a force-stop, so nothing inside the app's UID can revive itself.

To work around this, the app uses the same technique as [Overdrive](https://github.com/yash-srivastava/Overdrive-release): it bundles a [`dadb`](https://github.com/mobile-dev-inc/dadb) ADB client, connects to the head unit's own ADB daemon at `127.0.0.1:5555`, and spawns a tiny resurrector script that runs as the **shell user (UID 2000)** — outside the app's UID, so `forceStopPackage` doesn't touch it. Every 30 s the daemon checks `pidof com.abrp.telemetry`; if the app is gone, it `am start`s a hidden activity that un-stops the package and re-launches the foreground service.

The daemon is installed when you tap Start Telemetry and fully removed (process killed, script and log files deleted) when you tap Stop. The RSA key the app uses for the localhost ADB connection is generated on first use and saved in the app's private storage; the Sealion 7's `adbd` accepts it without an auth prompt.

### Settings persistence across reinstalls (optional)

The app mirrors its settings (token + car model) to `/data/local/tmp/abrp-telemetry-settings.json` whenever they change, so a reinstall can restore them automatically. `/data/local/tmp` isn't writable by apps by default, so the file must be pre-created with shell ownership:

```bash
adb shell touch /data/local/tmp/abrp-telemetry-settings.json
adb shell chmod 0666 /data/local/tmp/abrp-telemetry-settings.json
```

Without this prep, the app still works — the backup file just won't be written, and a reinstall will start from a blank token.

## How it works

The standard AOSP `CarPropertyManager` API is blocked on production BYD firmware — vendor properties require the `CAR_VENDOR_EXTENSION` signature-level permission which cannot be granted to third-party apps.

Instead, the app binds to `com.ts.appservice.caradapter.CarAdapterService`, a TS/BYD broker service exported without caller permission requirements. This service holds the sensitive permissions internally and exposes sub-managers for sensor, general, charging, and HVAC data.

Key implementation notes:

- **Sub-managers are acquired lazily** — acquiring them inside `onServiceConnected` causes a deadlock because that callback holds the `CarAdapterManager` monitor while `getCarAdapterManager()` tries to acquire the same lock. Managers are instead fetched on the first `snapshot()` poll after `isCarServiceBound()` returns true.
- **Reconnect on drop** — when `isCarServiceBound()` returns false, `snapshot()` uses reflection to null the static `CarAdapterManager` singleton field so `getInstance()` returns a fresh object, then calls `connect()`. Recovery is retried every 4 polls. A known trigger is shifting into Reverse: the 360-camera overlay activates and causes `CarAdapterService` to restart; Reverse gear (value 2) is therefore never observed in telemetry.
- **Outside temperature** comes from `HvacAdapterManager.getTempratureOut()`, not the sensor manager (which returns 0).
- **Charging power** is only read when `chargerState > 0` — the value is garbage (~359 kW) when not charging.
- **GPS** is read via Android `LocationManager` (AAOS has no Google Play Services).

## Debugging

The app tags every lifecycle / send / state-change event in logcat under `AbrpTelemetry`:

```bash
adb logcat -s AbrpTelemetry:V
```

The shell-user resurrector daemon writes its own log to `/data/local/tmp/abrp-daemon.log` (visible without `run-as`).

## Data sent to ABRP

| Field | Source |
|---|---|
| `soc` | `CarGeneralAdapterManager.getElecPercentageValue()` |
| `speed` | `CarSensorAdapterManager.getCurrentSpeed()` |
| `est_battery_range` | `CarGeneralAdapterManager.getElecDrivingRangeValue()` |
| `is_charging` / `is_dcfc` | `ChargingAdapterManager.getChargerState()` |
| `power` | `ChargingAdapterManager.getChargingPower()` (when charging) |
| `ext_temp` | `HvacAdapterManager.getTempratureOut()` |
| `lat` / `lon` / `elevation` / `heading` | Android `LocationManager` |
| `car_model` | User-selected from dropdown |
