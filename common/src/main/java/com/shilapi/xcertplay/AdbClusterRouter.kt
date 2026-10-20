package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.File

/** The projection panel, discovered afresh through the authorized shell by name prefix. */
internal object AdbClusterRouter {
    private const val REPORT = "adb-cluster-route.txt"
    data class Result(val success: Boolean, val report: String)

    /** Known 5/5.1 displays win; the DiLink 4 route may own the measured projection task. */
    fun enabled(context: Context): Boolean {
        if (DiLink51ClusterLayout.supported()) return false
        return AirPlayPersistence.loadAdbClusterEnabled(context) &&
            ClusterMapPresentation.findDisplay(context) == null
    }

    // Match only the base logical display by name prefix; owner and geometry vary by firmware.
    internal fun displayId(dump: String): Int? = target(dump)?.displayId

    internal fun target(dump: String): com.shilapi.xcertplay.hud.BydVirtualDisplay.Target? =
        com.shilapi.xcertplay.hud.BydVirtualDisplay.parse(dump)

    fun streamSize(): Pair<Int, Int> =
        com.shilapi.xcertplay.hud.BydVirtualDisplay.last?.let { it.width to it.height }
            ?: (DiLink4ClusterDisplay.STREAM_WIDTH to DiLink4ClusterDisplay.STREAM_HEIGHT)

    /** KEYCODE_BACK (4) to one display. */
    internal fun backCommand(display: Int): String {
        require(display > 0)
        return "input -d $display keyevent 4"
    }

    /**
     * The stock map re-projects onto the shared panel when the wheel menu enters Small or Full
     * navi from another known mode. A switch between Small and Full counts too.
     */
    internal fun stockMapCoversOnModeChange(
        previous: com.shilapi.xcertplay.hud.BydClusterNaviMode?,
        mode: com.shilapi.xcertplay.hud.BydClusterNaviMode?,
    ): Boolean = previous != null && mode != null && previous != mode &&
        (mode == com.shilapi.xcertplay.hud.BydClusterNaviMode.SMALL ||
            mode == com.shilapi.xcertplay.hud.BydClusterNaviMode.FULL)

    /**
     * Sends KEYCODE_BACK to the panel. The stock map's projection Activity closes when it is on
     * top; DiPlay's own Activity is not focusable and ignores the key. Only with the DiLink 4
     * route on, DiPlay's window already confirmed on the display and the iPhone session live, so
     * the stock map is never dismissed while DiPlay shows nothing. Blocking; call off the main
     * thread.
     */
    fun dismissStockProjection(context: Context): Boolean {
        if (!enabled(context) || !ClusterActivityOutput.hasConfirmedRoute() ||
            !CarPlayBackgroundSession.active) return false
        return runCatching {
            LocalAdb(AdbKeys.load(context)).use { adb ->
                if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use false
                val display = com.shilapi.xcertplay.hud.BydVirtualDisplay.last?.displayId
                    ?: target(adb.shell("dumpsys display").orEmpty())?.displayId
                    ?: return@use false
                if (!enabled(context) || !ClusterActivityOutput.hasConfirmedRoute() ||
                    !CarPlayBackgroundSession.active) return@use false
                adb.shell(backCommand(display))
                true
            }
        }.getOrDefault(false)
    }

    /** Fire-and-forget [dismissStockProjection] on a background thread. */
    fun dismissStockProjectionAsync(context: Context, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        Thread({
            val sent = dismissStockProjection(app)
            log("stock-map BACK sent=$sent")
        }, "adb-cluster-back").start()
    }

    // Direct shell launch, following Hanxu4131's legacy platform-21 adapter.
    internal fun launchCommand(pkg: String, display: Int, token: String): String {
        require(display > 0)
        require(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(pkg))
        require(runCatching { java.util.UUID.fromString(token).toString() == token }.getOrDefault(false))
        return "am start-activity --display $display -f 0x18000000 " +
            "-n $pkg/com.shilapi.xcertplay.AdbClusterActivity --es cluster_launch_token $token"
    }

    internal fun accepted(output: String): Boolean =
        (output.contains("Starting: Intent {") || Regex("(?m)^Status: ok\\s*$").containsMatchIn(output)) &&
        !Regex("(?i)error|exception|permission\\s*deni(?:al|ed)").containsMatchIn(output)

    internal fun activityDisplay(dump: String, pkg: String, task: Int): Int? {
        var display: Int? = null
        val matches = mutableListOf<Int>()
        val component = "$pkg/com.shilapi.xcertplay.AdbClusterActivity"
        // Car firmware lists history lines unindented ("* Hist #0: …, StackId =78 … t86}");
        // newer AOSP indents them and pads the index ("    * Hist  #0: …"). Both belong to the
        // current display section; only other top-level lines end it.
        val history = Regex("^\\s*\\* Hist\\s+#\\d+: ActivityRecord\\{")
        for (line in dump.lineSequence()) {
            val header = Regex("^Display #(\\d+) \\(activities from top to bottom\\):\\s*$").matchEntire(line)
            if (header != null) { display = header.groupValues[1].toInt(); continue }
            if (line.isNotEmpty() && !line.first().isWhitespace() && !history.containsMatchIn(line)) display = null
            val current = display ?: continue
            if (history.containsMatchIn(line) &&
                Regex("\\bu\\d+\\s+" + Regex.escape(component) + "(?=\\s|,)").containsMatchIn(line) &&
                Regex("\\bt$task(?=\\s|\\})").containsMatchIn(line)) matches.add(current)
        }
        return matches.distinct().singleOrNull()?.takeIf { it > 0 }
    }

    fun launch(context: Context, token: String, prepare: (Int) -> Boolean): Result {
        var success = false
        val text = buildString {
            appendLine("ADB direct cluster launch capturedAt=${java.util.Date()}")
            try {
                LocalAdb(AdbKeys.load(context)).use { adb ->
                    val access = adb.connect(mayAsk = false)
                    appendLine("adbAccess=$access")
                    if (access != LocalAdb.Access.READY) return@use
                    val panel = target(adb.shell("dumpsys display").orEmpty())
                    appendLine("routeTarget=${panel?.displayId ?: "none"}" +
                        (panel?.let { " size=${it.width}x${it.height}" } ?: ""))
                    if (panel == null || !enabled(context) || !prepare(panel.displayId)) return@use
                    val output = adb.shell(launchCommand(context.packageName, panel.displayId, token)).orEmpty()
                    success = accepted(output)
                    appendLine(output.take(1500))
                    appendLine("launchAccepted=$success; awaiting actual display confirmation")
                }
            } catch (error: Exception) {
                appendLine("routeError=${error.javaClass.simpleName}: ${error.message}")
            }
        }
        // A failed diagnostic write must not leave launchPending stuck forever.
        runCatching { File(context.filesDir, REPORT).writeText(text) }
        return Result(success, text)
    }

    fun verify(context: Context, task: Int): Int? = runCatching {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) null
            else activityDisplay(adb.shell("dumpsys activity activities").orEmpty(), context.packageName, task)
        }
    }.getOrNull()

    fun report(context: Context): String = File(context.filesDir, REPORT).let {
        if (it.isFile) it.readText() else "ADB cluster routing has not been run."
    }
}
