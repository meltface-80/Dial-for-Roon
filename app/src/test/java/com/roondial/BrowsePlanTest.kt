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
    fun opensAPlayWrapperRatherThanClaimingItPlayed() {
        // "Play Album" and "Play Artist" are usually action_list wrappers: they
        // open a submenu holding the real Play Now. Treating one as the play
        // reports success having started nothing.
        val step = BrowsePlan.next(
            null,
            listOf(item("Play Album", "action_list"), item("Track one", "action_list"))
        )
        assertEquals(BrowsePlan.Step.Descend("k-Play Album", "Play Album"), step)
    }

    @Test
    fun treatsAMissingHintOnAnActionLevelAsALeaf() {
        // Roon omits the hint on tag actions; pyroon works around the same bug.
        val step = BrowsePlan.next(
            "action_list",
            listOf(BrowseItem("Play Now", null, "k1", null))
        )
        assertEquals(BrowsePlan.Step.Play("k1", "Play Now"), step)
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

class BrowseOutcomeTest {

    @Test
    fun stopsOnceThePlayActionHasBeenSent() {
        // The bug this pins: Roon answered a play action with a list rather
        // than "none", the walk carried on, reported failure over music that
        // was already playing, and fired a second play action on the way.
        assertEquals(
            BrowsePlan.Outcome.Playing,
            BrowsePlan.afterBrowse(played = true, action = "list", isError = false, message = "")
        )
        assertEquals(
            BrowsePlan.Outcome.Playing,
            BrowsePlan.afterBrowse(played = true, action = "none", isError = false, message = "")
        )
    }

    @Test
    fun anErrorAfterPlayingIsStillAnError() {
        val outcome =
            BrowsePlan.afterBrowse(true, "message", isError = true, message = "Zone is busy")
        assertEquals(BrowsePlan.Outcome.Stop("Zone is busy"), outcome)
    }

    @Test
    fun aDeadEndIsNotSuccess() {
        // Reaching "none" without having asked for anything to play means Roon
        // rendered nothing — the mirror image of the bug that reported failure
        // over music that was playing.
        assertTrue(
            BrowsePlan.afterBrowse(false, "none", isError = false, message = "")
                is BrowsePlan.Outcome.Stop
        )
        assertTrue(
            BrowsePlan.afterBrowse(false, "remove_item", isError = false, message = "")
                is BrowsePlan.Outcome.Stop
        )
    }

    @Test
    fun keepsWalkingThroughLevels() {
        assertEquals(
            BrowsePlan.Outcome.KeepWalking,
            BrowsePlan.afterBrowse(false, "list", isError = false, message = "")
        )
    }

    @Test
    fun relaysWhatRoonSaid() {
        assertEquals(
            BrowsePlan.Outcome.Stop("No results"),
            BrowsePlan.afterBrowse(false, "message", isError = false, message = "No results")
        )
    }

    @Test
    fun stillSaysSomethingWhenRoonSendsAnEmptyMessage() {
        val outcome = BrowsePlan.afterBrowse(false, "message", isError = false, message = "")
        assertTrue((outcome as BrowsePlan.Outcome.Stop).message.isNotBlank())
    }
}
