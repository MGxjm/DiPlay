package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.security.MessageDigest

/** Ordinary-app IPC to the real stock receiver. No shell, local socket or permission grant. */
internal class BydStandaloneHudOutput private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("byd_standalone_hud", Context.MODE_PRIVATE)
    private val session = BydStandaloneSession(
        send = { packet ->
            app.sendBroadcast(Intent("byd.hud.NAVIGATION").setComponent(TARGET)
                .putExtra("normal", packet).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
            Log.d(TAG, "dispatch uid=${Process.myUid()} bytes=${packet.split(',').size}")
        },
        rememberPendingClear = { pending ->
            check(prefs.edit().putBoolean("pending_clear", pending).commit()) { "Cannot persist HUD cleanup" }
        },
        needsRecovery = prefs.getBoolean("pending_clear", false),
    )

    init {
        Log.i(TAG, "Standalone navigation ready uid=${Process.myUid()} helper=none")
        // Retain the journal if dispatch fails; the next scheduled tick retries.
        runCatching { session.clear() }.onFailure { Log.w(TAG, "Startup clear will retry", it) }
    }

    fun update(icon: Int, exit: Int, distanceMeters: Int, road: String) =
        session.update(icon, exit, distanceMeters, road)
    fun showText(text: String) = session.showText(text)
    fun clear() = session.clear()

    companion object {
        private const val TAG = "BYD-Standalone-Live"
        private val TARGET = ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN")
        @Volatile var syntheticHold = false

        fun create(context: Context): BydStandaloneHudOutput? =
            if (available(context)) BydStandaloneHudOutput(context) else null

        fun diagnostics(context: Context): String = buildString {
            appendLine("standaloneHudAvailable=${available(context)} sdk=${Build.VERSION.SDK_INT}")
            appendLine("firmware=${Build.FINGERPRINT}")
            runCatching {
                val info = context.packageManager.getPackageInfo(TARGET.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val receiver = context.packageManager.getReceiverInfo(TARGET, 0)
                appendLine("receiver=${TARGET.flattenToString()} version=${info.longVersionCode} system=${(info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) != 0}")
                appendLine("receiverEnabled=${receiver.enabled} exported=${receiver.exported} permission=${receiver.permission}")
                info.signingInfo?.apkContentsSigners?.forEach { signer ->
                    appendLine("signerSha256=" + MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 255) })
                }
            }.onFailure { appendLine("receiverMetadataUnavailable=${it.javaClass.simpleName}") }
        }

        /**
         * Enable HUD output on any head unit that exposes the open BYD HUD receiver. The firmware
         * fingerprint, app allowlist, receiver version, signing certificate, system-app flag,
         * receiver permission and SDK checks have all been lifted so HUD song/lyrics work across
         * DiLink generations without a retest per firmware. Only the functional contract remains:
         * the receiver element must exist, be enabled and exported so the navigation broadcast
         * can reach it.
         */
        fun available(context: Context): Boolean = runCatching {
            context.packageManager.getReceiverInfo(TARGET, 0).let { it.enabled && it.exported }
        }.getOrDefault(false)
    }
}
