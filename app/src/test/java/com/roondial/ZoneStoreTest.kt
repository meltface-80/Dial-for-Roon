package com.roondial

import com.roondial.roon.Volume
import com.roondial.roon.Zone
import com.roondial.roon.ZoneStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ZoneStoreTest {

    private fun subscribed() = JSONObject(
        """
        {"zones":[
          {"zone_id":"z1","display_name":"Kitchen","state":"playing",
           "is_play_allowed":false,"is_pause_allowed":true,
           "is_next_allowed":true,"is_previous_allowed":true,"is_seek_allowed":true,
           "outputs":[{"output_id":"o1","display_name":"Kitchen",
             "volume":{"type":"db","min":-80,"max":0,"value":-32.5,"step":0.5,"is_muted":false}}],
           "now_playing":{"length":215,"seek_position":12,"image_key":"abc123",
             "three_line":{"line1":"Windowlicker","line2":"Aphex Twin","line3":"Windowlicker"}}},
          {"zone_id":"z2","display_name":"Study","state":"stopped",
           "outputs":[{"output_id":"o2","display_name":"Study DAC"}]}
        ]}
        """.trimIndent()
    )

    @Test
    fun parsesSubscribedPayload() {
        val store = ZoneStore()
        store.applySubscribed(subscribed())

        val zones = store.all()
        assertEquals(2, zones.size)

        val kitchen = store.byId("z1")!!
        assertEquals("Kitchen", kitchen.displayName)
        assertTrue(kitchen.isPlaying)
        assertTrue(kitchen.isPauseAllowed)
        assertFalse(kitchen.isPlayAllowed)
        assertEquals("Windowlicker", kitchen.nowPlaying?.line1)
        assertEquals("Aphex Twin", kitchen.nowPlaying?.line2)
        assertEquals(215, kitchen.nowPlaying?.lengthSeconds)
        assertEquals("abc123", kitchen.nowPlaying?.imageKey)

        val vol = kitchen.primaryVolume!!
        assertEquals("db", vol.type)
        assertEquals(-32.5, vol.value, 0.001)
        assertEquals(0.5, vol.step, 0.001)
        assertEquals("-32.5 dB", vol.format())
        // -32.5 across a -80..0 range
        assertEquals(0.594f, vol.fraction, 0.01f)
    }

    @Test
    fun fixedVolumeOutputHasNoControl() {
        val store = ZoneStore()
        store.applySubscribed(subscribed())
        val study = store.byId("z2")!!
        assertFalse(study.hasVolumeControl)
        assertNull(study.primaryVolume)
    }

    @Test
    fun appliesChangedDeltas() {
        val store = ZoneStore()
        store.applySubscribed(subscribed())

        store.applyChanged(
            JSONObject(
                """
                {"zones_changed":[
                   {"zone_id":"z1","display_name":"Kitchen","state":"paused",
                    "outputs":[{"output_id":"o1","display_name":"Kitchen",
                      "volume":{"type":"db","min":-80,"max":0,"value":-20,"step":0.5,"is_muted":true}}]}],
                 "zones_removed":["z2"],
                 "zones_added":[{"zone_id":"z3","display_name":"Attic","state":"stopped","outputs":[]}]}
                """.trimIndent()
            )
        )

        assertNull(store.byId("z2"))
        assertEquals("Attic", store.byId("z3")?.displayName)
        val kitchen = store.byId("z1")!!
        assertEquals("paused", kitchen.state)
        assertFalse(kitchen.isPlaying)
        assertEquals(-20.0, kitchen.primaryVolume!!.value, 0.001)
        assertTrue(kitchen.primaryVolume!!.isMuted)
    }

    @Test
    fun seekDeltaOnlyTouchesSeekPosition() {
        val store = ZoneStore()
        store.applySubscribed(subscribed())
        store.applyChanged(
            JSONObject("""{"zones_seek_changed":[{"zone_id":"z1","seek_position":97}]}""")
        )
        val kitchen = store.byId("z1")!!
        assertEquals(97, kitchen.nowPlaying?.seekPosition)
        // Everything else survives the delta.
        assertEquals("Windowlicker", kitchen.nowPlaying?.line1)
        assertEquals(-32.5, kitchen.primaryVolume!!.value, 0.001)
    }

    @Test
    fun seekDeltaForUnknownZoneIsIgnored() {
        val store = ZoneStore()
        store.applySubscribed(subscribed())
        store.applyChanged(
            JSONObject("""{"zones_seek_changed":[{"zone_id":"nope","seek_position":5}]}""")
        )
        assertEquals(2, store.all().size)
    }

    @Test
    fun incrementalVolumeHasNoRange() {
        val vol = Volume.parse(JSONObject("""{"type":"incremental","is_muted":false}"""))!!
        assertTrue(vol.isIncremental)
        assertEquals(0f, vol.fraction, 0.0001f)
        assertEquals("+/-", vol.format())
    }

    @Test
    fun softLimitCapsTheRing() {
        val vol = Volume.parse(
            JSONObject("""{"type":"number","min":0,"max":100,"value":50,"step":1,"soft_limit":80}""")
        )!!
        assertEquals(80.0, vol.effectiveMax, 0.001)
        assertEquals(0.625f, vol.fraction, 0.001f)
    }

    @Test
    fun groupedZoneDrivesEveryOutputWithVolume() {
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject(
                """
                {"zones":[{"zone_id":"g","display_name":"Group","state":"playing","outputs":[
                   {"output_id":"a","display_name":"A",
                    "volume":{"type":"number","min":0,"max":100,"value":40,"step":1,"is_muted":false}},
                   {"output_id":"b","display_name":"B",
                    "volume":{"type":"db","min":-60,"max":0,"value":-10,"step":0.5,"is_muted":false}},
                   {"output_id":"c","display_name":"C (fixed)"}
                ]}]}
                """.trimIndent()
            )
        )
        val group: Zone = store.byId("g")!!
        assertEquals(3, group.outputs.size)
        assertEquals(2, group.volumeOutputs.size)
        assertEquals("a", group.volumeOutputs[0].outputId)
    }
}
