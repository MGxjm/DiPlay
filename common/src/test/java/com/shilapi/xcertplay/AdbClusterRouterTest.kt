package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.BydClusterNaviMode
import org.junit.Assert.*
import org.junit.Test

class AdbClusterRouterTest {
    private fun record(id: Int = 7, size: String = "1920 x 720", owner: String = "com.xdja.containerservice",
        name: String = "fission_bg_xdjaVirtualSurface") =
        "mBaseDisplayInfo=DisplayInfo{\"$name, displayId $id\", real $size, owner $owner (uid 1000)}"

    /** Android 16+ quotes only the name: DisplayInfo{"name", displayId N, ...}. */
    private fun newRecord(id: Int = 7, size: String = "1920 x 720", owner: String = "com.android.shell",
        name: String = "fission_mock_test") =
        "mBaseDisplayInfo=DisplayInfo{\"$name\", displayId $id, real $size, owner $owner (uid 2000)}"

    @Test fun parsesBothDisplayInfoFormats() {
        // Older firmware quotes "name, displayId N" as one blob; newer AOSP quotes only the name.
        assertEquals(7, AdbClusterRouter.displayId(record()))
        assertEquals(7, AdbClusterRouter.displayId(newRecord()))
        assertEquals(277, AdbClusterRouter.displayId(newRecord(id = 277, name = "fission_mock_test")))
        val panel = AdbClusterRouter.target(newRecord(id = 277, size = "1920 x 720"))
        assertEquals(277, panel?.displayId)
        assertEquals(1920, panel?.width)
        assertEquals(720, panel?.height)
    }

    @Test fun parsesTheRealCarFirmwareLines() {
        // Verbatim DiLink 4 dumpsys (displayId 5, quoted "name, displayId N" blob).
        val line = "mBaseDisplayInfo=DisplayInfo{\"fission_bg_xdjaVirtualSurface, displayId 5\", " +
            "uniqueId \"virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0\", " +
            "app 1920 x 720, real 1920 x 720, largest app 1920 x 720, smallest app 1920 x 720, " +
            "mode 6, defaultMode 6, modes [{id=6, width=1920, height=720, fps=60.0}], type VIRTUAL, " +
            "state ON, owner com.xdja.containerservice (uid 1000), FLAG_PRESENTATION, removeMode 0}"
        val target = AdbClusterRouter.target(line)
        assertEquals(5, target?.displayId)
        assertEquals(1920, target?.width)
        assertEquals(720, target?.height)
    }

    @Test fun verifiesTheCarFirmwareHistoryShape() {
        val pkg = "com.shihab.diplay.daming"
        // Real DiLink 4 history lines are unindented and carry StackId/windowingMode fields.
        val hist = "* Hist #0: ActivityRecord{8f36678 u0 $pkg/com.shilapi.xcertplay.AdbClusterActivity, " +
            "StackId =78, windowingMode =1 t86}"
        assertEquals(5, AdbClusterRouter.activityDisplay(
            "Display #5 (activities from top to bottom):\n$hist", pkg, 86))
        assertNull(AdbClusterRouter.activityDisplay(
            "Display #5 (activities from top to bottom):\n$hist", pkg, 87))
        // The main display is never a route target.
        assertNull(AdbClusterRouter.activityDisplay(
            "Display #0 (activities from top to bottom):\n$hist", pkg, 86))
        // Sibling stacks listed in the same section must not change the result.
        val siblings = "Display #5 (activities from top to bottom):\n" +
            "* Hist #0: ActivityRecord{22596b6 u0 com.byd.automap/.extra.MeterActivity, " +
            "StackId =78, windowingMode =1 t86}\n" + hist
        assertEquals(5, AdbClusterRouter.activityDisplay(siblings, pkg, 86))
    }

    @Test fun usesCurrentLogicalDisplayIdRatherThanLayerStackOrAssumedOne() {
        val dump = "mCurrentLayerStack=1\nDisplay 7:\n" + record() + "\nmOverrideDisplayInfo=" + record()
        assertEquals(7, AdbClusterRouter.displayId(dump))
    }

    @Test fun namePrefixFindsThePanelWithoutOwnerOrGeometryChecks() {
        // The stock map owns the panel and its owner is a system app; only the name identifies it.
        assertEquals(7, AdbClusterRouter.displayId(record(owner = "com.android.systemui")))
        assertEquals(7, AdbClusterRouter.displayId(record(size = "1280 x 480")))
        assertEquals(7, AdbClusterRouter.displayId(record(name = "fission_magicsentry")))
        assertEquals(7, AdbClusterRouter.displayId(record(name = "sission_magicsentry")))
        val panel = AdbClusterRouter.target(record(size = "1280 x 480"))
        assertEquals(1280, panel?.width)
        assertEquals(480, panel?.height)
    }

    @Test fun rejectsMainAmbiguousAndNonPanelTargets() {
        assertNull(AdbClusterRouter.displayId(record(0)))
        assertNull(AdbClusterRouter.displayId(record() + "\n" + record(8)))
        assertNull(AdbClusterRouter.displayId(record().replace("mBaseDisplayInfo", "mOverrideDisplayInfo")))
        assertNull(AdbClusterRouter.displayId(record(name = "shared_fission_bg_xdjaVirtualSurface_0")))
        assertNull(AdbClusterRouter.displayId(record(name = "Passenger display")))
    }

    @Test fun theExactNameWinsOverOtherPrefixHits() {
        assertEquals(7, AdbClusterRouter.displayId(record() + "\n" + record(8, name = "fission_magicsentry")))
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
        // Real AOSP pads the history index ("Hist  #0"); both spacings must verify.
        val padded = record.replace("Hist #0", "Hist  #0")
        assertEquals(7, AdbClusterRouter.activityDisplay(
            "Display #7 (activities from top to bottom):\n$padded", pkg, 12))
        assertNull(AdbClusterRouter.activityDisplay(dump, pkg, 13))
        assertNull(AdbClusterRouter.activityDisplay(dump.replace("Display #7", "Display #0"), pkg, 12))
        assertNull(AdbClusterRouter.activityDisplay("ResumedActivity: $record", pkg, 12))
        assertNull(AdbClusterRouter.activityDisplay(dump.replace(pkg, "other.package"), pkg, 12))
    }

    @Test fun stockMapBackTargetsThePanelDisplayWithKeycodeBack() {
        assertEquals("input -d 7 keyevent 4", AdbClusterRouter.backCommand(7))
        assertTrue(runCatching { AdbClusterRouter.backCommand(0) }.isFailure)
    }

    @Test fun stockMapBackFollowsSmallAndFullNaviEntriesFromAKnownMode() {
        val covers = AdbClusterRouter::stockMapCoversOnModeChange
        // Entering Small or Full from any other known mode, including Small <-> Full.
        assertTrue(covers(BydClusterNaviMode.OFF, BydClusterNaviMode.SMALL))
        assertTrue(covers(BydClusterNaviMode.TURN_ON_BY_NAVI, BydClusterNaviMode.FULL))
        assertTrue(covers(BydClusterNaviMode.SMALL, BydClusterNaviMode.FULL))
        assertTrue(covers(BydClusterNaviMode.FULL, BydClusterNaviMode.SMALL))
        // Unknown previous mode, no change, or leaving Small/Full must not close the stock map.
        assertFalse(covers(null, BydClusterNaviMode.SMALL))
        assertFalse(covers(BydClusterNaviMode.SMALL, BydClusterNaviMode.SMALL))
        assertFalse(covers(BydClusterNaviMode.SMALL, BydClusterNaviMode.OFF))
        assertFalse(covers(BydClusterNaviMode.FULL, null))
    }
}
