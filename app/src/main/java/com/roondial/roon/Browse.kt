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

        val actions = usable.filter { listHint == "action_list" || it.hint == "action" }
        if (actions.isNotEmpty()) {
            for (wanted in PLAY_ACTIONS) {
                val match = actions.firstOrNull { it.title.trim().lowercase() == wanted }
                if (match?.itemKey != null) return Step.Play(match.itemKey, match.title)
            }
            // An unfamiliar action list: anything that starts with "play" beats
            // guessing, and guessing wrong here queues or deletes something.
            val anyPlay = actions.firstOrNull { it.title.trim().lowercase().startsWith("play") }
            return if (anyPlay?.itemKey != null) {
                Step.Play(anyPlay.itemKey, anyPlay.title)
            } else {
                Step.GiveUp("no play action in \"${actions.joinToString { it.title }}\"")
            }
        }

        val first = usable.first()
        return Step.Descend(first.itemKey!!, first.title)
    }
}
