package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional, needs ADB over network: the iPhone draws and streams the cluster map for the whole
 * session, but the cluster shows it only in Small and Full screen navi. DiPlay reads the mode the
 * driver picked on the wheel every second and asks the iPhone to stop drawing the map while the
 * cluster hides it (Off, Turn on by navi, where it shows arrows only), and to draw it again when the
 * driver picks Small or Full. Without ADB access, or when the mode cannot be read, the map streams
 * as before.
 */
internal object BydClusterMapPause {
    private const val TAG = "DiPlay-BYD-ClusterMap"
    private const val READ_MILLIS = 1_000L

    private val tickerStarted = AtomicBoolean(false)
    private val shell = BydAdbShell(TAG)
    @Volatile private var context: Context? = null

    // Only the ticker thread touches this, so nothing blocking ever runs under a lock that
    // initialize() or the UI needs.
    private var lastMode: BydClusterNaviMode? = null
    private var mapWasOnCluster = false

    /** Whether DiPlay's map window is on the cluster. */
    @Volatile var clusterMapShown = false

    /** The running CarPlay session, told every second whether the iPhone should draw the cluster map. */
    @Volatile var streamControl: ((Boolean) -> Unit)? = null

    /** Reads the mode over adb. Blocking, and only called on the ticker thread; tests replace it. */
    @Volatile internal var readMode: (Context) -> BydClusterNaviMode? = { app ->
        BydClusterNaviMode.parseRead(shell.run(app, BydClusterNaviMode.READ_COMMAND))
    }

    /** Never waits for the ticker: an adb read in flight does not hold up opening or reconnecting. */
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        if (tickerStarted.compareAndSet(false, true)) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-cluster-map").apply { isDaemon = true }
            }.scheduleAtFixedRate(::tick, READ_MILLIS, READ_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun tick() {
        val app = context ?: return
        val control = streamControl
        val mapOnCluster = control != null && clusterMapShown
        // The wheel mode is read every second, and regardless of the stream-pause setting and of
        // whether the CarPlay map window is currently on the cluster. The projection command has to
        // follow it: Full screen navi only opens the projection once DiPlay sends the full-screen
        // projection (16), and 17 is ignored there, so reading the mode only while the map is on the
        // cluster would leave the dashboard blank in Full. The instrument gates its window on
        // INSTRUMENT_SEND_NAVI_STATUS_SET, which only the stock map used to write, so DiPlay
        // re-announces it on every mode change too (see BydClusterScreenStatus).
        val mode = readMode(app)
        if (mode != lastMode) {
            val previous = lastMode
            lastMode = mode
            Log.i(TAG, "cluster mode ${mode?.label ?: "unknown"}")
            BydClusterScreenStatus.onModeChanged(app, mode)
            // Keep the projection command in sync with the instrument's navi mode: Full screen
            // navi needs the full-screen projection (16), Small screen navi the half-screen one
            // (17). Entering Full also reopens the projection so the instrument lays it out again.
            BydDiLink3ClusterOutput.setInstrumentMode(app, mode)
            if (previous != null && previous != BydClusterNaviMode.FULL && mode == BydClusterNaviMode.FULL) {
                BydDiLink3ClusterOutput.refreshProjection(app)
            }
        }
        if (!mapOnCluster) {
            control?.invoke(true)
            // Re-announce the instrument state when the map window comes back to the cluster.
            if (mapWasOnCluster) BydClusterScreenStatus.reset()
            mapWasOnCluster = false
            return
        }
        mapWasOnCluster = true
        if (!BydOutputSettings.clusterStreamPause(app)) {
            control(true)
            return
        }
        // An unknown mode keeps the map streaming, as without ADB.
        control(mode?.showsMap != false)
    }
}
