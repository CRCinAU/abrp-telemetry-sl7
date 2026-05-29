package com.abrp.telemetry

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Shared dadb connection helper. Both [DaemonLauncher] (resurrector install/uninstall)
 * and [Updater] (auto-update via `pm install -r`) need a tunnel to the head unit's
 * own adbd at 127.0.0.1:5555 as the shell user; consolidating the key handling here
 * means a single keypair under filesDir is reused across both, so any one-time auth
 * approval the head unit might require only happens once.
 */
internal object AdbTunnel {
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    private const val PRIVATE_KEY = "adbkey"
    private const val PUBLIC_KEY = "adbkey.pub"

    /** Open a fresh ADB session. Caller is responsible for [Dadb.close]. */
    fun connect(context: Context): Dadb {
        val keyPair = getOrCreateKeyPair(context)
        return Dadb.create(ADB_HOST, ADB_PORT, keyPair)
    }

    // Synchronized so concurrent callers (e.g. DaemonLauncher.installAndStart on
    // its own Thread and Updater.installViaShell on the send-tick Thread) can't
    // both observe absent files and both call generate(), racing on disk writes
    // and producing a mismatched (priv, pub) pair that breaks ADB auth.
    @Synchronized
    private fun getOrCreateKeyPair(context: Context): AdbKeyPair {
        val priv = File(context.filesDir, PRIVATE_KEY)
        val pub  = File(context.filesDir, PUBLIC_KEY)
        if (!priv.exists() || !pub.exists()) {
            DebugLog.log("AdbTunnel", "generating ADB keypair under filesDir")
            AdbKeyPair.generate(priv, pub)
        }
        return AdbKeyPair.read(priv, pub)
    }
}
