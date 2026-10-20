package com.shilapi.xcertplay.hud

/**
 * The cluster's virtual projection panel as `dumpsys display` lists it. The stock map owns this
 * container on DiLink 4 (its owner is a system app, not DiPlay), so DiPlay only discovers the panel
 * and projects onto it. The name is the only stable identity: owner varies by firmware and the
 * geometry varies by panel (1920x720 on DiLink 4, 1280x480 on a DiLink 3 car).
 */
object BydVirtualDisplay {
    const val EXACT_NAME = "fission_bg_xdjaVirtualSurface"
    const val NAME_PREFIX = "fission_"

    // Controller 13 and 17 firmware list the same panel under a misspelled name (#552).
    private const val MISSPELLED_ALIAS = "sission_magicsentry"

    data class Target(val displayId: Int, val width: Int, val height: Int)

    /** The last panel a successful detection saw; later shell commands address it without a new dump. */
    @Volatile var last: Target? = null
        private set

    fun named(name: String): Boolean = name.startsWith(NAME_PREFIX) || name == MISSPELLED_ALIAS

    /**
     * The unique base logical display whose name matches. The exact name wins over other prefix
     * hits; an ambiguous set is rejected rather than projected onto the wrong panel. The measured
     * `real W x H` is reported, never enforced: only the name identifies the panel.
     */
    fun parse(dump: String): Target? {
        val found = dump.lineSequence().mapNotNull(::record).toList()
        val named = found.filter { named(it.name) && it.displayId > 0 }
        val chosen = when {
            named.count { it.name == EXACT_NAME } == 1 -> named.first { it.name == EXACT_NAME }
            named.size == 1 -> named.single()
            else -> null
        } ?: return null
        return Target(chosen.displayId, chosen.width, chosen.height).also { last = it }
    }

    private data class Record(val name: String, val displayId: Int, val width: Int, val height: Int)

    private fun record(line: String): Record? {
        // Only the base logical display identifies a panel; override/layer-stack records do not.
        if (!line.trimStart().startsWith("mBaseDisplayInfo=DisplayInfo{\"")) return null
        // Two firmware shapes exist: older builds quote "name, displayId N" as one blob, newer
        // AOSP (seen on Android 16) quotes only the name: "name", displayId N.
        val head = Regex("mBaseDisplayInfo=DisplayInfo\\{\"(.*?)(?:\",\\s*displayId |,\\s*displayId )(\\d+)")
            .find(line) ?: return null
        val size = Regex("\\breal (\\d+) x (\\d+)\\b").find(line)
        return Record(
            head.groupValues[1],
            head.groupValues[2].toIntOrNull() ?: return null,
            size?.groupValues?.get(1)?.toIntOrNull() ?: 1920,
            size?.groupValues?.get(2)?.toIntOrNull() ?: 720,
        )
    }
}
