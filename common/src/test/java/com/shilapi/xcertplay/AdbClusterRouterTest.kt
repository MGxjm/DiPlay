package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class AdbClusterRouterTest {
    private fun record(id: Int = 7, size: String = "1920 x 720", owner: String = "com.xdja.containerservice",
        name: String = "fission_bg_xdjaVirtualSurface") =
        "mBaseDisplayInfo=DisplayInfo{\"$name, displayId $id\", real $size, owner $owner (uid 1000)}"

    @Test fun usesCurrentLogicalDisplayIdRatherThanLayerStackOrAssumedOne() {
        val dump = "mCurrentLayerStack=1\nDisplay 7:\n" + record() + "\nmOverrideDisplayInfo=" + record()
        assertEquals(7, AdbClusterRouter.displayId(dump))
    }
    @Test fun acceptsTheWholeBydProjectionFamilyWhateverItsResolution() {
        assertEquals(7, AdbClusterRouter.displayId(record(name = "fission_bg_XDJAScreenProjection")))
        assertEquals(7, AdbClusterRouter.displayId(record(name = "fission_bg_xdjaVirtualSurface_1", size = "1600 x 600")))
        // The name alone decides: the panel resolution differs between models and firmware.
        assertEquals(7, AdbClusterRouter.displayId(record(size = "1280 x 720")))
        assertEquals(7, AdbClusterRouter.displayId(record(size = "640 x 240")))
    }
    @Test fun rejectsMainPassengerUnknownNamesAndAmbiguousTargets() {
        assertNull(AdbClusterRouter.displayId(record(0)))
        assertNull(AdbClusterRouter.displayId(record(owner = "com.passenger")))
        assertNull(AdbClusterRouter.displayId(record(name = "passenger_screen")))
        assertNull(AdbClusterRouter.displayId(record() + "\n" + record(8)))
        assertNull(AdbClusterRouter.displayId(record().replace("mBaseDisplayInfo", "mOverrideDisplayInfo")))
    }
    @Test fun manualOverrideWinsAndFallsBackToAutomaticWhenAbsent() {
        val override = AdbClusterRouter.DisplayTarget("fission_bg_xdjaVirtualSurface", 1920, 720)
        assertEquals(7, AdbClusterRouter.resolveDisplay(record(), override))
        // Picked display missing from this dump: fall back to the automatic match.
        assertEquals(7, AdbClusterRouter.resolveDisplay(record(), override.copy(name = "gone")))
        // A manual pick bypasses the owner check by design: it resolves whatever matches its name+size.
        val passenger = record(owner = "com.passenger")
        assertEquals(7, AdbClusterRouter.resolveDisplay(passenger, override))
        // No override: automatic behavior, unchanged.
        assertEquals(7, AdbClusterRouter.resolveDisplay(record(), null))
        assertNull(AdbClusterRouter.resolveDisplay(record(name = "passenger_screen"), null))
        assertNull(AdbClusterRouter.resolveDisplay(passenger, null))
    }

    @Test fun candidatesListEveryBaseDisplayWithItsGeometry() {
        // candidates() now returns every base logical display, including the main display (id 0),
        // so the picker can mark the main screen as not selectable instead of hiding it.
        val dump = record() + "\n" + record(id = 0, size = "1280 x 720", owner = "com.android.systemui", name = "main_screen")
        val candidates = AdbClusterRouter.candidates(dump)
        assertEquals(2, candidates.size)
        assertEquals(7, candidates[0].id)
        assertEquals("fission_bg_xdjaVirtualSurface", candidates[0].name)
        assertEquals(1920, candidates[0].width)
        assertEquals(720, candidates[0].height)
        assertEquals("com.xdja.containerservice", candidates[0].owner)
        assertEquals(0, candidates[1].id)
        assertEquals("main_screen", candidates[1].name)
        assertEquals(1280, candidates[1].width)
        assertEquals(720, candidates[1].height)
        assertEquals("com.android.systemui", candidates[1].owner)
    }

    @Test fun classifyDisplayCandidatesIntoMainDashboardWidgetOther() {
        // id 0 is always the main screen and never selectable.
        assertEquals(AdbClusterRouter.DisplayClass.MAIN,
            AdbClusterRouter.classify(AdbClusterRouter.DisplayCandidate(0, "main_screen", 1280, 720, "com.android.systemui")))
        // A BYD/XDJA projection surface is the recommended target whatever its resolution.
        assertEquals(AdbClusterRouter.DisplayClass.DASHBOARD,
            AdbClusterRouter.classify(AdbClusterRouter.DisplayCandidate(7, "fission_bg_xdjaVirtualSurface", 1920, 720, "com.xdja.containerservice")))
        assertEquals(AdbClusterRouter.DisplayClass.DASHBOARD,
            AdbClusterRouter.classify(AdbClusterRouter.DisplayCandidate(8, "fission_small_panel", 640, 240, "com.xdja.containerservice")))
        // A small surface owned by a non-BYD package is a suspected third-party widget.
        assertEquals(AdbClusterRouter.DisplayClass.WIDGET,
            AdbClusterRouter.classify(AdbClusterRouter.DisplayCandidate(2, "widget_overlay", 400, 300, "com.thirdparty.widget")))
        // A small BYD-owned surface is not classed as a third-party widget — it falls through to OTHER.
        assertEquals(AdbClusterRouter.DisplayClass.OTHER,
            AdbClusterRouter.classify(AdbClusterRouter.DisplayCandidate(3, "small_byd", 400, 300, "com.xdja.something")))
        // A large non-projection surface owned by anyone is OTHER.
        assertEquals(AdbClusterRouter.DisplayClass.OTHER,
            AdbClusterRouter.classify(AdbClusterRouter.DisplayCandidate(4, "passenger_screen", 1920, 1080, "com.passenger")))
    }

    @Test fun diLink5RouteDetectionByProjectionNameNotFingerprint() {
        // DiLink 5 routing is now keyed on the projection surface name, not the single measured
        // firmware fingerprint, so any firmware exposing an XDJA Screen Projection display takes
        // the DiLink 5 path. Pure-name detection is exercised here; the full Context-based route
        // requires a live DisplayManager and is not covered by this pure unit test.
        assertTrue(DiLink51ClusterLayout.isDiLink5ProjectionName("fission_bg_XDJAScreenProjection"))
        assertTrue(DiLink51ClusterLayout.isDiLink5ProjectionName("shared_fission_bg_XDJAScreenProjection_0"))
        assertFalse(DiLink51ClusterLayout.isDiLink5ProjectionName("fission_bg_xdjaVirtualSurface"))
        assertFalse(DiLink51ClusterLayout.isDiLink5ProjectionName("main_screen"))
    }

    @Test fun displayTargetEncodingRoundTrips() {
        val target = AdbClusterRouter.DisplayTarget("fission|odd,name", 1920, 720)
        assertEquals(target, AdbClusterRouter.DisplayTarget.parse(target.encode()))
        assertNull(AdbClusterRouter.DisplayTarget.parse(null))
        assertNull(AdbClusterRouter.DisplayTarget.parse(""))
        assertNull(AdbClusterRouter.DisplayTarget.parse("junk"))
    }

    @Test fun directLaunchUsesIndependentTaskAndRejectsMainDisplay() {
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        val command = AdbClusterRouter.launchCommand("com.shihab.diplay.hudtest", 7, token)
        assertTrue(command.startsWith("am start-activity --display 7 -f 0x18000000 "))
        assertTrue(command.endsWith("--es cluster_launch_token $token"))
        assertTrue(runCatching { AdbClusterRouter.launchCommand("com.shihab.diplay", 0, token) }.isFailure)
        assertTrue(runCatching { AdbClusterRouter.launchCommand("bad;command", 7, token) }.isFailure)
        assertTrue(runCatching { AdbClusterRouter.launchCommand("com.shihab.diplay", 7, "bad") }.isFailure)
        assertFalse(AdbClusterRouter.accepted("Starting: Intent {}\nError: Permission Denial"))
        assertFalse(AdbClusterRouter.accepted(""))
        assertTrue(AdbClusterRouter.accepted("Starting: Intent {}"))
    }
    @Test fun verifiesOnlyExactTaskInsidePerDisplayHistory() {
        val pkg = "com.shihab.diplay.hudtest"
        val record = "    * Hist #0: ActivityRecord{abc u0 $pkg/com.shilapi.xcertplay.AdbClusterActivity t12}"
        val dump = "Display #7 (activities from top to bottom):\n$record\nResumedActivity: $record"
        assertEquals(7, AdbClusterRouter.activityDisplay(dump, pkg, 12))
        // Some firmware omits the trailing colon after the display header.
        assertEquals(7, AdbClusterRouter.activityDisplay(dump.replace("bottom):", "bottom)"), pkg, 12))
        assertNull(AdbClusterRouter.activityDisplay(dump, pkg, 13))
        assertNull(AdbClusterRouter.activityDisplay(dump.replace("Display #7", "Display #0"), pkg, 12))
        assertNull(AdbClusterRouter.activityDisplay("ResumedActivity: $record", pkg, 12))
        assertNull(AdbClusterRouter.activityDisplay(dump.replace(pkg, "other.package"), pkg, 12))
    }
}
