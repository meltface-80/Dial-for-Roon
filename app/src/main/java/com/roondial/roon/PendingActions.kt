package com.roondial.roon

/**
 * Actions taken before the Core is reachable.
 *
 * A widget button can be pressed with the app long dead: the tap starts the
 * process, and a second or two passes before the extension has registered and
 * knows what the zones are. Rather than dropping the press, it waits here and
 * runs once there is a zone to run it against.
 *
 * Anything older than [expiryMs] is discarded — a press that arrives after the
 * user has given up and moved on is worse than no press at all.
 */
class PendingActions(
    private val expiryMs: Long = 20_000,
    private val capacity: Int = 8
) {
    private data class Entry(val action: RoonClient.Action, val queuedAtMs: Long)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(action: RoonClient.Action, nowMs: Long) {
        entries.addLast(Entry(action, nowMs))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** Returns the actions still worth running, oldest first, and empties the queue. */
    @Synchronized
    fun drain(nowMs: Long): List<RoonClient.Action> {
        val fresh = entries.filter { nowMs - it.queuedAtMs <= expiryMs }.map { it.action }
        entries.clear()
        return fresh
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    @Synchronized
    fun clear() = entries.clear()
}
