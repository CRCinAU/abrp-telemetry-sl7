package com.abrp.telemetry

import android.content.Context
import java.io.File

/**
 * Spawns a tiny shell-user (UID 2000) resurrector daemon that re-launches our app
 * after BYD's ignition-off `forceStopPackage()` puts our package into stopped state.
 *
 * Why this is needed: on the BYD Sealion 7, `com.ts.appservice.power` issues a
 * forceStopPackage on third-party apps at ignition off. Stopped apps don't receive
 * vendor broadcasts (no FLAG_INCLUDE_STOPPED_PACKAGES) and START_STICKY services
 * don't survive a force-stop — so nothing inside our own UID can revive us. The
 * canonical escape (and what Overdrive uses on other BYD models) is to tunnel
 * through the device's own ADB daemon at 127.0.0.1:5555, which runs as `shell`,
 * and spawn a daemon under that UID. forceStopPackage targets package UIDs; the
 * shell-user process is unaffected.
 *
 * The daemon itself is dumb: every 30s, if `pidof com.abrp.telemetry` returns
 * nothing, it `am start`s [WakeActivity], which un-stops the package and kicks
 * the foreground service.
 *
 * Lifecycle:
 *  - [installAndStart] is called from TelemetryService.start() — daemon runs
 *    exactly when telemetry is in a sending state.
 *  - [uninstall] is called from TelemetryService.stop() — daemon and its files
 *    are fully removed, no traces left in /data/local/tmp.
 *
 * First-time setup: the device shows a one-time "Allow ADB connection?" dialog;
 * the user must accept (and tick "always allow"). Subsequent connects are silent.
 */
object DaemonLauncher {

    private const val SCRIPT_PATH = "/data/local/tmp/abrp-daemon.sh"
    private const val LOG_PATH    = "/data/local/tmp/abrp-daemon.log"
    private const val PID_PATTERN = "abrp-daemon.sh"  // for pkill -f

    private val DAEMON_SCRIPT = """#!/system/bin/sh
# ABRP Telemetry resurrector — runs as shell (UID 2000).
# See DaemonLauncher.kt for why.
LOG=$LOG_PATH
PKG=com.abrp.telemetry
echo "${'$'}(date '+%F %T') daemon up pid=${'$'}${'$'}" >> "${'$'}LOG"
while true; do
    if ! pidof "${'$'}PKG" >/dev/null 2>&1; then
        echo "${'$'}(date '+%F %T') ${'$'}PKG not running — am start WakeActivity" >> "${'$'}LOG" 2>&1
        am start --user 0 -n "${'$'}PKG/.WakeActivity" >> "${'$'}LOG" 2>&1
    fi
    sleep 30
done
""".trimIndent()

    /**
     * Push a fresh script, kill any prior daemon, and start a new one detached.
     * Best-effort: any failure is logged via [DebugLog] and silently swallowed —
     * telemetry still works for the current drive, just won't auto-resume the
     * next ignition cycle until this succeeds.
     */
    fun installAndStart(context: Context) {
        DebugLog.log("Daemon", "installAndStart: connecting via AdbTunnel")
        runCatching {
            AdbTunnel.connect(context).use { dadb ->
                // Stop any previous daemon. `; true` so a missing pkill doesn't fail us.
                dadb.shell("pkill -9 -f $PID_PATTERN; true")
                // Push fresh script — 0755 so shell can exec.
                val temp = File.createTempFile("abrp-daemon", ".sh", context.cacheDir).apply {
                    writeText(DAEMON_SCRIPT)
                }
                try {
                    dadb.push(temp, SCRIPT_PATH, "755".toInt(8), System.currentTimeMillis())
                } finally {
                    temp.delete()
                }
                // Start detached. nohup + redirects + & so it survives the ADB
                // session disconnect and doesn't keep the shell channel open.
                val res = dadb.shell("nohup sh $SCRIPT_PATH </dev/null >/dev/null 2>&1 &")
                DebugLog.log("Daemon", "started (shell exit=${res.exitCode})")
            }
        }.onFailure { e ->
            DebugLog.log("Daemon", "install FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Kill the daemon and remove all traces from /data/local/tmp. Called from
     * TelemetryService.stop() so a user-initiated Stop fully tears down.
     */
    fun uninstall(context: Context) {
        DebugLog.log("Daemon", "uninstall: tearing down")
        runCatching {
            AdbTunnel.connect(context).use { dadb ->
                dadb.shell("pkill -9 -f $PID_PATTERN; true")
                dadb.shell("rm -f $SCRIPT_PATH $LOG_PATH")
                DebugLog.log("Daemon", "uninstalled — script and log removed")
            }
        }.onFailure { e ->
            DebugLog.log("Daemon", "uninstall FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

}
