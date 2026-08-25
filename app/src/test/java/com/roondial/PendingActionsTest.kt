package com.roondial

import com.roondial.roon.PendingActions
import com.roondial.roon.RoonClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionsTest {

    @Test
    fun drainsInOrderAndEmpties() {
        val queue = PendingActions()
        queue.add(RoonClient.Action.PLAY_PAUSE, 1_000)
        queue.add(RoonClient.Action.VOLUME_UP, 1_200)

        assertEquals(
            listOf(RoonClient.Action.PLAY_PAUSE, RoonClient.Action.VOLUME_UP),
            queue.drain(1_500)
        )
        assertTrue(queue.isEmpty())
        assertTrue(queue.drain(1_600).isEmpty())
    }

    @Test
    fun dropsPressesTheUserHasLongGivenUpOn() {
        val queue = PendingActions(expiryMs = 20_000)
        queue.add(RoonClient.Action.NEXT, 0)
        queue.add(RoonClient.Action.PREVIOUS, 19_000)

        // A press from 25s ago firing now would skip a track out of nowhere.
        assertEquals(listOf(RoonClient.Action.PREVIOUS), queue.drain(25_000))
    }

    @Test
    fun staleEntriesStillClearTheQueue() {
        val queue = PendingActions(expiryMs = 1_000)
        queue.add(RoonClient.Action.NEXT, 0)
        assertTrue(queue.drain(5_000).isEmpty())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun aJammedButtonCannotGrowTheQueueWithoutBound() {
        val queue = PendingActions(capacity = 3)
        repeat(10) { queue.add(RoonClient.Action.VOLUME_UP, it.toLong()) }
        assertEquals(3, queue.drain(10).size)
    }

    @Test
    fun startsEmpty() {
        assertTrue(PendingActions().isEmpty())
        assertFalse(PendingActions().let { it.add(RoonClient.Action.NEXT, 0); it.isEmpty() })
    }
}
