package com.roondial

import com.roondial.roon.BrowseItem
import com.roondial.roon.BrowsePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsePlanTest {

    private fun item(title: String, hint: String? = null, key: String? = "k-$title") =
        BrowseItem(title, null, key, hint)

    @Test
    fun descendsIntoTheFirstRealResult() {
        val step = BrowsePlan.next(
            null,
            listOf(item("Artists", "list"), item("Albums", "list"))
        )
        assertEquals(BrowsePlan.Step.Descend("k-Artists", "Artists"), step)
    }

    @Test
    fun skipsHeadersWhichCannotBeOpened() {
        val step = BrowsePlan.next(
            null,
            listOf(
                BrowseItem("Search results", null, null, "header"),
                item("Iron Maiden", "list")
            )
        )
        assertEquals(BrowsePlan.Step.Descend("k-Iron Maiden", "Iron Maiden"), step)
    }

    @Test
    fun playsRatherThanQueuesWhenItReachesTheActions() {
        // Getting this wrong queues music silently instead of playing it, or
        // worse picks a destructive action.
        val step = BrowsePlan.next(
            "action_list",
            listOf(
                item("Add Next", "action"),
                item("Queue", "action"),
                item("Play Now", "action"),
                item("Start Radio", "action")
            )
        )
        assertEquals(BrowsePlan.Step.Play("k-Play Now", "Play Now"), step)
    }

    @Test
    fun prefersPlayNowOverOtherPlayVerbs() {
        val step = BrowsePlan.next(
            "action_list",
            listOf(item("Start Radio", "action"), item("Play Now", "action"))
        )
        assertEquals(BrowsePlan.Step.Play("k-Play Now", "Play Now"), step)
    }

    @Test
    fun fallsBackToAnyPlayVerbItDoesNotKnow() {
        val step = BrowsePlan.next(
            "action_list",
            listOf(item("Queue", "action"), item("Play Everything", "action"))
        )
        assertEquals(BrowsePlan.Step.Play("k-Play Everything", "Play Everything"), step)
    }

    @Test
    fun refusesToGuessWhenNoActionPlays() {
        val step = BrowsePlan.next(
            "action_list",
            listOf(item("Add to Library", "action"), item("Delete", "action"))
        )
        assertTrue(step is BrowsePlan.Step.GiveUp)
    }

    @Test
    fun givesUpOnAnEmptyResult() {
        assertTrue(BrowsePlan.next(null, emptyList()) is BrowsePlan.Step.GiveUp)
        assertTrue(
            BrowsePlan.next(null, listOf(BrowseItem("Nothing", null, null, "header")))
                is BrowsePlan.Step.GiveUp
        )
    }

    @Test
    fun treatsALooseActionItemAsAnAction() {
        // Some levels mix a list with a single action rather than flagging the
        // whole level as an action list.
        val step = BrowsePlan.next(
            null,
            listOf(item("Play Now", "action"), item("Tracks", "list"))
        )
        assertEquals(BrowsePlan.Step.Play("k-Play Now", "Play Now"), step)
    }
}
