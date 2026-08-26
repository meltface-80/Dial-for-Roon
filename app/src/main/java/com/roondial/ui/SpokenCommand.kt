package com.roondial.ui

/**
 * Turns what was said into an action.
 *
 * The two ways of getting this wrong are not equally bad. Mistaking a title
 * for a command costs one tap. Mistaking a command for a title replaces the
 * queue and starts playing — so "turn down the volume" ends up playing "Turn
 * Down for What", loudly, which is the opposite of what was asked for. The
 * rules below are shaped around that asymmetry.
 *
 * Rather than matching a list of exact phrases — which a speech recogniser
 * defeats immediately by dropping "the" or reordering words — an utterance is
 * a command only if EVERY word in it belongs to a small closed vocabulary and
 * at least one is a verb or a direction. That absorbs the variance, and what
 * is deliberately left out of the vocabulary does most of the work: "me", "to",
 * "on" and "for" are not command words, which is what saves "Turn Me On",
 * "Next to Me" and "Turn Down for What" without knowing anything about music.
 */
object SpokenCommand {

    enum class Amount { SMALL, NORMAL, LARGE }

    sealed class Intent {
        data class Search(val query: String) : Intent()
        object Play : Intent()
        object Pause : Intent()
        object Next : Intent()
        object Previous : Intent()
        object Mute : Intent()
        object Unmute : Intent()
        data class VolumeUp(val amount: Amount) : Intent()
        data class VolumeDown(val amount: Amount) : Intent()
        /** "set the volume to 40" — a percentage of the output's range. */
        data class VolumePercent(val percent: Int) : Intent()
        object Nothing : Intent()
    }

    private val PAUSE_WORDS = setOf("pause", "stop", "shush")
    private val PLAY_WORDS = setOf("resume", "unpause", "continue", "carry")
    private val NEXT_WORDS = setOf("next", "skip", "forward")
    private val PREVIOUS_WORDS = setOf("previous", "back")
    private val MUTE_WORDS = setOf("mute", "silence")
    private val UNMUTE_WORDS = setOf("unmute")
    private val UP_WORDS = setOf("up", "louder", "higher", "raise", "increase")
    private val DOWN_WORDS = setOf("down", "quieter", "softer", "lower", "decrease", "quiet")

    /** Words that make an utterance an instruction rather than a name. */
    private val VERBS = setOf(
        "pause", "stop", "shush", "resume", "unpause", "continue", "carry",
        "next", "skip", "forward", "previous", "back", "go",
        "mute", "unmute", "silence", "turn", "set", "make", "keep"
    )

    private val DIRECTIONS = UP_WORDS + DOWN_WORDS

    /** Words allowed to appear in a command without being one. */
    private val OBJECTS = setOf(
        "it", "this", "that", "the", "volume", "music", "sound",
        "track", "song", "playback", "one", "again", "off", "on"
    )

    private val FILLERS = setOf("please", "just", "a", "bit", "little", "now", "slightly", "much", "way", "lot", "loads")

    private val VOCABULARY = VERBS + DIRECTIONS + OBJECTS + FILLERS

    /**
     * Words that are commands on their own but are also real records — Stop,
     * Pause, Next, Louder, Mute, Quiet. Resolved by what the zone is doing:
     * nobody says "pause" to a silent room.
     */
    /** Meaningless alone — "up" is not a volume request. */
    private val BARE_DIRECTION_ONLY = setOf("up", "down", "higher", "lower")

    private val AMBIGUOUS_ALONE = setOf(
        "stop", "pause", "next", "previous", "back", "skip", "louder",
        "quieter", "softer", "quiet", "mute", "up", "down", "forward", "silence"
    )

    /** Saying a play verb means a search, whatever follows. Absolute. */
    private val PLAY_VERBS = listOf(
        "play some music by ", "play some music from ", "play music by ",
        "play me some ", "play some ", "play me ", "play the album ",
        "play album ", "play the track ", "play track ", "play the song ",
        "play song ", "play artist ", "play ",
        "put on some ", "put on the ", "put on ", "listen to ", "let's hear ",
        "search for ", "find me ", "find "
    )

    private val LEADING_COURTESY = listOf("please ", "can you ", "could you ", "would you ")

    private val SMALL_WORDS = setOf("bit", "little", "slightly", "touch", "tad")
    private val LARGE_WORDS = setOf("lot", "much", "way", "loads")

    /**
     * "to" means an absolute level and "by" a relative change, whatever verb
     * precedes it: "turn the volume down to 90 percent" sets 90, it does not
     * subtract 90. Home Assistant's grammar makes the same distinction.
     */
    private val SET_VOLUME = Regex(
        "^(?:(?:set|change|turn|increase|decrease|make|put) )?(?:the )?volume ?" +
            "(?:up |down )?(?:to|at) (\\d{1,3})(?: ?(?:%|percent))?$"
    )

    /**
     * Transport phrases that begin with a play verb. Without these the
     * play-verb rule sends "play the previous song" to the library as a
     * search — the one place that rule is too eager.
     */
    private val PLAY_TRANSPORT = mapOf(
        "play next" to Intent.Next,
        "play next song" to Intent.Next,
        "play next track" to Intent.Next,
        "play the next song" to Intent.Next,
        "play the next track" to Intent.Next,
        "go to the next song" to Intent.Next,
        "go to the next track" to Intent.Next,
        "play previous" to Intent.Previous,
        "play previous song" to Intent.Previous,
        "play previous track" to Intent.Previous,
        "play the previous song" to Intent.Previous,
        "play the previous track" to Intent.Previous,
        "play the last song" to Intent.Previous,
        "play the last track" to Intent.Previous,
        "play the last song again" to Intent.Previous,
        "play the last track again" to Intent.Previous,
        "go to the previous song" to Intent.Previous,
        "go to the previous track" to Intent.Previous,
        "replay" to Intent.Previous,
        "replay the last song" to Intent.Previous,
        "replay the last track" to Intent.Previous
    )

    /** A play request with no real content: start something, don't search. */
    private val DEGENERATE = setOf(
        "the music", "music", "some music", "the tunes", "tunes",
        "something", "anything", "some", "it", "the song", "a song"
    )

    private val NUMBER_WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        "hundred" to 100
    )

    /**
     * @param isPlaying whether the zone is playing, used only to resolve a
     *        single word that is both a command and a record.
     */
    fun parse(spoken: String, isPlaying: Boolean = true): Intent {
        val said = stripCourtesy(spoken.trim())
        if (said.isBlank()) return Intent.Nothing

        val normalised = normalise(said)
        // Transport phrases that happen to start with "play", checked before
        // the play-verb rule that would otherwise search for them.
        PLAY_TRANSPORT[normalised]?.let { return it }

        // Otherwise a play verb is always a search, so nothing can claim
        // "play Pause" or "play Volume 4".
        for (verb in PLAY_VERBS) {
            if (said.startsWith(verb, ignoreCase = true)) {
                val query = said.substring(verb.length).trim().trimEnd('.', '!', '?')
                if (query.isEmpty()) return Intent.Play
                // "play some music" asks for music, not for a record called
                // "some music".
                if (DEGENERATE.contains(normalise(query))) return Intent.Play
                return Intent.Search(query)
            }
        }

        val text = normalised
        if (text.isEmpty()) return Intent.Nothing
        if (text == "play") return Intent.Play

        setVolume(text)?.let { return it }

        val words = text.split(' ').filter { it.isNotEmpty() }
        val isCommand = words.all { VOCABULARY.contains(it) } &&
            words.any { VERBS.contains(it) || DIRECTIONS.contains(it) }

        if (isCommand) {
            // A bare direction is never a volume command on its own: both open
            // grammars surveyed require a "volume" word alongside it.
            if (words.size == 1 && BARE_DIRECTION_ONLY.contains(words[0])) {
                return Intent.Search(said.trimEnd('.', '!', '?'))
            }
            // One word that is also a record: only a command if there is
            // something to command.
            if (words.size == 1 && AMBIGUOUS_ALONE.contains(words[0]) && !isPlaying) {
                return Intent.Search(said.trimEnd('.', '!', '?'))
            }
            classify(words)?.let { return it }
        }

        return Intent.Search(said.trimEnd('.', '!', '?'))
    }

    private fun classify(words: List<String>): Intent? {
        val amount = when {
            words.any { SMALL_WORDS.contains(it) } -> Amount.SMALL
            words.any { LARGE_WORDS.contains(it) } -> Amount.LARGE
            else -> Amount.NORMAL
        }
        return when {
            words.any { UNMUTE_WORDS.contains(it) } -> Intent.Unmute
            words.contains("sound") && words.contains("on") -> Intent.Unmute
            words.any { MUTE_WORDS.contains(it) } -> Intent.Mute
            words.any { UP_WORDS.contains(it) } -> Intent.VolumeUp(amount)
            words.any { DOWN_WORDS.contains(it) } -> Intent.VolumeDown(amount)
            words.any { NEXT_WORDS.contains(it) } -> Intent.Next
            words.any { PREVIOUS_WORDS.contains(it) } -> Intent.Previous
            words.any { PAUSE_WORDS.contains(it) } -> Intent.Pause
            words.any { PLAY_WORDS.contains(it) } -> Intent.Play
            else -> null
        }
    }

    /**
     * "set the volume to 40". Requires the verb and the preposition: a bare
     * "volume four" is Black Sabbath, and reading it as a level would drop the
     * output to near silence.
     */
    private fun setVolume(text: String): Intent? {
        val spelled = text.split(' ').joinToString(" ") {
            NUMBER_WORDS[it]?.toString() ?: it
        }
        val match = SET_VOLUME.find(spelled) ?: return null
        val percent = match.groupValues[1].toIntOrNull() ?: return null
        return Intent.VolumePercent(percent.coerceIn(0, 100))
    }

    /**
     * Only at the edges, and never "thank you" — that is the front half of a
     * number one single, not politeness to be discarded.
     */
    private fun stripCourtesy(spoken: String): String {
        var text = spoken
        var changed = true
        while (changed) {
            changed = false
            for (prefix in LEADING_COURTESY) {
                if (text.startsWith(prefix, ignoreCase = true)) {
                    text = text.substring(prefix.length).trim(); changed = true; break
                }
            }
            val trimmed = text.trimEnd('.', '!', '?')
            if (trimmed.endsWith(" please", ignoreCase = true)) {
                text = trimmed.dropLast(7).trim(); changed = true
            }
        }
        return text
    }

    private fun normalise(spoken: String): String =
        spoken.lowercase()
            .replace("per cent", "percent")
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it == '\'' }
            .replace(Regex("\\s+"), " ")
            .trim()
}
