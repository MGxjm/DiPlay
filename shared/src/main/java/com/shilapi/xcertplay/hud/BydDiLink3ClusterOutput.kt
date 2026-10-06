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
    @Volatile private var desiredMode: BydDiLink3ClusterMode.Mode? = null
    private var session: DiLink3ClusterModeSession? = null

    /** Also called when the setting is off, so an interrupted output is always recoverable. */
    fun restoreIfNeeded(appContext: Context) {
        initialize(appContext)
        requestApply()
    }

    fun setDesired(appContext: Context, mapShown: Boolean, guidanceActive: Boolean) {
        desiredMode = when {
            mapShown -> BydDiLink3ClusterMode.Mode.PROJECTION
            guidanceActive -> BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION
            else -> null
        }
        initialize(appContext)
        requestApply()
    }

    fun prepareDisplay(appContext: Context, displayPresent: () -> Boolean) {
        initialize(appContext)
        worker.execute {
            val app = context ?: return@execute
            val prepared = runCatching {
                state(app).prepareDisplay(displayPresent, { desiredMode },
                    { BydOutputSettings.enabled(app) }, { Thread.sleep(3_000L) })
            }.onFailure { Log.w(TAG, "Cluster preparation will recover", it) }.getOrDefault(false)
            Log.i(TAG, "DiLink 3 cluster display prepared=$prepared")
        }
    }

    /**
     * The driver changed the cluster navi mode on the wheel. The instrument keeps the projection
     * window in its previous layout until it is opened again, so close it and re-apply the
     * current mode; reopening makes the instrument take the newly selected layout. Runs on both
     * DiLink 3 and DiLink 4 head units; the ADB route does not suppress these commands.
     */
    fun refreshProjection(appContext: Context) {
        initialize(appContext)
        worker.execute {
            val app = context ?: return@execute
            val mode = desiredMode ?: return@execute
            runCatching { state(app).refresh(mode) }
                .onFailure { Log.w(TAG, "Cluster projection refresh will retry", it) }
                .onSuccess { refreshed ->
                    if (!refreshed) Log.w(TAG, "Cluster projection refresh pending recovery/retry")
                }
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
            val attempted = desiredMode
            try { applyLatest() }
            finally {
                applyQueued.set(false)
                // A request that changed during a blocking ADB call must still be applied.
                if (attempted != desiredMode) requestApply()
            }
        }
    }

    private fun applyLatest() {
        val app = context ?: return
        runCatching { state(app).apply(desiredMode) }
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
            projectionStillWanted = {
                desiredMode == BydDiLink3ClusterMode.Mode.PROJECTION && !adbClusterRouteSelected(app)
            },
        ).also { session = it }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("diplay_dilink3_cluster", Context.MODE_PRIVATE)
}
