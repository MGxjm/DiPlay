package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.SafeAreaRect

/**
 * BYD's cluster projection family, matched by display name only: the panel resolution and the
 * exact firmware name differ between models. 1920x720 was measured on the 2022 Seal / DiLink 4.0.
 */
internal object DiLink4ClusterDisplay {
    const val NAME = "fission_bg_xdjaVirtualSurface"

    // BYD/XDJA projection surfaces are named fission_* or *xdja* across DiLink 3/4/5 firmware,
    // with different casing and suffixes. Derived "shared_" layers are not the base projection.
    private val NAME_MARKERS = listOf("fission", "xdja")
    // Advisory only: the settings picker flags small third-party surfaces with these.
    internal const val MIN_WIDTH = 960
    internal const val MIN_HEIGHT = 320

    /** A BYD cluster projection surface, whatever its resolution. */
    fun matches(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith("shared_")) return false
        return NAME_MARKERS.any { lower.contains(it) }
    }

    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720

    // Reuse DiLink 5 marker-safe margins as a calibration starting point.
    // Draw outside remains enabled so the map background still fills the activity.
    fun streamConfig(content: CarPlayClusterDisplay.Content, horizontalStep: Int = 0, verticalStep: Int = 0,
        safeAreaRect: SafeAreaRect? = null): com.shilapi.xcertplay.airplay.AirPlayDisplayConfig {
        val config = CarPlayClusterDisplay.config(STREAM_WIDTH, STREAM_HEIGHT, scalePercent = 100,
            horizontalStep = horizontalStep, verticalStep = verticalStep, content = content)
        return if (safeAreaRect == null) config else config.copy(safeArea = AirPlaySafeArea.toInsets(
            safeAreaRect, STREAM_WIDTH, STREAM_HEIGHT, STREAM_WIDTH, STREAM_HEIGHT))
    }

    fun defaultSafeAreaRect(horizontalStep: Int = 0, verticalStep: Int = 0): SafeAreaRect {
        val insets = streamConfig(CarPlayClusterDisplay.Content.MAP, horizontalStep, verticalStep).safeArea!!
        return SafeAreaRect(insets.left, insets.top, STREAM_WIDTH - insets.right, STREAM_HEIGHT - insets.bottom)
    }
}
