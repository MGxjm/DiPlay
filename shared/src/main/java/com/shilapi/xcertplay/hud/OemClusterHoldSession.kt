package com.shilapi.xcertplay.hud

/** Used only on one serial worker. The journal is durable before the first OEM write. */
internal class OemClusterHoldSession(
    private val readState: (Target) -> Int?,
    private val setState: (Target, Int) -> Boolean,
    private val loadJournal: () -> Journal?,
    private val saveJournal: (Journal?) -> Boolean,
) {
    enum class Target { COMPONENT, PACKAGE }
    data class Journal(val target: Target, val originalState: Int, val lease: String)
    private var ownedLease: String? = null

    fun acquire(mode: BydOemClusterHold, lease: String, current: () -> Boolean): Boolean {
        if (!current()) return false
        return when (mode) {
            // OFF re-enables any previous hold, including a long-term PACKAGE disable left behind.
            BydOemClusterHold.OFF -> restoreStockMap()
            // DiPlay-running disable: journal the original state, restore on release.
            BydOemClusterHold.COMPONENT -> acquireJournaled(Target.COMPONENT, lease, current)
            // Long-term disable: no journal, no auto-restore. Stays disabled after DiPlay stops.
            BydOemClusterHold.PACKAGE -> acquirePersistent(Target.PACKAGE, lease, current)
        }
    }

    private fun acquireJournaled(target: Target, lease: String, current: () -> Boolean): Boolean {
        val pending = loadJournal()
        if (pending != null && ownedLease == lease && pending.target == target &&
            readState(target) == DISABLED_USER) return current()
        if (pending != null && !release()) return false
        if (!current()) return false
        val previous = readState(target)?.takeIf { it in 0..4 } ?: return false
        if (!saveJournal(Journal(target, previous, lease))) return false
        ownedLease = lease
        if (current() && setState(target, DISABLED_USER) && readState(target) == DISABLED_USER && current()) return true
        // Failed commands can still have mutated the OEM state. Keep recovery evidence on failure.
        release(lease)
        return false
    }

    private fun acquirePersistent(target: Target, lease: String, current: () -> Boolean): Boolean {
        if (readState(target) == DISABLED_USER) return current()
        if (!current()) return false
        // Drop any journaled hold from a previous COMPONENT mode so a later release() won't fight this.
        saveJournal(null)
        ownedLease = null
        return setState(target, DISABLED_USER) && readState(target) == DISABLED_USER && current()
    }

    fun release(lease: String? = null): Boolean {
        val pending = loadJournal() ?: return true
        // A late failed launch must not undo a newer activity's hold.
        if (lease != null && lease != pending.lease) return true
        if (readState(pending.target) != pending.originalState &&
            !setState(pending.target, pending.originalState)) return false
        if (readState(pending.target) != pending.originalState || !saveJournal(null)) return false
        ownedLease = null
        return true
    }

    /**
     * Re-enable both the cluster projection component and the whole stock map package, and clear
     * any recovery journal. Used when the driver picks OFF, so a previous long-term PACKAGE
     * disable is also undone. Safe to call when nothing was held.
     */
    fun restoreStockMap(): Boolean {
        var ok = true
        for (target in Target.entries) {
            val now = readState(target)
            // DEFAULT (0) and ENABLED (1) both mean the target is enabled, so writing ENABLED for
            // DEFAULT is a no-op. Some firmware rejects such a write for stock components
            // ("Shell cannot change component state"), which would otherwise fail the whole restore.
            if (now != null && now != ENABLED_DEFAULT && now != DEFAULT_STATE &&
                !setState(target, ENABLED_DEFAULT)) ok = false
        }
        if (!saveJournal(null)) ok = false
        ownedLease = null
        return ok
    }

    companion object {
        const val DISABLED_USER = 3
        const val ENABLED_DEFAULT = 1

        /** PackageManager.COMPONENT_ENABLED_STATE_DEFAULT: no explicit state, i.e. enabled. */
        const val DEFAULT_STATE = 0
    }
}
