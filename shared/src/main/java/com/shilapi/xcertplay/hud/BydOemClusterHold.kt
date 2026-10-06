package com.shilapi.xcertplay.hud

/**
 * How DiPlay keeps the car's own map off the instrument-cluster surface while it mirrors there.
 *
 * On DiLink 4.0 the car draws its cluster map with a single activity
 * ([BydOemClusterNavi.STOCK_MAP_CLUSTER_ACTIVITY]), so disabling just that activity stops the
 * projection while the rest of the app — and, on some firmware, the cluster's own navigation mode —
 * keeps running. Disabling the whole package is blunter and takes the app's other services with it,
 * which is why the driver picks here instead of the app deciding. Needs ADB over network.
 *
 * Three user-facing levels:
 * - [OFF] — 不禁用: share the surface with the car's map; also re-enables any previous hold.
 * - [COMPONENT] — DiPlay 运行期间禁用: disable only the cluster projection activity while DiPlay
 *   mirrors, restore the original state on stop (journaled, auto-recover).
 * - [PACKAGE] — 长期禁用: disable the whole stock map package and keep it disabled after DiPlay
 *   stops. No journal, no auto-restore; the driver picks OFF to re-enable.
 */
enum class BydOemClusterHold {
    /** Share the surface with the car's map; also re-enable any previous hold. */
    OFF,

    /** Disable only the car map's cluster projection while DiPlay mirrors; restore on stop. */
    COMPONENT,

    /** Disable the whole car map package and keep it disabled after DiPlay stops. */
    PACKAGE;

    companion object {
        /** The saved preference, or null when it is absent or no longer a known value. */
        fun fromName(name: String?): BydOemClusterHold? = entries.firstOrNull { it.name == name }
    }
}
