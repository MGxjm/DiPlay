package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Stock-map handoff for the validated DiLink 4 private-display route. All state belongs to worker. */
object BydOemClusterNavi {
    internal const val STOCK_MAP = "com.byd.automap"
    internal const val STOCK_MAP_CLUSTER_ACTIVITY = "$STOCK_MAP.extra.MeterActivity"
    private const val TAG = "DiPlay-BYD-OemCluster"
    private const val JOURNAL = "restore_journal"

    /** A command that never returns (adb shell stuck) must not block the projection launch forever. */
    private const val COMMAND_TIMEOUT_SECONDS = 8L
    private val shell = BydAdbShell(TAG)
    private val worker = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "diplay-oem-cluster").apply { isDaemon = true }
    }
    private var session: OemClusterHoldSession? = null

    fun applicable(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(STOCK_MAP, 0) }.isSuccess

    /**
     * Re-enable the stock map (component and package) and clear any recovery journal. Blocking, for
     * the ADB routing worker only; never call from the Android main thread.
     */
    fun restoreStockMap(context: Context) {
        val app = context.applicationContext
        worker.execute {
            runCatching { state(app).restoreStockMap() }
                .onFailure { Log.w(TAG, "Stock-map restore failed", it) }
        }
    }

    /**
     * Blocking, for the ADB routing worker only; never call from the Android main thread. Prepares
     * the DiLink 4 projection: re-enables the whole stock map so its cluster activity rebuilds the
     * instrument's projection window, forces Small screen navi, and opens the half-screen projection
     * (17). Only then does DiPlay launch its own projection; the stock map is disabled again once
     * that projection is confirmed (see [disableAfterProjection]).
     */
    fun primeForLaunch(context: Context, current: () -> Boolean): Boolean {
        val app = context.applicationContext
        return runCatching {
            worker.submit<Boolean> {
                if (!applicable(app) || !current()) return@submit false
                if (!state(app).restoreStockMap()) return@submit false
                if (!current()) return@submit false
                shell.run(app, BydClusterNaviMode.selectSmallCommand())
                BydDiLink3ClusterMode.accepted(shell.run(app, BydDiLink3ClusterMode.Mode.PROJECTION.command))
            }.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "Stock-map priming refused", it) }.getOrDefault(false)
    }

    /**
     * DiPlay's projection is confirmed on the cluster: disable the whole stock map package so it can
     * no longer grab the projection surface back. Journaled, so [release] restores the original state
     * when the session ends, or at the next app launch after a crash.
     */
    fun disableAfterProjection(context: Context, lease: String) {
        val app = context.applicationContext
        worker.execute {
            runCatching { state(app).disablePackage(lease) { true } }
                .onFailure { Log.w(TAG, "Stock-map disable failed", it) }
        }
    }

    /** Always enqueue, even when the disable has not saved its journal yet. */
    fun release(context: Context, lease: String? = null) {
        val app = context.applicationContext
        worker.execute {
            val pendingLease = lease ?: runCatching { journal(app)?.lease }.getOrNull()
            val restored = runCatching { state(app).release(pendingLease) }
                .onFailure { Log.w(TAG, "Stock-map restore will retry", it) }.getOrDefault(false)
            if (!restored) worker.schedule({ release(app, pendingLease) }, 30, TimeUnit.SECONDS)
        }
    }

    fun restoreIfNeeded(context: Context) = release(context)

    private fun state(app: Context): OemClusterHoldSession = session ?: OemClusterHoldSession(
        readState = { target -> runCatching {
            if (target == OemClusterHoldSession.Target.PACKAGE)
                app.packageManager.getApplicationEnabledSetting(STOCK_MAP)
            else app.packageManager.getComponentEnabledSetting(ComponentName(STOCK_MAP, STOCK_MAP_CLUSTER_ACTIVITY))
        }.getOrNull() },
        setState = { target, value ->
            val output = shell.run(app, command(target, value) + "; printf '\nDIPLAY_PM_RC:%s\n' \"\$?\"")
            output != null && Regex("(?m)^DIPLAY_PM_RC:0\\s*$").containsMatchIn(output) &&
                !Regex("(?i)error|exception|permission\\s*deni(?:al|ed)").containsMatchIn(output)
        },
        loadJournal = { journal(app) },
        saveJournal = { next ->
            val edit = prefs(app).edit()
            if (next == null) edit.remove(JOURNAL)
            else edit.putString(JOURNAL, "${next.target.name}:${next.originalState}:${next.lease}")
            edit.commit()
        },
    ).also { session = it }

    private fun journal(context: Context): OemClusterHoldSession.Journal? {
        val value = prefs(context).getString(JOURNAL, null) ?: return null
        val parts = value.split(':')
        check(parts.size == 3 && parts[1].toIntOrNull() in 0..4) { "Invalid stock-map recovery journal" }
        return OemClusterHoldSession.Journal(OemClusterHoldSession.Target.valueOf(parts[0]), parts[1].toInt(), parts[2])
    }

    internal fun command(target: OemClusterHoldSession.Target, state: Int): String {
        val operation = when (state) {
            0 -> "default-state"
            1 -> "enable"
            2 -> "disable"
            3 -> "disable-user"
            4 -> "disable-until-used"
            else -> error("Invalid OEM component state")
        }
        val component = if (target == OemClusterHoldSession.Target.PACKAGE) STOCK_MAP
            else "$STOCK_MAP/$STOCK_MAP_CLUSTER_ACTIVITY"
        return "pm $operation --user 0 $component"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("diplay_oem_cluster", Context.MODE_PRIVATE)
}
