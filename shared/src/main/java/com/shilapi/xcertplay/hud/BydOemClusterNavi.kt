package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in stock-map hold for the validated private-display route. All state belongs to worker. */
object BydOemClusterNavi {
    internal const val STOCK_MAP = "com.byd.automap"
    internal const val STOCK_MAP_CLUSTER_SERVICE = "$STOCK_MAP.service.VirtualBindService"
    private const val TAG = "DiPlay-BYD-OemCluster"
    private const val JOURNAL = "restore_journal"
    private const val PROJECTION_JOURNAL = "dilink4_projection_recovery"
    private val shell = BydAdbShell(TAG)
    private val worker = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "diplay-oem-cluster").apply { isDaemon = true }
    }
    private var session: OemClusterHoldSession? = null
    private var projectionSession: DiLink4ClusterProjectionSession? = null

    fun applicable(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(STOCK_MAP, 0) }.isSuccess

    /** Blocking, for the ADB routing worker only; never call from the Android main thread. */
    fun holdForLaunch(context: Context, lease: String, current: () -> Boolean): Boolean {
        val app = context.applicationContext
        return runCatching {
            worker.submit<Boolean> {
                val mode = BydOutputSettings.oemClusterHold(app)
                (mode == BydOemClusterHold.OFF || applicable(app)) &&
                    state(app).acquire(mode, lease, current)
            }.get()
        }.onFailure { Log.w(TAG, "Stock-map hold refused", it) }.getOrDefault(false)
    }

    /** Switches the container to DiPlay projection only while the stock projection service is held. */
    fun startDiLink4Projection(context: Context, lease: String, current: () -> Boolean): Boolean {
        val app = context.applicationContext
        return runCatching {
            worker.submit<Boolean> {
                val held = journal(app)
                held?.target == OemClusterHoldSession.Target.COMPONENT && held.lease == lease &&
                    current() && projectionState(app).enter()
            }.get()
        }.onFailure { Log.w(TAG, "DiLink 4 projection start failed", it) }.getOrDefault(false)
    }

    /** Always enqueue, even when acquire has not saved its journal yet. */
    fun release(context: Context, lease: String? = null) {
        val app = context.applicationContext
        worker.execute {
            val pendingLease = lease ?: runCatching { journal(app)?.lease }.getOrNull()
            if (!runCatching { projectionState(app).recoverInterrupted() }.getOrDefault(false)) {
                worker.schedule({ release(app, pendingLease) }, 30, TimeUnit.SECONDS)
                return@execute
            }
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
            else app.packageManager.getComponentEnabledSetting(ComponentName(STOCK_MAP, STOCK_MAP_CLUSTER_SERVICE))
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
        afterDisable = { target ->
            if (target == OemClusterHoldSession.Target.COMPONENT) Thread.sleep(1_500L)
        },
    ).also { session = it }

    private fun projectionState(app: Context): DiLink4ClusterProjectionSession =
        projectionSession ?: DiLink4ClusterProjectionSession(
            run = { shell.run(app, it) },
            loadRecovery = { prefs(app).getBoolean(PROJECTION_JOURNAL, false) },
            saveRecovery = { pending ->
                val edit = prefs(app).edit()
                if (pending) edit.putBoolean(PROJECTION_JOURNAL, true)
                else edit.remove(PROJECTION_JOURNAL)
                edit.commit()
            },
        ).also { projectionSession = it }

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
            else "$STOCK_MAP/$STOCK_MAP_CLUSTER_SERVICE"
        return "pm $operation --user 0 $component"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("diplay_oem_cluster", Context.MODE_PRIVATE)
}
