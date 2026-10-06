package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors

/**
 * The instrument's projection-window state, `INSTRUMENT_SEND_NAVI_STATUS_SET`. BYD's own map app
 * writes it from `MeterActivity.handleMeterType` (via `MeterExtKt.generateScreenStatus`) whenever
 * the driver changes the cluster navi mode: Small opens the small projection window, Full opens the
 * full one, Off/Turn on by navi close it. The instrument native side gates the projection on it.
 *
 * When DiPlay holds the stock map disabled (`BydOemClusterHold`), nobody writes it anymore: the
 * window for the mode the stock map last announced stays latched (so that mode keeps working),
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
 * Writes `INSTRUMENT_SEND_NAVI_STATUS_SET` like the stock map app does (needs ADB over network:
 * apps without a BYD signature cannot reach the instrument, the adb shell user can — the same
 * `app_process` route as [BydClusterSong]). Writes happen on mode changes only, de-duplicated, on a
 * background thread; the instrument keeps the state latched, so there is nothing to refresh.
 */
internal object BydClusterScreenStatus {
    private const val TAG = "DiPlay-BYD-ScreenStatus"

    private val shell = BydAdbShell(TAG)
    private val writer = Executors.newSingleThreadExecutor {
        Thread(it, "diplay-cluster-screen-status").apply { isDaemon = true }
    }

    // Writer thread + ticker read; only used to skip duplicate writes of the same state.
    @Volatile private var lastWritten: Int? = null

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
        val apk = app.applicationInfo.sourceDir
        val output = shell.run(app, "CLASSPATH=$apk app_process /system/bin ${BydClusterScreenStatusTool::class.java.name} ${status.code}")
            ?: return
        val failed = output.lineSequence().map { it.trim() }.filter { it.contains('=') }
            .any { line -> line.substringAfter('=').trim().toIntOrNull() != 0 }
        if (failed) {
            Log.w(TAG, "screen status write failed: ${output.trim().take(160)}")
        } else {
            lastWritten = status.code
            Log.i(TAG, "instrument screen status -> ${status.name}")
        }
    }
}

/**
 * Runs under the head unit's adb shell through app_process, not in DiPlay: writes
 * `INSTRUMENT_SEND_NAVI_STATUS_SET` (device 1007) with `BYDAutoInstrumentDevice.set`, the same
 * transaction the stock map's `BydAutoSettingProxy.setEventValue` issues. The feature id is
 * resolved by name at runtime — `BYDAutoFeatureIds` is a compile-time stub in app dex, its values
 * only exist in the platform jars on the head unit. Argument: the status code. Prints "status=N";
 * 0 is success.
 */
object BydClusterScreenStatusTool {
    private const val DEVICE = 1007

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            write(args)
        } catch (error: Throwable) {
            println("status=ERR ${describe(error)}")
        } finally {
            // ActivityThread leaves threads behind; without this the shell command would not return.
            System.exit(0)
        }
    }

    @SuppressLint("PrivateApi")
    private fun write(args: Array<String>) {
        val status = args.getOrNull(0)?.toIntOrNull()
        if (status == null) {
            println("status=ERR no status argument")
            return
        }
        runCatching { android.os.Looper.prepareMainLooper() }
        val thread = Class.forName("android.app.ActivityThread")
        val main = thread.getMethod("systemMain").invoke(null)
        val context = thread.getMethod("getSystemContext").invoke(main)
        val deviceClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        // getInstance checks BYDAUTO_INSTRUMENT_COMMON on the caller's side only; autoservice itself
        // accepts the shell user, so build the device the way getInstance does.
        val device = try {
            deviceClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (_: InvocationTargetException) {
            deviceClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        }
        val valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue")
        val value = valueClass.getConstructor().newInstance()
        runCatching { valueClass.getField("intValue").setInt(value, status) }
            .onFailure { valueClass.getField("intArrayValue").set(value, intArrayOf(status)) }
        val result = device.javaClass.getMethod("set", IntArray::class.java, valueClass)
            .invoke(device, intArrayOf(featureId()), value)
        println("status=$result")
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
