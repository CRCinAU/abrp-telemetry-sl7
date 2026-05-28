package com.abrp.telemetry

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Auto-update. Checks GitHub Releases, verifies the downloaded APK is signed with
 * the same cert as the installed app, and installs it via `pm install -r` over the
 * same dadb tunnel [DaemonLauncher] uses for the resurrector. The post-install kill
 * is healed by the daemon, which `am start`s WakeActivity once the new APK is live.
 *
 * Safety layers:
 *  - Per-app opt-in pref (auto_update, defaults true).
 *  - In-app cert match check before pushing the APK to /data/local/tmp.
 *  - `pm install -r` also enforces signature continuity inside the package manager.
 *  - Install is gated on the vehicle being parked, so we don't drop telemetry
 *    frames in the middle of a drive.
 */
object Updater {
    private const val RELEASES_API = "https://api.github.com/repos/CRCinAU/abrp-telemetry-sl7/releases/latest"
    private const val ASSET_SUFFIX = "-debug.apk"
    private const val REMOTE_APK_PATH = "/data/local/tmp/abrp-update.apk"
    private const val CHECK_COOLDOWN_MS = 24L * 60 * 60 * 1000  // 24h
    private const val PREFS = "abrp_prefs"
    private const val PREF_AUTO_UPDATE = "auto_update"
    private const val PREF_LAST_CHECK_MS = "updater_last_check_ms"
    // Phase + latest tag are persisted separately so the rendered "Installed: v…"
    // prefix always reflects the *currently running* BuildConfig.VERSION_NAME on
    // each render — even when the persisted state was written by an older APK.
    private const val PREF_LAST_PHASE = "updater_last_phase"
    private const val PREF_LAST_LATEST = "updater_last_latest"
    const val DEFAULT_AUTO_UPDATE = true

    /** Broadcast action carrying [EXTRA_STATUS] — MainActivity renders it under
     *  the auto-update toggle. Persisted to prefs too so a cold-start activity
     *  has something to display. */
    const val ACTION_STATUS = "com.abrp.telemetry.UPDATER_STATUS"
    const val EXTRA_STATUS  = "status_text"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private data class GhAsset(
        val name: String,
        @SerializedName("browser_download_url") val url: String,
    )

    private data class GhRelease(
        @SerializedName("tag_name") val tag: String,
        val assets: List<GhAsset> = emptyList(),
    )

    /**
     * Best-effort update check. Safe to call on a background thread.
     *
     * The check is rate-limited to once every 24 hours by a timestamp persisted
     * to SharedPreferences (so re-opening the app doesn't reset it). Pass
     * [force]=true to bypass the cooldown — the UI toggle uses this when the
     * user flips auto-update on, since a deliberate gesture should re-check
     * straight away.
     *
     * Silent no-op when:
     *   - auto-update is disabled by pref
     *   - we already checked within [CHECK_COOLDOWN_MS] (unless [force])
     *   - no newer release is available
     *   - the vehicle isn't parked (we defer; the cooldown timestamp isn't
     *     bumped so the next call retries while still parked)
     */
    @Synchronized
    fun maybeUpdate(context: Context, isParked: Boolean, force: Boolean = false) {
        val current = BuildConfig.VERSION_NAME
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_AUTO_UPDATE, DEFAULT_AUTO_UPDATE)) {
            report(context, null, "auto-update off")
            return
        }
        val now = System.currentTimeMillis()
        val lastCheckMs = prefs.getLong(PREF_LAST_CHECK_MS, 0L)
        if (!force && now - lastCheckMs < CHECK_COOLDOWN_MS) {
            val hoursLeft = (CHECK_COOLDOWN_MS - (now - lastCheckMs)) / (60 * 60 * 1000)
            DebugLog.log("Updater", "skipping — within 24h cooldown (~${hoursLeft}h left)")
            return  // don't churn the visible status text for routine cooldown skips
        }

        report(context, null, "checking…")
        val release = runCatching { fetchLatest() }.getOrElse {
            report(context, null, "check failed: ${it.message}")
            return
        }

        val latestTag = release.tag.removePrefix("v")
        if (!isNewer(latestTag, current)) {
            report(context, latestTag, "up to date")
            recordCheck(prefs, now)
            return
        }

        if (!isParked) {
            report(context, latestTag, "waiting until parked")
            return
        }

        val asset = release.assets.firstOrNull { it.name.endsWith(ASSET_SUFFIX) }
        if (asset == null) {
            report(context, latestTag, "no $ASSET_SUFFIX asset in release ${release.tag}")
            recordCheck(prefs, now)
            return
        }

        report(context, latestTag, "downloading…")
        val apk = runCatching { downloadApk(context, asset.url) }.getOrElse {
            report(context, latestTag, "download failed: ${it.message}")
            return
        }

        report(context, latestTag, "verifying…")
        if (!signatureMatches(context, apk)) {
            report(context, latestTag, "signature mismatch — refusing to install")
            apk.delete()
            return
        }

        report(context, latestTag, "installing…")
        runCatching { installViaShell(context, apk) }
            .onSuccess { report(context, latestTag, "installed, restarting") }
            .onFailure { report(context, latestTag, "install failed: ${it.message}") }
        apk.delete()
        recordCheck(prefs, now)
    }

    /** Format the right-hand "Latest Release" line. When the latest tag isn't
     *  known yet (very first check, or fetch failed) the phase replaces the
     *  version slot — e.g. "Latest Release: checking…" — so we don't have to
     *  print a meaningless "v—" placeholder. */
    private fun latestLine(latest: String?, phase: String): String =
        if (latest != null) "Latest Release: v$latest · $phase"
        else                "Latest Release: $phase"

    /** Persist the (latest, phase) pair and broadcast just the right-hand line.
     *  The "Installed:" half is rendered by the UI from BuildConfig directly. */
    private fun report(context: Context, latest: String?, phase: String) {
        val text = latestLine(latest, phase)
        DebugLog.log("Updater", text)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_PHASE, phase)
            .putString(PREF_LAST_LATEST, latest ?: "")
            .apply()
        context.sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, text))
    }

    /** The most recent "Latest Release: …" line, reconstructed from prefs so a
     *  cold start has something to display before the next periodic check
     *  fires. Empty if we've never checked. */
    fun lastLatestLine(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val phase = prefs.getString(PREF_LAST_PHASE, "").orEmpty()
        if (phase.isBlank()) return ""
        val latest = prefs.getString(PREF_LAST_LATEST, "").orEmpty().ifBlank { null }
        return latestLine(latest, phase)
    }

    private fun recordCheck(prefs: android.content.SharedPreferences, now: Long) {
        prefs.edit().putLong(PREF_LAST_CHECK_MS, now).apply()
    }

    private fun fetchLatest(): GhRelease {
        val req = Request.Builder().url(RELEASES_API)
            .header("User-Agent", "abrp-telemetry-sl7-updater")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw RuntimeException("empty body")
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(120)}")
            return gson.fromJson(body, GhRelease::class.java)
                ?: throw RuntimeException("malformed JSON")
        }
    }

    private fun downloadApk(context: Context, url: String): File {
        val out = File(context.cacheDir, "abrp-update.apk")
        val req = Request.Builder().url(url)
            .header("User-Agent", "abrp-telemetry-sl7-updater")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("empty body")
            out.outputStream().use { dst -> body.byteStream().copyTo(dst) }
        }
        return out
    }

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun signatureMatches(context: Context, apk: File): Boolean {
        return runCatching {
            val pm = context.packageManager
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            val downloaded = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                ?: return@runCatching false
            val a = installed.signatures?.firstOrNull() ?: return@runCatching false
            val b = downloaded.signatures?.firstOrNull() ?: return@runCatching false
            a == b
        }.getOrDefault(false)
    }

    private fun installViaShell(context: Context, apk: File) {
        AdbTunnel.connect(context).use { dadb ->
            dadb.push(apk, REMOTE_APK_PATH, "644".toInt(8), System.currentTimeMillis())
            // Chain pm install + cleanup so the temp file is removed even if pm fails
            // or if our app process is killed mid-install (the shell session lives
            // under UID 2000 and keeps running after our UID dies). pm prints
            // "Success" on success and "Failure [REASON]" otherwise; both routes
            // exit 0 on older Android, so check stdout too.
            val res = dadb.shell(
                "pm install -r $REMOTE_APK_PATH; rc=\$?; rm -f $REMOTE_APK_PATH; exit \$rc"
            )
            val out = res.allOutput
            if (res.exitCode != 0 || !out.contains("Success")) {
                throw RuntimeException("pm install (exit=${res.exitCode}): ${out.trim().take(200)}")
            }
        }
    }

    /** Returns true iff [latest] is strictly newer than [current], treating
     *  git-describe-style suffixes as "this many commits past the named tag".
     *
     *  Examples:
     *      isNewer("1.0.5",        "1.0.4-3-gabc") = true   (next release wins)
     *      isNewer("1.0.4",        "1.0.4-3-gabc") = false  (local is past v1.0.4)
     *      isNewer("1.0.4-3-gabc", "1.0.4-3-gabc") = false  (equal)
     *      isNewer("1.0.4-5-gxyz", "1.0.4-3-gabc") = true   (more commits past tag)
     *  Either side unparseable → false (conservative — don't update). */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = parseDescribe(latest) ?: return false
        val c = parseDescribe(current) ?: return false
        // Base semver first — tag with a higher base wins outright even against
        // a dev build of an older tag.
        for (i in 0 until maxOf(l.base.size, c.base.size)) {
            val li = l.base.getOrElse(i) { 0 }
            val ci = c.base.getOrElse(i) { 0 }
            if (li != ci) return li > ci
        }
        // Same base: more commits past the tag = newer.
        return l.commitsAhead > c.commitsAhead
    }

    private data class Describe(val base: IntArray, val commitsAhead: Int)

    /** Parse "1.0.4", "v1.0.4", "1.0.4-3-gabc", "1.0.4-3" into (base, commits).
     *  Anything else (SHA-only "gabc123", "dev", malformed) returns null. */
    private fun parseDescribe(s: String): Describe? {
        val trimmed = s.removePrefix("v")
        val firstDash = trimmed.indexOf('-')
        val basePart: String
        val ahead: Int
        if (firstDash < 0) {
            basePart = trimmed
            ahead = 0
        } else {
            basePart = trimmed.substring(0, firstDash)
            // Tail is "3-gabc" or just "3" — first dash-delimited token must
            // parse as a non-negative int (the commit count past the tag).
            val tail = trimmed.substring(firstDash + 1)
            val nextDash = tail.indexOf('-')
            val countStr = if (nextDash < 0) tail else tail.substring(0, nextDash)
            ahead = countStr.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
        val ints = basePart.split(".").map { it.toIntOrNull() ?: return null }
        if (ints.isEmpty()) return null
        return Describe(ints.toIntArray(), ahead)
    }
}
