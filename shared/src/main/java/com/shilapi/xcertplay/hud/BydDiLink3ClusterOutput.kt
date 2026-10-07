package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** DiLink 3 cluster modes have their own recovery journal; OEM package holds are independent. */
internal object BydDiLink3ClusterOutput {
    private const val TAG = "DiPlay-BYD-DiLink3"
    private const val JOURNAL = "restore_stock_mode"
    private val shell = BydAdbShell(TAG)
    private val worker = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "diplay-byd-cluster-mode").apply { isDaemon = true }
    }
    private val retryStarted = AtomicBoolean()
    private val applyQueued = AtomicBoolean()
    @Volatile private var context: Context? = null
    @Volatile private var mapShown = false
    @Volatile private var guidanceActive = false
    @Volatile private var instrumentMode: BydClusterNaviMode? = null
    private var session: DiLink3ClusterModeSession? = null

    /** The projection command for the current map/guidance state and instrument navi mode. */
    private fun desiredMode(): BydDiLink3ClusterMode.Mode? = BydDiLink3ClusterMode.desired(
        mapShown, guidanceActive, null, instrumentMode,
    )

    /** Also called when the setting is off, so an interrupted output is always recoverable. */
    fun restoreIfNeeded(appContext: Context) {
        initialize(appContext)
        requestApply()
    }

    fun setDesired(appContext: Context, mapShown: Boolean, guidanceActive: Boolean) {
        this.mapShown = mapShown
        this.guidanceActive = guidanceActive
        initialize(appContext)
        requestApply()
    }

    /**
     * The instrument navi mode the driver picked on the wheel. DiPlay's projection command follows
     * it: Full screen navi needs the full-screen projection (16), Small screen navi uses the
     * half-screen one (17). Called from the cluster-map ticker whenever the mode changes.
     */
    fun setInstrumentMode(appContext: Context, mode: BydClusterNaviMode?) {
        instrumentMode = mode
        initialize(appContext)
        requestApply()
    }

    fun prepareDisplay(appContext: Context, displayPresent: () -> Boolean) {
        initialize(appContext)
        worker.execute {
            val app = context ?: return@execute
            val prepared = runCatching {
                state(app).prepareDisplay(displayPresent, { desiredMode() },
                    { BydOutputSettings.enabled(app) }, { Thread.sleep(3_000L) })
            }.onFailure { Log.w(TAG, "Cluster preparation will recover", it) }.getOrDefault(false)
            Log.i(TAG, "DiLink 3 cluster display prepared=$prepared")
        }
    }

    /**
     * The driver picked Full screen navi on the wheel. The instrument opens its full projection
     * only when DiPlay asks for it (16), so this sends 16 directly: unlike a reopen, it does not
     * close the projection first, which would leave the dashboard blank until something reopened it.
     * Runs on both DiLink 3 and DiLink 4 head units; the ADB route does not suppress these commands.
     */
    fun enterFullScreen(appContext: Context) {
        instrumentMode = BydClusterNaviMode.FULL
        initialize(appContext)
        worker.execute {
            val app = context ?: return@execute
            runCatching { state(app).apply(BydDiLink3ClusterMode.Mode.FULL_PROJECTION) }
                .onFailure { Log.w(TAG, "Full-screen projection will retry", it) }
                .onSuccess { accepted -> if (!accepted) Log.w(TAG, "Full-screen projection pending recovery/retry") }
        }
    }

    private fun initialize(appContext: Context) {
        context = appContext.applicationContext
        if (retryStarted.compareAndSet(false, true)) {
            worker.scheduleWithFixedDelay({ applyLatest() }, 30, 30, TimeUnit.SECONDS)
        }
    }

    private fun requestApply() {
        if (!applyQueued.compareAndSet(false, true)) return
        worker.execute {
            val attempted = desiredMode()
            try { applyLatest() }
            finally {
                applyQueued.set(false)
                // A request that changed during a blocking ADB call must still be applied.
                if (attempted != desiredMode()) requestApply()
            }
        }
    }

    private fun applyLatest() {
        val app = context ?: return
        runCatching { state(app).apply(desiredMode()) }
            .onFailure { Log.w(TAG, "Cluster mode will retry", it) }
            .onSuccess { accepted -> if (!accepted) Log.w(TAG, "Cluster mode pending recovery/retry") }
    }

    private fun state(app: Context): DiLink3ClusterModeSession {
        session?.let { return it }
        val journal = DiLink3ClusterRecoveryJournal(prefs(app).getBoolean(JOURNAL, false)) { pending ->
            val edit = prefs(app).edit()
            if (pending) edit.putBoolean(JOURNAL, true) else edit.remove(JOURNAL)
            edit.commit()
        }
        return DiLink3ClusterModeSession(
            run = { command -> shell.run(app, command) },
            loadRecovery = { journal.pending },
            saveRecovery = journal::save,
        ).also { session = it }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("diplay_dilink3_cluster", Context.MODE_PRIVATE)
}
