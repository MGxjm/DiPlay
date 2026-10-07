package com.shilapi.xcertplay.hud

/**
 * The stock AMap adapter that turns AUTONAVI_STANDARD_BROADCAST_SEND broadcasts into cluster guidance.
 * DiLink 3 and DiLink 4 ship the same adapter and take the same cluster-mode commands, so this must
 * not gate anything the cluster needs: the guidance card, the preformatted `*_AUTO` text and the
 * simple-navigation command are identical on both, and only the projection-display creation below
 * still keys on the package.
 */
internal enum class BydAmapAdapter(val packageName: String, val needsSimpleNavigationMode: Boolean) {
    BYD("com.byd.amapservice", needsSimpleNavigationMode = false),
    DILINK3("com.example.amapservice", needsSimpleNavigationMode = true);

    companion object {
        fun find(installed: (String) -> Boolean): BydAmapAdapter? = entries.firstOrNull { installed(it.packageName) }
    }
}

/**
 * The DiLink 3 cluster mode, switched through the AutoContainer binder as ClusterDebug does with
 * sendInfo(1000, command, ""). Apps would need a BYD signature; the adb shell may call it.
 * ClusterDebug labels 16 "full-screen projection on", 17 "half-screen projection on",
 * 18 "projection off" (the stock state) and 39 "simple navigation".
 */
internal object BydDiLink3ClusterMode {
    enum class Mode(val info: Int) {
        // Full-screen projection: the map fills the instrument. Used while the driver picked
        // Full screen navi on the wheel; 17 alone does not bring the projection up in that mode.
        FULL_PROJECTION(16),
        // Half screen keeps the cluster's own speed and status readouts beside the map.
        PROJECTION(17),
        SIMPLE_NAVIGATION(39),
        STOCK(18);

        val command: String get() = "service call AutoContainer 2 i32 1000 i32 $info s16 \"\""
    }

    /**
     * The mode to request now, or null while DiPlay has never changed the stock mode. Once the
     * instrument's navi mode is readable it decides alone, and the guidance/map flags are only the
     * fallback for head units that report no mode at all (DiLink 3):
     * - Turn-on-by-navi (2) shows the text guidance card and never the projection map, so it asks
     *   for the simple-navigation command (39).
     * - Full screen navi (4) always needs the full-screen projection (16): it is the only command
     *   that opens the full window, so falling through to the stock view (18) would blank the
     *   dashboard until the driver changed the wheel mode again.
     * - Small screen navi (3) asks for the half-screen projection (17) as well, never the turn card:
     *   the DiLink 4 ADB route never reports DiPlay's map window, so waiting for that flag left the
     *   wheel switch from Full to Small sending the stock view (18) and blanking the dashboard.
     * - Off (1) closes the projection; a null mode (nothing to restore) leaves the stock view alone.
     */
    fun desired(mapShown: Boolean, guidanceActive: Boolean, requested: Mode?,
        instrumentMode: BydClusterNaviMode?,
    ): Mode? = when {
        instrumentMode == BydClusterNaviMode.TURN_ON_BY_NAVI -> Mode.SIMPLE_NAVIGATION
        instrumentMode == BydClusterNaviMode.FULL -> Mode.FULL_PROJECTION
        instrumentMode == BydClusterNaviMode.SMALL -> Mode.PROJECTION
        mapShown -> Mode.PROJECTION
        instrumentMode != null -> null
        guidanceActive -> Mode.SIMPLE_NAVIGATION
        requested != null -> Mode.STOCK
        else -> null
    }

    /** True when the binder call returned without an exception. */
    fun accepted(output: String?): Boolean = BydParcel.words(output).firstOrNull() == 0L

    /**
     * Creates the cluster projection display, fission_bg_xdjaVirtualSurface, which does not exist until
     * the cluster first projects. As DashCast does: full projection, then 35 (Di4.0 mode) creates it; the
     * display then stays after projection off restores the stock view.
     */
    val CREATE_DISPLAY = listOf(16, 35, 18).map { "service call AutoContainer 2 i32 1000 i32 $it s16 \"\"" }
}

/** The DiLink 3 adapter shows these preformatted strings, not the numeric distance and time extras. */
internal object BydDiLink3GuidanceText {
    fun distance(meters: Int): String? = when {
        meters < 0 -> null
        meters < 1000 -> "$meters m"
        meters < 10_000 -> String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
        else -> "${meters / 1000} km"
    }

    fun duration(seconds: Int): String? {
        if (seconds < 0) return null
        val minutes = (seconds + 59) / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    fun arrival(nowMillis: Long, seconds: Int, zone: java.util.TimeZone, use24Hour: Boolean): String? {
        if (seconds < 0) return null
        val format = java.text.SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm", java.util.Locale.US)
        format.timeZone = zone
        return format.format(java.util.Date(nowMillis + seconds * 1000L))
    }
}
