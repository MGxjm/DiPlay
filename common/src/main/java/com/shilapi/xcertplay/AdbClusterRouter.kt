package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.File

/** Strictly measured DiLink 4 target, discovered afresh through the authorized shell. */
internal object AdbClusterRouter {
    private const val REPORT = "adb-cluster-route.txt"
    data class Result(val success: Boolean, val report: String)

    /**
     * A cluster display the user picked manually in settings. Matched by name and geometry, not by
     * the numeric id: the id is per-boot, the name and panel size survive reboots. Bypasses the
     * automatic owner/geometry checks on purpose — a manual pick is trusted as-is.
     */
    data class DisplayTarget(val name: String, val width: Int, val height: Int) {
        fun encode(): String = listOf(width.toString(), height.toString(), name).joinToString(SEP)
        fun describe(): String = "$name ${width}x$height"

        companion object {
            private const val SEP = "|"
            fun parse(encoded: String?): DisplayTarget? {
                if (encoded.isNullOrBlank()) return null
                val parts = encoded.split(SEP, limit = 3)
                val width = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val height = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val name = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
                return DisplayTarget(name, width, height)
            }
        }
    }

    /** One logical display parsed from `dumpsys display`, for the settings picker. */
    data class DisplayCandidate(val id: Int, val name: String, val width: Int, val height: Int,
        val owner: String = "")

    /** Classification shown next to each display in the settings picker. */
    enum class DisplayClass { MAIN, DASHBOARD, WIDGET, OTHER }

    /**
     * Classifies a discovered display for the picker. The main display (id 0) is never
     * selectable; a BYD/XDJA projection surface, whatever its resolution, is the recommended
     * dashboard target; small surfaces owned by non-BYD packages are suspected third-party
     * desktop widgets. Nothing here prevents a manual pick — the markers only advise.
     */
    fun classify(candidate: DisplayCandidate): DisplayClass {
        if (candidate.id == 0) return DisplayClass.MAIN
        if (DiLink4ClusterDisplay.matches(candidate.name)) return DisplayClass.DASHBOARD
        val smallArea = candidate.width * candidate.height < DiLink4ClusterDisplay.MIN_WIDTH * DiLink4ClusterDisplay.MIN_HEIGHT
        val bydOwner = candidate.owner.startsWith("com.xdja.") || candidate.owner.startsWith("com.byd.")
        if (smallArea && !bydOwner) return DisplayClass.WIDGET
        return DisplayClass.OTHER
    }

    /** Existing public cluster displays always win, even when the experimental switch is saved. */
    fun enabled(context: Context): Boolean = AirPlayPersistence.loadAdbClusterEnabled(context) &&
        !DiLink51ClusterLayout.diLink5Route(context) && ClusterMapPresentation.findDisplay(context) == null

    /** Every base logical display in a `dumpsys display` dump, whatever owns it. */
    internal fun candidates(dump: String): List<DisplayCandidate> = dump.lineSequence().mapNotNull { line ->
        if (!line.contains("mBaseDisplayInfo=DisplayInfo{\"")) return@mapNotNull null
        val name = Regex("mBaseDisplayInfo=DisplayInfo\\{\"([^\"]+?), displayId \\d+\"")
            .find(line)?.groupValues?.get(1) ?: return@mapNotNull null
        val size = Regex("\\breal (\\d+) x (\\d+)\\b").find(line) ?: return@mapNotNull null
        val width = size.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val height = size.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val id = Regex("displayId (\\d+)\"").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val owner = Regex("\\bowner (\\S+) \\(uid \\d+\\)").find(line)?.groupValues?.get(1).orEmpty()
        DisplayCandidate(id, name, width, height, owner)
    }.distinct().toList()

    /**
     * The display to launch the cluster activity on: the manually picked [override] when it is
     * present in the dump, otherwise the automatic BYD projection match.
     */
    internal fun resolveDisplay(dump: String, override: DisplayTarget?): Int? {
        if (override != null) {
            candidates(dump).firstOrNull {
                it.name == override.name && it.width == override.width && it.height == override.height
            }?.id?.let { return it }
        }
        return displayId(dump)
    }

    // Match only the base logical display, not a device's layer-stack number or override record.
    // The name identifies BYD's whole cluster projection family; the panel resolution differs
    // between models and firmware, so it takes no part in the match.
    internal fun displayId(dump: String): Int? {
        val candidates = dump.lineSequence().mapNotNull { line ->
            if (!line.contains("mBaseDisplayInfo=DisplayInfo{\"")) return@mapNotNull null
            if (!line.contains("owner com.xdja.containerservice (uid 1000)")) return@mapNotNull null
            val name = Regex("mBaseDisplayInfo=DisplayInfo\\{\"([^\"]+?), displayId \\d+\"")
                .find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            if (!DiLink4ClusterDisplay.matches(name)) return@mapNotNull null
            Regex("displayId (\\d+)\"").find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        }.distinct().toList()
        return candidates.singleOrNull()
    }

    /**
     * FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK: a fresh cluster task every launch, so a
     * stale task left on another display is never reused for a new route.
     */
    private const val LAUNCH_FLAGS = "0x18000000"

    /**
     * FLAG_ACTIVITY_NEW_TASK only. Android finds the running cluster task and reorders it, keeping
     * its decoder and surface; MULTIPLE_TASK would create a second activity, re-attach the CarPlay
     * stream and visibly refresh the cluster on every front pass.
     */
    private const val FRONT_FLAGS = "0x10000000"

    // Direct shell launch, following Hanxu4131's legacy platform-21 adapter.
    internal fun launchCommand(pkg: String, display: Int, token: String): String =
        amStart(pkg, display, token, LAUNCH_FLAGS)

    /** Re-orders an existing cluster task to the front without recreating its activity. */
    internal fun frontCommand(pkg: String, display: Int, token: String): String =
        amStart(pkg, display, token, FRONT_FLAGS)

    private fun amStart(pkg: String, display: Int, token: String, flags: String): String {
        require(display > 0)
        require(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(pkg))
        require(runCatching { java.util.UUID.fromString(token).toString() == token }.getOrDefault(false))
        return "am start-activity --display $display -f $flags " +
            "-n $pkg/com.shilapi.xcertplay.AdbClusterActivity --es cluster_launch_token $token"
    }

    internal fun accepted(output: String): Boolean =
        (output.contains("Starting: Intent {") || Regex("(?m)^Status: ok\\s*$").containsMatchIn(output)) &&
        !Regex("(?i)error|exception|permission\\s*deni(?:al|ed)").containsMatchIn(output)

    internal fun activityDisplay(dump: String, pkg: String, task: Int): Int? {
        var display: Int? = null
        val matches = mutableListOf<Int>()
        val component = "$pkg/com.shilapi.xcertplay.AdbClusterActivity"
        for (line in dump.lineSequence()) {
            // Some firmware omits the trailing colon for non-default displays
            // ("Display #1 (activities from top to bottom)"), so it must be optional.
            val header = Regex("^Display #(\\d+) \\(activities from top to bottom\\):?\\s*$").matchEntire(line)
            if (header != null) { display = header.groupValues[1].toInt(); continue }
            if (line.isNotEmpty() && !line.first().isWhitespace()) display = null
            val current = display ?: continue
            if (Regex("^\\s{4,}\\* Hist #\\d+: ActivityRecord\\{").containsMatchIn(line) &&
                Regex("\\bu\\d+\\s+" + Regex.escape(component) + "(?=\\s|,)").containsMatchIn(line) &&
                Regex("\\bt$task(?=\\s|\\})").containsMatchIn(line)) matches.add(current)
        }
        return matches.singleOrNull()?.takeIf { it > 0 }
    }

    fun launch(context: Context, token: String, holdStockMap: Boolean = true, prepare: (Int) -> Boolean): Result {
        var success = false
        val text = buildString {
            appendLine("ADB direct cluster launch capturedAt=${java.util.Date()}")
            appendLine("diLink3ModeSwitchSuppressed=" + AirPlayPersistence.loadAdbClusterEnabled(context))
            appendLine("calibrationOnly=${!holdStockMap}")
            try {
                LocalAdb(AdbKeys.load(context)).use { adb ->
                    val access = adb.connect(mayAsk = false)
                    appendLine("adbAccess=$access")
                    if (access != LocalAdb.Access.READY) return@use
                    val dump = adb.shell("dumpsys display").orEmpty()
                    val override = AirPlayPersistence.loadClusterDisplayOverride(context)
                    val display = resolveDisplay(dump, override)
                    AirPlayPersistence.saveClusterDisplayCandidates(context, candidates(dump).map {
                        DisplayTarget(it.name, it.width, it.height)
                    })
                    appendLine("routeTarget=${display ?: "none"}")
                    appendLine("displayOverride=${override?.describe() ?: "auto"}")
                    if (display == null || !enabled(context) || !prepare(display)) return@use
                    // The first launch re-enables the stock map so its cluster activity rebuilds the
                    // instrument's projection window, and picks Small screen navi; every launch then
                    // re-sends the projection command the instrument's navi mode asks for (nothing for
                    // Small, 16 for Full, 18 while it is off), and only then does DiPlay start its own
                    // projection. A refused prepare must not stop the launch: the stock map then simply
                    // keeps the surface, whereas returning here would show nothing at all. The stock map
                    // is disabled again once DiPlay's projection is confirmed (see AdbClusterActivity).
                    val prepared = !holdStockMap ||
                        com.shilapi.xcertplay.hud.BydOemClusterNavi.prepareForLaunch(context) {
                            enabled(context) && prepare(display)
                        }
                    appendLine("stockMapPrimeReady=$prepared")
                    if (!enabled(context) || !prepare(display)) return@use
                    val output = adb.shell(launchCommand(context.packageName, display, token)).orEmpty()
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

    /**
     * Re-orders the cluster task that already owns the projection back to the front: the instrument
     * re-lays out its window when DiPlay asks for the full-screen projection (16), which raises the
     * stock map above DiPlay's cluster task. The existing task is only reordered ([frontCommand] does
     * not add MULTIPLE_TASK), so its activity, decoder and surface survive and nothing refreshes. The
     * already-confirmed display and token are reused, so nothing is invalidated when the shell is
     * unavailable, and the result is best effort. Blocking; call off the UI thread.
     */
    fun front(context: Context, display: Int, token: String): Boolean = runCatching {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) false
            else accepted(adb.shell(frontCommand(context.packageName, display, token)).orEmpty())
        }
    }.getOrDefault(false)

    fun verify(context: Context, task: Int): Int? = runCatching {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) null
            else activityDisplay(adb.shell("dumpsys activity activities").orEmpty(), context.packageName, task)
        }
    }.getOrNull()

    /**
     * Reads the head unit's current display list for the settings picker. Blocking; call off the
     * UI thread. Null only when ADB is not authorized or unreachable; an empty list means the read
     * returned no displays and must not be reported as an authorization problem.
     */
    fun scan(context: Context): List<DisplayCandidate>? = runCatching {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) null
            else candidates(adb.shell("dumpsys display").orEmpty())
        }
    }.getOrNull()

    fun report(context: Context): String = File(context.filesDir, REPORT).let {
        if (it.isFile) it.readText() else "ADB cluster routing has not been run."
    }
}
