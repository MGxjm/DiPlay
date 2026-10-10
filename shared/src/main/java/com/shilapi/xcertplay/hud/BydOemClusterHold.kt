package com.shilapi.xcertplay.hud

/**
 * How DiPlay keeps the car's own map off the instrument-cluster surface while it mirrors there.
 *
 * The default route shares the cluster display and leaves vehicle mode and the stock projection
 * untouched. Component mode remains a compatibility value and is also treated as shared output.
 * Package mode explicitly disables the stock map package before DiPlay takes over container mode.
 * Needs ADB over network.
 */
enum class BydOemClusterHold {
    /** Share the surface with the car's map; do nothing. */
    OFF,

    /** Legacy preference value; current route shares output and does not mutate OEM state. */
    COMPONENT,

    /** Disable the whole car map package while DiPlay mirrors the cluster. */
    PACKAGE;

    companion object {
        /** The saved preference, or null when it is absent or no longer a known value. */
        fun fromName(name: String?): BydOemClusterHold? = entries.firstOrNull { it.name == name }
    }
}
