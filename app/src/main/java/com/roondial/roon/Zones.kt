package com.roondial.roon

import org.json.JSONObject

/**
 * Volume as reported by com.roonlabs.transport:2 for a single output.
 *
 * `type` is "number", "db" or "incremental". An incremental control has no
 * value, range or step at all — it is a pair of +/- buttons behind an IR
 * blaster, and the only legal request is a relative +1/-1.
 */
data class Volume(
    val type: String,
    val min: Double,
    val max: Double,
    val value: Double,
    val step: Double,
    val isMuted: Boolean,
    val softLimit: Double?
) {
    val isIncremental: Boolean get() = type == "incremental"

    /** Highest value the ring should be able to reach. */
    val effectiveMax: Double get() = softLimit?.coerceAtMost(max) ?: max

    val fraction: Float
        get() {
            if (isIncremental) return 0f
            val span = effectiveMax - min
            if (span <= 0.0) return 0f
            return ((value - min) / span).coerceIn(0.0, 1.0).toFloat()
        }

    fun format(): String = when (type) {
        "db" -> String.format("%.1f dB", value)
        "incremental" -> if (isMuted) "muted" else "+/-"
        else -> if (value == Math.floor(value)) value.toInt().toString()
                else String.format("%.1f", value)
    }

    companion object {
        fun parse(o: JSONObject?): Volume? {
            if (o == null) return null
            val type = o.optString("type", "number")
            return Volume(
                type = type,
                min = o.optDouble("min", 0.0).orZero(),
                max = o.optDouble("max", 100.0).orZero(),
                value = o.optDouble("value", 0.0).orZero(),
                step = o.optDouble("step", 1.0).let { if (it.isNaN() || it == 0.0) 1.0 else it },
                isMuted = o.optBoolean("is_muted", false),
                softLimit = o.optDouble("soft_limit").takeIf { !it.isNaN() }
            )
        }

        private fun Double.orZero(): Double = if (isNaN()) 0.0 else this
    }
}

data class Output(
    val outputId: String,
    val displayName: String,
    val volume: Volume?
) {
    companion object {
        fun parse(o: JSONObject): Output = Output(
            outputId = o.optString("output_id"),
            displayName = o.optString("display_name"),
            volume = Volume.parse(o.optJSONObject("volume"))
        )
    }
}

data class NowPlaying(
    val line1: String,
    val line2: String,
    val line3: String,
    val lengthSeconds: Int?,
    val seekPosition: Int?,
    val imageKey: String?
) {
    companion object {
        fun parse(o: JSONObject?): NowPlaying? {
            if (o == null) return null
            val three = o.optJSONObject("three_line")
            val two = o.optJSONObject("two_line")
            val one = o.optJSONObject("one_line")
            val src = three ?: two ?: one
            return NowPlaying(
                line1 = src?.optString("line1").orEmpty(),
                line2 = src?.optString("line2").orEmpty(),
                line3 = src?.optString("line3").orEmpty(),
                lengthSeconds = o.optInt("length", -1).takeIf { it >= 0 },
                seekPosition = o.optInt("seek_position", -1).takeIf { it >= 0 },
                imageKey = o.optString("image_key").takeIf { it.isNotEmpty() }
            )
        }
    }
}

data class Zone(
    val zoneId: String,
    val displayName: String,
    val state: String,
    val isPlayAllowed: Boolean,
    val isPauseAllowed: Boolean,
    val isNextAllowed: Boolean,
    val isPreviousAllowed: Boolean,
    val isSeekAllowed: Boolean,
    val outputs: List<Output>,
    val nowPlaying: NowPlaying?
) {
    val isPlaying: Boolean get() = state == "playing"

    /**
     * Volume lives on outputs, not zones. A grouped zone has one output per
     * device, each with its own type, range and step, so the ring drives all
     * of them and displays the first one that actually has a control.
     */
    val volumeOutputs: List<Output> get() = outputs.filter { it.volume != null }

    val primaryVolume: Volume? get() = volumeOutputs.firstOrNull()?.volume

    val hasVolumeControl: Boolean get() = volumeOutputs.isNotEmpty()

    companion object {
        fun parse(o: JSONObject): Zone {
            val outs = ArrayList<Output>()
            o.optJSONArray("outputs")?.let { arr ->
                for (i in 0 until arr.length()) outs += Output.parse(arr.getJSONObject(i))
            }
            return Zone(
                zoneId = o.optString("zone_id"),
                displayName = o.optString("display_name"),
                state = o.optString("state", "stopped"),
                isPlayAllowed = o.optBoolean("is_play_allowed", false),
                isPauseAllowed = o.optBoolean("is_pause_allowed", false),
                isNextAllowed = o.optBoolean("is_next_allowed", false),
                isPreviousAllowed = o.optBoolean("is_previous_allowed", false),
                isSeekAllowed = o.optBoolean("is_seek_allowed", false),
                outputs = outs,
                nowPlaying = NowPlaying.parse(o.optJSONObject("now_playing"))
            )
        }
    }
}

/**
 * Applies the subscribe_zones stream. The first message is "Subscribed" with
 * the full set; everything after is "Changed" with added/removed/changed
 * deltas plus a separate, much more frequent seek-position delta.
 */
class ZoneStore {
    private val zones = LinkedHashMap<String, Zone>()

    fun applySubscribed(body: JSONObject) {
        zones.clear()
        body.optJSONArray("zones")?.let { arr ->
            for (i in 0 until arr.length()) {
                val z = Zone.parse(arr.getJSONObject(i))
                zones[z.zoneId] = z
            }
        }
    }

    fun applyChanged(body: JSONObject) {
        body.optJSONArray("zones_removed")?.let { arr ->
            for (i in 0 until arr.length()) zones.remove(arr.getString(i))
        }
        body.optJSONArray("zones_added")?.let { arr ->
            for (i in 0 until arr.length()) {
                val z = Zone.parse(arr.getJSONObject(i))
                zones[z.zoneId] = z
            }
        }
        body.optJSONArray("zones_changed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val z = Zone.parse(arr.getJSONObject(i))
                zones[z.zoneId] = z
            }
        }
        body.optJSONArray("zones_seek_changed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val zoneId = e.optString("zone_id")
                val existing = zones[zoneId] ?: continue
                val np = existing.nowPlaying ?: continue
                val pos = e.optInt("seek_position", -1).takeIf { it >= 0 } ?: continue
                zones[zoneId] = existing.copy(nowPlaying = np.copy(seekPosition = pos))
            }
        }
    }

    fun clear() = zones.clear()

    fun all(): List<Zone> = zones.values.sortedBy { it.displayName.lowercase() }

    fun byId(id: String?): Zone? = if (id == null) null else zones[id]
}
