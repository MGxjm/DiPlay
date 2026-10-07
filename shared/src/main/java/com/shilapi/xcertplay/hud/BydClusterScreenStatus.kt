package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * The instrument's projection-window state, `INSTRUMENT_SEND_NAVI_STATUS_SET`. BYD's own map app
 * writes it from `MeterActivity.handleMeterType` (via `MeterExtKt.generateScreenStatus`) whenever
 * the driver changes the cluster navi mode: Small opens the small projection window, Full opens the
 * full one, Off/Turn on by navi close it. The instrument native side gates the projection on it.
 *
 * When DiPlay disables the stock map once its projection is confirmed, nobody writes it anymore:
 * the window for the mode the stock map last announced stays latched (so that mode keeps working),
 * while every other mode shows nothing — switching to Full on the wheel leaves the dashboard
 * blank until something re-announces the state. DiPlay writes it itself, from the same navi-mode
 * ticker that already reads the wheel mode.
 *
 * Values are the dex constants of the stock map app, not guesses:
 * `NaviScreenStatus{UNKNOWN=0, START_SMALL=1, START_FULL=2, STOP_SCREEN=3}` and
 * `generateScreenStatus`: small(3)->1, full(4)->2, close(1)/simple(2)->3, default(0)->0.
 */
internal enum class NaviScreenStatus(val code: Int) {
    UNKNOWN(0),
    START_SMALL(1),
    START_FULL(2),
    STOP(3);

    companion object {
        /** What the stock map app writes when the driver picks [mode]; null when the mode is unreadable. */
        fun fromMode(mode: BydClusterNaviMode?): NaviScreenStatus? = when (mode) {
            BydClusterNaviMode.SMALL -> START_SMALL
            BydClusterNaviMode.FULL -> START_FULL
            BydClusterNaviMode.OFF, BydClusterNaviMode.TURN_ON_BY_NAVI -> STOP
            null -> null
        }
    }
}

/**
 * Writes `INSTRUMENT_SEND_NAVI_STATUS_SET` like the stock map app does. The write goes through the
 * `autoservice` binder as the adb shell user (`service call autoservice 6`, setInt on instrument
 * device 1007): `BYDAutoInstrumentDevice.set` is gated on the signature-level
 * `android.permission.BYDAUTO_INSTRUMENT_SET`, which user 2000 does not hold, while autoservice
 * accepts the caller. The feature id is not in DiPlay's dex — [BydClusterScreenStatusTool] resolves
 * it on the head unit once per session. Writes happen on mode changes only, de-duplicated, on a
 * background thread; the instrument keeps the state latched, so there is nothing to refresh.
 */
internal object BydClusterScreenStatus {
    private const val TAG = "DiPlay-BYD-ScreenStatus"
    private const val INSTRUMENT_DEVICE = 1007
    private const val SET_INT = 6

    private val shell = BydAdbShell(TAG)
    private val writer = Executors.newSingleThreadExecutor {
        Thread(it, "diplay-cluster-screen-status").apply { isDaemon = true }
    }

    // Writer thread + ticker read; only used to skip duplicate writes of the same state.
    @Volatile private var lastWritten: Int? = null

    /** Resolved once on the head unit; the constant only exists in the platform jars. */
    @Volatile private var resolvedFeatureId: Int? = null

    /** The driver picked a new cluster navi mode; re-announce the projection state for it. */
    fun onModeChanged(app: Context, mode: BydClusterNaviMode?) {
        if (!BydOutputSettings.clusterScreenStatus(app)) return
        val status = NaviScreenStatus.fromMode(mode) ?: return
        if (status.code == lastWritten) return
        writer.execute { write(app, status) }
    }

    /** The map left the cluster (session end, map off): drop the de-dup state so the next showing re-announces. */
    fun reset() {
        lastWritten = null
    }

    private fun write(app: Context, status: NaviScreenStatus) {
        val featureId = featureId(app) ?: return
        val output = shell.run(
            app,
            "service call autoservice $SET_INT i32 $INSTRUMENT_DEVICE i32 $featureId i32 ${status.code}",
        ) ?: return
        // A refused transaction answers with an exception, or with no Parcel at all. The accepted
        // reply is the raw status Parcel, which the bring-up log keeps verbatim.
        val accepted = BydParcel.words(output).isNotEmpty() &&
            !Regex("(?i)exception|denied|error").containsMatchIn(output)
        if (!accepted) {
            Log.w(TAG, "screen status write refused: ${output.trim().take(160)}")
            return
        }
        lastWritten = status.code
        Log.i(TAG, "instrument screen status -> ${status.name} (${output.trim().take(80)})")
    }

    /** `INSTRUMENT_SEND_NAVI_STATUS_SET`, resolved on the head unit through [BydClusterScreenStatusTool]. */
    private fun featureId(app: Context): Int? {
        resolvedFeatureId?.let { return it }
        val apk = app.applicationInfo.sourceDir
        val output = shell.run(app, "CLASSPATH=$apk app_process /system/bin ${BydClusterScreenStatusTool::class.java.name}")
            ?: return null
        val prefix = BydClusterScreenStatusTool.FEATURE_ID_PREFIX
        val id = output.lineSequence().map { it.trim() }
            .firstNotNullOfOrNull { line ->
                line.removePrefix(prefix).toIntOrNull().takeIf { line.startsWith(prefix) }
            }
        if (id == null) {
            Log.w(TAG, "navi status feature id unresolved: ${output.trim().take(160)}")
            return null
        }
        Log.i(TAG, "navi status feature id $id")
        resolvedFeatureId = id
        return id
    }
}

/**
 * Resolves `INSTRUMENT_SEND_NAVI_STATUS_SET` (device 1007) on the head unit and prints
 * `naviStatusId=<n>`; [BydClusterScreenStatus] then writes it through `service call autoservice 6`.
 *
 * Runs under the head unit's adb shell through app_process, not in DiPlay: `BYDAutoFeatureIds` is a
 * compile-time stub in DiPlay's dex, its values only exist in the platform jars, and the app_process
 * class loader reaches them. Reading the constant is all the tool does — the write cannot go through
 * `BYDAutoInstrumentDevice.set` here, because that is gated on the signature-level
 * `android.permission.BYDAUTO_INSTRUMENT_SET`, which user 2000 does not hold (verified on DiLink 4.0:
 * "Neither user 2000 nor current process has android.permission.BYDAUTO_INSTRUMENT_SET"), while
 * autoservice accepts the caller.
 */
object BydClusterScreenStatusTool {
    const val FEATURE_ID_PREFIX = "naviStatusId="

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            println("$FEATURE_ID_PREFIX${featureId()}")
        } catch (error: Throwable) {
            println("${FEATURE_ID_PREFIX}ERR ${describe(error)}")
        } finally {
            System.exit(0)
        }
    }

    /** `BYDAutoFeatureIds$Instrument.INSTRUMENT_SEND_NAVI_STATUS_SET`, falling back to the outer class. */
    private fun featureId(): Int {
        val idsClass = try {
            Class.forName("android.hardware.bydauto.BYDAutoFeatureIds\$Instrument")
        } catch (_: ClassNotFoundException) {
            Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
        }
        return idsClass.getField("INSTRUMENT_SEND_NAVI_STATUS_SET").getInt(null)
    }

    private fun describe(error: Throwable): String {
        val cause = error.cause ?: error
        return cause.javaClass.name + (cause.message?.let { ": $it" } ?: "")
    }
}
