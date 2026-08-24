package com.roondial.roon

/**
 * What a control surface needs from Roon: the verbs, and nothing else.
 *
 * The media session's player is written against this rather than the whole
 * client, which keeps the mapping from media3 commands to Roon commands
 * testable without a Core on the other end of a socket.
 */
interface ZoneControl {
    /** "play", "pause", "playpause", "stop", "previous" or "next". */
    fun control(control: String)

    /** Moves every volume-capable output in the zone by [steps] of its own scale. */
    fun changeVolumeSteps(steps: Int)

    fun setMuted(muted: Boolean)

    /** [how] is "absolute" or "relative" (seconds). */
    fun seek(seconds: Long, how: String = "absolute")

    fun imageUrl(imageKey: String, size: Int): String?

    fun selectedZone(): Zone?
}
