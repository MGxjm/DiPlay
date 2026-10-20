package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.SafeAreaRect

/** 2022 Seal / DiLink 4.0: 1920x720 logical display, 1920x624 observed activity area. */
internal object DiLink4ClusterDisplay {
    const val NAME = "fission_bg_xdjaVirtualSurface"

    // Controller 13 and 17 firmware list the same 1920x720 cluster surface under other names (#552).
    private val FULL_PANEL_NAMES = setOf(NAME, "fission_magicsentry", "sission_magicsentry")

    // Exact names and geometry only. The loose fission_ prefix is for the ADB dumpsys probe
    // (BydVirtualDisplay), which cannot see display names any other way; here DisplayManager
    // reports names reliably, and DiLink 5/5.1 panels also start with fission_.
    fun matches(name: String, width: Int, height: Int): Boolean =
        name in FULL_PANEL_NAMES && width == 1920 && height == 720

    /**
     * Also recognize the 1280x480 projection surface reported by a DiLink 3 car. This
     * smaller panel uses the generic per-display stream, not [streamConfig]. Keep selection
     * limited to observed geometries: an aspect ratio alone does not identify a cluster.
     */
    fun accepts(name: String, width: Int, height: Int): Boolean =
        matches(name, width, height) || (name == NAME && width == 1280 && height == 480)

    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720

    // Reuse DiLink 5 marker-safe margins as a calibration starting point.
    // Draw outside remains enabled so the map background still fills the activity.
    // A marker placed in percent (the settings sliders) takes precedence over the legacy steps.
    // The safe-area rectangle is stored in the 1920x720 calibration space and mapped by axis to
    // the negotiated stream, so a measured panel size keeps the saved placement.
    fun streamConfig(content: CarPlayClusterDisplay.Content, horizontalStep: Int = 0, verticalStep: Int = 0,
        safeAreaRect: SafeAreaRect? = null, markerXPercent: Int? = null,
        markerYPercent: Int? = null, width: Int = STREAM_WIDTH,
        height: Int = STREAM_HEIGHT): com.shilapi.xcertplay.airplay.AirPlayDisplayConfig {
        val config = CarPlayClusterDisplay.config(width, height, scalePercent = 100,
            horizontalStep = horizontalStep, verticalStep = verticalStep, content = content,
            markerXPercent = markerXPercent, markerYPercent = markerYPercent)
        return if (safeAreaRect == null) config else config.copy(safeArea = AirPlaySafeArea.toInsets(
            safeAreaRect, STREAM_WIDTH, STREAM_HEIGHT, width, height))
    }

    fun defaultSafeAreaRect(horizontalStep: Int = 0, verticalStep: Int = 0,
        markerXPercent: Int? = null, markerYPercent: Int? = null): SafeAreaRect {
        val insets = streamConfig(CarPlayClusterDisplay.Content.MAP, horizontalStep, verticalStep,
            markerXPercent = markerXPercent, markerYPercent = markerYPercent).safeArea!!
        return SafeAreaRect(insets.left, insets.top, STREAM_WIDTH - insets.right, STREAM_HEIGHT - insets.bottom)
    }
}
