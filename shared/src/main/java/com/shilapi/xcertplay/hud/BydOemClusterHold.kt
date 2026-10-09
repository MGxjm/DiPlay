package com.shilapi.xcertplay.hud

/**
 * How DiPlay keeps the car's own map off the instrument-cluster surface while it mirrors there.
 *
 * On the inspected DiLink 4 APK, PushService binds VirtualBindService to draw the cluster map.
 * Disabling only that service may stop its Presentation while leaving the package installed; vehicle
 * mode switching must be checked on the target firmware. Disabling the whole package is blunter and
 * also removes the app's navigation-state listener. Needs ADB over network.
 */
enum class BydOemClusterHold {
    /** Share the surface with the car's map; do nothing. */
    OFF,

    /** Disable only the car map's cluster projection, leaving the rest of the app running. */
    COMPONENT,

    /** Disable the whole car map package while DiPlay mirrors the cluster. */
    PACKAGE;

    companion object {
        /** The saved preference, or null when it is absent or no longer a known value. */
        fun fromName(name: String?): BydOemClusterHold? = entries.firstOrNull { it.name == name }
    }
}
