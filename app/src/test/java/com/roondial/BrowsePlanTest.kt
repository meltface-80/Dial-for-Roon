package com.roondial

import com.roondial.roon.ActionKind
import com.roondial.roon.BrowseItem
import com.roondial.roon.BrowsePlan
import com.roondial.roon.BrowseSessionPool
import com.roondial.roon.classifyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

class BrowsePagingTest {

    private fun item(title: String, hint: String? = null, key: String? = "k-$title") =
        BrowseItem(title, null, key, hint)

    private fun headers(n: Int) =
        (1..n).map { BrowseItem("Header $it", null, null, "header") }

    @Test
    fun asksForMoreWhenThePageHeldNothingUsable() {
        // The bug this pins: a level whose first page is all headers, or whose
        // playable rows start after row fifty, reported "nothing to play" over
        // a library that had it. One page is not the level.
        val step = BrowsePlan.next(null, headers(50), total = 300)
        assertEquals(BrowsePlan.Step.LoadMore(50), step)
    }

    @Test
    fun stopsPagingOnceTheWholeLevelHasBeenRead() {
        assertTrue(BrowsePlan.next(null, headers(40), total = 40) is BrowsePlan.Step.GiveUp)
    }

    @Test
    fun doesNotPageAnEmptyLevel() {
        // Nothing came back at all: paging on would loop against a Core that
        // has already said there is nothing here.
        assertTrue(BrowsePlan.next(null, emptyList(), total = 900) is BrowsePlan.Step.GiveUp)
    }

    @Test
    fun stopsPagingAtTheScanLimit() {
        val step = BrowsePlan.next(null, headers(BrowsePlan.MAX_SCAN), total = 100_000)
        assertTrue(step is BrowsePlan.Step.GiveUp)
    }

    @Test
    fun takesTheFirstUsableRowFromTheAccumulatedPages() {
        val loaded = headers(50) + item("Iron Maiden", "list")
        assertEquals(
            BrowsePlan.Step.Descend("k-Iron Maiden", "Iron Maiden"),
            BrowsePlan.next(null, loaded, total = 300)
        )
    }

    @Test
    fun doesNotPageAnActionMenuThatSimplyCannotPlay() {
        // A menu of favourites and edits is the wrong menu, not a short one,
        // and paging it would send loads at a Core for no reason.
        val step = BrowsePlan.next(
            "action_list",
            listOf(item("Add to Library", "action"), item("Delete", "action")),
            total = 500
        )
        assertTrue(step is BrowsePlan.Step.GiveUp)
    }
}

class ActionClassificationTest {

    private fun item(title: String) = BrowseItem(title, null, "k-$title", "action")

    @Test
    fun namesWhatEachActionDoes() {
        assertEquals(ActionKind.PLAY_NOW, classifyAction("Play Now"))
        assertEquals(ActionKind.PLAY_NOW, classifyAction("play now"))
        assertEquals(ActionKind.PLAY_NEXT, classifyAction("Play Next"))
        assertEquals(ActionKind.PLAY_NEXT, classifyAction("Add Next"))
        assertEquals(ActionKind.PLAY_NEXT, classifyAction("Add to Queue"))
        assertEquals(ActionKind.QUEUE, classifyAction("Queue"))
        assertEquals(ActionKind.SHUFFLE, classifyAction("Shuffle"))
        assertEquals(ActionKind.RADIO, classifyAction("Start Radio"))
        assertEquals(ActionKind.PLAY, classifyAction("Play Album"))
        assertEquals(ActionKind.PLAY, classifyAction("Play From Here"))
        assertEquals(ActionKind.OTHER, classifyAction("Add to Library"))
        assertEquals(ActionKind.OTHER, classifyAction(null))
    }

    @Test
    fun neverQueuesWhenAskedToPlay() {
        // "Play Next" starts with "play", so a startsWith fallback picks it and
        // the room stays silent while the app reports success. Recognising what
        // an action does, rather than how it is spelled, is the whole point.
        val step = BrowsePlan.next(
            "action_list",
            listOf(item("Play Next"), item("Add to Queue"), item("Shuffle"))
        )
        assertEquals(BrowsePlan.Step.Play("k-Shuffle", "Shuffle"), step)
    }

    @Test
    fun prefersPlayingTheThingOverStartingRadioFromIt() {
        val step = BrowsePlan.next(
            "action_list",
            listOf(item("Start Radio"), item("Play Album"), item("Play Next"))
        )
        assertEquals(BrowsePlan.Step.Play("k-Play Album", "Play Album"), step)
    }

    @Test
    fun givesUpRatherThanQueueingWhenNothingPlays() {
        val step = BrowsePlan.next(
            "action_list",
            listOf(item("Play Next"), item("Add to Queue"))
        )
        assertTrue(step is BrowsePlan.Step.GiveUp)
    }
}

class BrowseSessionPoolTest {

    @Test
    fun reusesAKeyOnceItIsGivenBack() {
        // Roon holds server-side state for every session key it is shown, for
        // as long as the extension stays connected. A key per search means a
        // Core carrying one dead browse session per voice command.
        val pool = BrowseSessionPool()
        val first = pool.acquire()
        pool.release(first)
        assertEquals(first, pool.acquire())
    }

    @Test
    fun mintsAKeyPerSimultaneousWalk() {
        val pool = BrowseSessionPool()
        val a = pool.acquire()
        val b = pool.acquire()
        assertNotEquals(a, b)
        pool.release(a)
        pool.release(b)
        assertEquals(2, pool.idleCount())
    }

    @Test
    fun neverHandsTheSameKeyToTwoWalks() {
        // A double release is the one bug a pool can have that silently
        // corrupts two searches at once, so it is refused rather than trusted.
        val pool = BrowseSessionPool()
        val key = pool.acquire()
        pool.release(key)
        pool.release(key)
        assertEquals(1, pool.idleCount())
        assertNotEquals(pool.acquire(), pool.acquire())
    }

    @Test
    fun forgetsEverythingWhenTheCoreGoesAway() {
        val pool = BrowseSessionPool()
        pool.release(pool.acquire())
        pool.reset()
        assertEquals(0, pool.idleCount())
    }
}

class BrowseMergeTest {

    private fun page(from: Int, n: Int) =
        (from until from + n).map { BrowseItem("row $it", null, "k$it", "list") }

    @Test
    fun appendsAPageThatStartsWhereTheLastOneEnded() {
        val merged = BrowsePlan.merge(page(0, 50), page(50, 50), at = 50)
        assertEquals(100, merged?.size)
        assertEquals("row 99", merged?.last()?.title)
    }

    @Test
    fun refusesAPageThatRepeatsWhatIsAlreadyHeld() {
        // A Core that ignores the offset re-sends its first page. Appending it
        // grows the list without ever reaching the level's count, so the walk
        // pages for ever: an endless request loop against the user's Core.
        assertNull(BrowsePlan.merge(page(0, 50), page(0, 50), at = 0))
    }

    @Test
    fun refusesAnEmptyPage() {
        assertNull(BrowsePlan.merge(page(0, 50), emptyList(), at = 50))
    }

    @Test
    fun rewindsWhenRoonAnswersAtAnEarlierOffsetThanAsked() {
        // Truncating to the echoed offset keeps the list in list order, which
        // is what "the first playable row" means.
        val merged = BrowsePlan.merge(page(0, 50), page(20, 40), at = 20)
        assertEquals(60, merged?.size)
        assertEquals("row 20", merged?.get(20)?.title)
    }

    @Test
    fun takesTheFirstPage() {
        assertEquals(50, BrowsePlan.merge(emptyList(), page(0, 50), at = 0)?.size)
    }
}
