package com.roondial.roon

import org.json.JSONObject
import java.util.ArrayDeque

/** One row in a Roon browse level. */
data class BrowseItem(
    val title: String,
    val subtitle: String?,
    val itemKey: String?,
    /** null, "action", "action_list", "list" or "header". */
    val hint: String?
) {
    companion object {
        fun parse(o: JSONObject) = BrowseItem(
            title = o.str("title"),
            subtitle = o.str("subtitle").takeIf { it.isNotEmpty() },
            itemKey = o.str("item_key").takeIf { it.isNotEmpty() },
            hint = o.str("hint").takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * Hands out browse session keys, and takes them back.
 *
 * Roon keeps the browse cursor server-side, keyed by `multi_session_key`, and
 * holds that state for as long as the extension stays connected. Minting a key
 * per search — which is what this did — means a Core that has answered two
 * hundred voice commands is holding two hundred dead browse sessions. Pooling
 * caps what it holds at the number of searches running at once, which is one.
 *
 * Reuse is safe because every search starts with `pop_all`, which discards
 * whatever the previous walk left on that key.
 */
class BrowseSessionPool(private val prefix: String = "dial") {
    private val free = ArrayDeque<String>()
    private var seq = 0

    @Synchronized
    fun acquire(): String = free.pollLast() ?: "$prefix-s${++seq}"

    /** Called exactly once per [acquire]: two walks sharing a key corrupt each other. */
    @Synchronized
    fun release(key: String) {
        if (!free.contains(key)) free.addLast(key)
    }

    /** Keys are meaningless to a Core that has never seen them. */
    @Synchronized
    fun reset() {
        free.clear()
    }

    @Synchronized
    fun idleCount(): Int = free.size
}

/** Roon's own vocabulary for the entries of a Play menu. */
object ActionKind {
    const val PLAY_NOW = "play_now"

    /** "Play Album", "Play Artist", "Play From Here" — starts this thing now. */
    const val PLAY = "play"

    /** "Play Next", "Add Next" — queues after what is playing, so never a search result. */
    const val PLAY_NEXT = "play_next"
    const val SHUFFLE = "shuffle"
    const val RADIO = "radio"
    const val QUEUE = "queue"
    const val OTHER = "other"
}

/**
 * Classifies one Play-menu entry by what it does.
 *
 * Matching action titles exactly is how "turn down volume" ended up playing an
 * album: any list of literal titles is a guess at Roon's wording, and the
 * fallback for when the guess missed — "anything starting with play" — will
 * happily pick "Play Next", which queues silently and starts nothing.
 *
 * Order matters. "Play Next" starts with "play", so it has to be recognised
 * before the general play case.
 */
fun classifyAction(title: String?): String {
    val t = (title ?: "").trim().lowercase()
    return when {
        t.isEmpty() -> ActionKind.OTHER
        Regex("play\\s*now").containsMatchIn(t) -> ActionKind.PLAY_NOW
        Regex("add\\s*next|play\\s*next|add\\s*to\\s*queue").containsMatchIn(t) -> ActionKind.PLAY_NEXT
        t.contains("queue") -> ActionKind.QUEUE
        t.contains("shuffle") -> ActionKind.SHUFFLE
        t.contains("radio") -> ActionKind.RADIO
        t.startsWith("play") -> ActionKind.PLAY
        else -> ActionKind.OTHER
    }
}

/**
 * Decides how to get from a search result to music playing.
 *
 * Roon's browse API is a hierarchy walk, not a query language: a search returns
 * categories, a category returns matches, and a match returns a list of actions
 * one of which starts playback. Rather than hard-coding the shape of that tree —
 * which differs between an artist, an album and a track — this walks it, taking
 * the first real result at each level until it reaches a list of actions, and
 * then picks the one that plays.
 *
 * Kept separate from the network so the walk can be tested without a Core.
 */
object BrowsePlan {

    /** How many rows to ask for at a time. */
    const val PAGE = 50

    /** As deep into one level as this will page before giving up on it. */
    const val MAX_SCAN = 500

    /** Which action starts playback, best first. */
    private val PLAY_PREFERENCE = listOf(
        ActionKind.PLAY_NOW,
        ActionKind.PLAY,
        ActionKind.SHUFFLE,
        ActionKind.RADIO
    )

    /** What a browse response means for a walk in progress. */
    sealed class Outcome {
        /** Load this level and keep looking. */
        object KeepWalking : Outcome()
        object Playing : Outcome()
        data class Stop(val message: String) : Outcome()
    }

    /**
     * Reads a browse response.
     *
     * [played] means the previous request was the play action. Once it has
     * been sent the walk is over whatever comes back: Roon does not always
     * answer "none", and may hand back the level it popped to. Treating that
     * as "keep looking" reported failure over music that was already playing,
     * and sent a second play action — which made playback jump.
     */
    fun afterBrowse(
        played: Boolean,
        action: String,
        isError: Boolean,
        message: String?
    ): Outcome = when {
        played && isError -> Stop(message)
        played -> Outcome.Playing
        action == "list" -> Outcome.KeepWalking
        action == "message" -> Stop(message)
        // Reaching "none" — or a list-mutation instruction — without having
        // invoked a play action means Roon rendered nothing and nothing was
        // asked to play. That is a dead end, not success.
        else -> Stop(null)
    }

    private fun Stop(message: String?) =
        Outcome.Stop(message?.takeIf { it.isNotBlank() } ?: "Roon had nothing for that")

    sealed class Step {
        /** Open this item and look again. */
        data class Descend(val itemKey: String, val title: String) : Step()

        /** This item starts playback. */
        data class Play(val itemKey: String, val title: String) : Step()

        /** This page held nothing usable, but the level has more rows. */
        data class LoadMore(val offset: Int) : Step()

        /** Nothing here can be played. */
        data class GiveUp(val reason: String) : Step()
    }

    /**
     * @param listHint the level's own hint: "action_list" means these are verbs.
     * @param items every row loaded from this level so far, in order.
     * @param total how many rows Roon says the level holds.
     */
    fun next(listHint: String?, items: List<BrowseItem>, total: Int = items.size): Step {
        val usable = items.filter { it.itemKey != null && it.hint != "header" }
        if (usable.isEmpty()) return more(items, total) ?: Step.GiveUp("nothing to play")

        val onActionLevel = listHint == "action_list"
        val actions = usable.filter { onActionLevel || it.hint == "action" }
        if (actions.isNotEmpty()) {
            val byKind = actions.groupBy { classifyAction(it.title) }
            val best = PLAY_PREFERENCE.firstNotNullOfOrNull { byKind[it]?.firstOrNull() }

            if (best?.itemKey == null) {
                // Every action here queues, favourites or edits. Paging on
                // cannot change that: it is the wrong menu, not a short one.
                return Step.GiveUp("nothing here plays")
            }
            // Only a leaf plays. "Play Album" and "Play Artist" are usually
            // wrappers: opening one shows Play Now / Shuffle / Start Radio, and
            // treating the wrapper as the play means reporting success having
            // opened a submenu and started nothing. A missing hint on an
            // action level is a known Roon quirk for tags — treat it as a leaf.
            return if (best.hint == "action_list") {
                Step.Descend(best.itemKey, best.title)
            } else {
                Step.Play(best.itemKey, best.title)
            }
        }

        val first = usable.first()
        return Step.Descend(first.itemKey!!, first.title)
    }

    /**
     * Folds one loaded page into what a level has yielded so far, or returns
     * null when the load did not advance.
     *
     * [at] is where Roon says the page actually starts, which is not always
     * where it was asked to start. A Core that ignores the offset and re-sends
     * its first page would otherwise have that page appended to itself for
     * ever, because the level's own count never drops — a request loop against
     * the Core with no way out. Trusting the echo and then insisting the level
     * grew makes that terminate on the second page instead.
     */
    fun merge(loaded: List<BrowseItem>, page: List<BrowseItem>, at: Int): List<BrowseItem>? {
        val kept = if (at in 0 until loaded.size) loaded.subList(0, at) else loaded
        val merged = kept + page
        return if (merged.size > loaded.size) merged else null
    }

    /**
     * A level that holds more than has been loaded gets another page.
     *
     * Roon's search result level opens with a run of headers, and a category
     * with a lot of matches can push the first playable row past the end of the
     * first page. Stopping at one page meant those searches reported "nothing
     * to play" over a library that had it.
     */
    private fun more(loaded: List<BrowseItem>, total: Int): Step? {
        val next = loaded.size
        return if (next in 1 until minOf(total, MAX_SCAN)) Step.LoadMore(next) else null
    }
}
