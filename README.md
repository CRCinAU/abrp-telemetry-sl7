# ABRP Telemetry — BYD Sealion 7

Sends live vehicle telemetry to [A Better Route Planner](https://abetterrouteplanner.com/) from a BYD Sealion 7 running DiLink 5.0 (Android Automotive OS, Android 11).

Data is read directly from the car's internal TS vendor SDK (`CarAdapterService`) rather than via OBD, providing SOC, speed, range, charging state, power, and temperatures.

---

# For users — install and configure

You don't need to build anything. Grab the latest pre-built APK from [Releases](../../releases) and sideload it.

## Install

1. **Enable ADB debugging on the head unit:**
   - On the car, open **Settings → System → Version**
   - Tap the **Factory Reset** label repeatedly to reveal the hidden developer menu.
   - Rotate the screen to landscape orientation to access the additional options.
   - Enable **ADB Debugging**.
   - Note the head unit's IP on its wifi network.
2. **From a laptop** connected to the same network:
   ```bash
   adb connect <car-ip>:5555
   adb install abrp-telemetry-sl7-<version>-debug.apk
   ```
3. Grant the location permission when prompted on first launch.

Releases sign with a stable debug key from `v1.0.4` onward, so future updates can be installed in place with `adb install -r`. (One-time uninstall needed when upgrading from a release before v1.0.4 — those each had different auto-generated keys.)

## Configure

On first launch:

1. Enter your **ABRP user token** (ABRP app → Settings → Live Data → Copy Token)
2. Select your **car model** from the dropdown (defaults to Sealion 7 Comfort RWD)
3. Tap **Validate and Save** — the app contacts ABRP to confirm the token works, then saves and locks both the token and car model fields
4. Tap **Start Telemetry**

To change the token or car model later, tap **Edit Values** to re-enable both fields, then validate again.

The app follows the head unit's system **dark mode** automatically.

## How it behaves

### Send interval

The telemetry send rate adapts to driving state:

| State | Interval |
|---|---|
| Parked (P gear, or charging) | 60 s |
| Drive gear (D) | 10 s |
| Any other state (N, R, unknown) | 30 s |

State transitions (parking, shifting into D, plugging in to charge, DC fast charging start/stop) are detected within 2 s — the app doesn't wait the full scheduled interval to pick them up.

If SOC and GPS coordinates are both zero when a send is due (e.g. the car API or GPS hasn't connected yet), the app retries once after 3 seconds and skips the send entirely if data is still unavailable.

### Resume after ignition cycles

BYD's `com.ts.appservice.power` force-stops third-party apps when the ignition turns off. To keep telemetry going across ignition cycles, the app spawns a tiny resurrector script that runs as the shell user (outside the app's UID, so the force-stop doesn't touch it) over the head unit's own ADB daemon. When the app gets stopped, the resurrector re-launches it within ~30 s.

The resurrector is installed when you tap **Start Telemetry** and fully removed (process killed, files deleted) when you tap **Stop**. No interactive setup needed.

### Settings persistence across reinstalls (optional)

The app mirrors its settings (token + car model) to `/data/local/tmp/abrp-telemetry-settings.json` whenever they change, so a reinstall can restore them. `/data/local/tmp` isn't writable by apps by default, so the file must be pre-created with shell ownership:

```bash
adb shell touch /data/local/tmp/abrp-telemetry-settings.json
adb shell chmod 0666 /data/local/tmp/abrp-telemetry-settings.json
```

Without this prep the app still works — the backup just won't be written, and a reinstall starts from blank.

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

---

# For developers — build from source

## Requirements

- BYD Sealion 7 with DiLink 5.0 (AAOS Android 11) for testing
- ADB access to the car (`adb connect <car-ip>:5555`)
- Android SDK with `platform-tools` and `build-tools;34.0.0` on your build machine
- JDK 17
- An Iternio developer API key (contact@iternio.com)

## 1. Pull system JARs from the car

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

## 2. Configure your API key

Add your Iternio developer API key to `local.properties` (gitignored):

```
abrp.api_key=your_key_here
```

The key is baked into the build via `BuildConfig.ABRP_API_KEY`. Builds without a key will compile but telemetry sends will fail.

## 3. Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture notes

The standard AOSP `CarPropertyManager` API is blocked on production BYD firmware — vendor properties require the `CAR_VENDOR_EXTENSION` signature-level permission which cannot be granted to third-party apps.

Instead, the app binds to `com.ts.appservice.caradapter.CarAdapterService`, a TS/BYD broker service exported without caller permission requirements. This service holds the sensitive permissions internally and exposes sub-managers for sensor, general, charging, and HVAC data.

Key implementation notes:

- **Sub-managers are acquired lazily** — acquiring them inside `onServiceConnected` causes a deadlock because that callback holds the `CarAdapterManager` monitor while `getCarAdapterManager()` tries to acquire the same lock. Managers are instead fetched on the first `snapshot()` poll after `isCarServiceBound()` returns true.
- **Reconnect on drop** — when `isCarServiceBound()` returns false, `snapshot()` uses reflection to null the static `CarAdapterManager` singleton field so `getInstance()` returns a fresh object, then calls `connect()`. Recovery is retried every 4 polls. A known trigger is shifting into Reverse: the 360-camera overlay activates and causes `CarAdapterService` to restart; Reverse gear (value 2) is therefore never observed in telemetry.
- **Outside temperature** comes from `HvacAdapterManager.getTempratureOut()`, not the sensor manager (which returns 0).
- **Charging power** is only read when `chargerState > 0` — the value is garbage (~359 kW) when not charging.
- **GPS** is read via Android `LocationManager` (AAOS has no Google Play Services).
- **Ignition resume** uses [`dadb`](https://github.com/mobile-dev-inc/dadb) to tunnel into the head unit's own ADB daemon at `127.0.0.1:5555` from inside the app, then `app_process`-style spawns a shell-script daemon under UID 2000 that calls `am start` on a hidden `WakeActivity` whenever it sees the app process gone. The same technique [Overdrive](https://github.com/yash-srivastava/Overdrive-release) uses.


## Debugging

The app tags every lifecycle / send / state-change event in logcat under `AbrpTelemetry`:

```bash
adb logcat -s AbrpTelemetry:V
```

The shell-user resurrector daemon writes its own log to `/data/local/tmp/abrp-daemon.log` (visible without `run-as`).
