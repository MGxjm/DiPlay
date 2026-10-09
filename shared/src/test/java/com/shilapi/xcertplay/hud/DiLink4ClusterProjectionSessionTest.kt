package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class DiLink4ClusterProjectionSessionTest {
    @Test fun entersUsingTheDiLink3ContainerSequenceAndKeepsRecoveryPending() {
        val commands = mutableListOf<String>()
        var pending = false
        val session = DiLink4ClusterProjectionSession(
            run = { commands += it; "Result: Parcel(00000000 00000000 '........')" },
            loadRecovery = { pending },
            saveRecovery = { pending = it; true },
            stepDelay = {},
        )

        assertTrue(session.enter())
        assertEquals(
            BydDiLink3ClusterMode.CREATE_DISPLAY.take(2) + listOf(
                BydDiLink3ClusterMode.Mode.PROJECTION.entryCommand!!,
                BydDiLink3ClusterMode.Mode.PROJECTION.command,
            ),
            commands,
        )
        assertTrue(pending)
    }

    @Test fun failedEntryCompensatesToStockAndClearsRecoveryWhenAccepted() {
        val commands = mutableListOf<String>()
        var pending = false
        val session = DiLink4ClusterProjectionSession(
            run = {
                commands += it
                if (commands.size == 3) "Error" else "Result: Parcel(00000000 00000000 '........')"
            },
            loadRecovery = { pending },
            saveRecovery = { pending = it; true },
            stepDelay = {},
        )

        assertFalse(session.enter())
        assertEquals(BydDiLink3ClusterMode.Mode.STOCK.command, commands.last())
        assertFalse(pending)
    }

    @Test fun interruptedSessionRestoresStockBeforeClearingItsJournal() {
        val commands = mutableListOf<String>()
        var pending = true
        val session = DiLink4ClusterProjectionSession(
            run = { commands += it; "Result: Parcel(00000000 00000000 '........')" },
            loadRecovery = { pending },
            saveRecovery = { pending = it; true },
            stepDelay = {},
        )

        assertTrue(session.recoverInterrupted())
        assertEquals(listOf(BydDiLink3ClusterMode.Mode.STOCK.command), commands)
        assertFalse(pending)
    }
}
