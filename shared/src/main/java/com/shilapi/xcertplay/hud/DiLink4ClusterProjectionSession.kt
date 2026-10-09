package com.shilapi.xcertplay.hud

/** Owns the container projection mode while DiPlay replaces the stock cluster presentation. */
internal class DiLink4ClusterProjectionSession(
    private val run: (String) -> String?,
    private val loadRecovery: () -> Boolean,
    private val saveRecovery: (Boolean) -> Boolean,
    private val stepDelay: () -> Unit = { Thread.sleep(1_000L) },
    private val stillWanted: () -> Boolean = { true },
) {
    private var owned = false

    fun enter(): Boolean {
        if (!recoverInterrupted() || !stillWanted() || !saveRecovery(true)) return false
        owned = true
        val commands = BydDiLink3ClusterMode.CREATE_DISPLAY.take(2) + listOf(
            BydDiLink3ClusterMode.Mode.PROJECTION.entryCommand!!,
            BydDiLink3ClusterMode.Mode.PROJECTION.command,
        )
        for ((index, command) in commands.withIndex()) {
            if (!stillWanted() || !BydDiLink3ClusterMode.accepted(run(command))) {
                restoreStock()
                return false
            }
            if (index < commands.lastIndex) {
                try {
                    stepDelay()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    restoreStock()
                    return false
                }
            }
        }
        return true
    }

    fun recoverInterrupted(): Boolean =
        if (owned || loadRecovery()) restoreStock() else true

    private fun restoreStock(): Boolean {
        if (!owned && !loadRecovery()) return true
        if (!BydDiLink3ClusterMode.accepted(run(BydDiLink3ClusterMode.Mode.STOCK.command))) return false
        if (!saveRecovery(false)) return false
        owned = false
        return true
    }
}
