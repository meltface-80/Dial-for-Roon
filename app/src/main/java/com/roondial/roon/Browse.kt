package com.roondial.roon

import org.json.JSONObject

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
            title = o.optString("title"),
            subtitle = o.optString("subtitle").takeIf { it.isNotEmpty() },
            itemKey = o.optString("item_key").takeIf { it.isNotEmpty() },
            hint = o.optString("hint").takeIf { it.isNotEmpty() }
        )
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

    /** Actions that start playback, best first. */
    private val PLAY_ACTIONS = listOf(
        "play now",
        "play album",
        "play artist",
        "play from here",
        "start radio"
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

        /** Nothing here can be played. */
        data class GiveUp(val reason: String) : Step()
    }

    /**
     * @param listHint the level's own hint: "action_list" means these are verbs.
     * @param items the level's rows.
     */
    fun next(listHint: String?, items: List<BrowseItem>): Step {
        val usable = items.filter { it.itemKey != null && it.hint != "header" }
        if (usable.isEmpty()) return Step.GiveUp("nothing to play")

        val onActionLevel = listHint == "action_list"
        val actions = usable.filter { onActionLevel || it.hint == "action" }
        if (actions.isNotEmpty()) {
            val best = PLAY_ACTIONS.firstNotNullOfOrNull { wanted ->
                actions.firstOrNull { it.title.trim().lowercase() == wanted }
            } ?: actions.firstOrNull { it.title.trim().lowercase().startsWith("play") }

            if (best?.itemKey == null) {
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
}
