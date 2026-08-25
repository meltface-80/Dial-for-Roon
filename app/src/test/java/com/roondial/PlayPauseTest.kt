package com.roondial

import com.roondial.roon.RoonClient
import com.roondial.roon.Zone
import com.roondial.roon.ZoneStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayPauseTest {

    private fun zone(state: String): Zone {
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject(
                """
                {"zones":[{"zone_id":"z1","display_name":"Living Room","state":"$state",
                  "outputs":[{"output_id":"o1","display_name":"Living Room"}],
                  "now_playing":{"three_line":{"line1":"a","line2":"b","line3":"c"}}}]}
                """.trimIndent()
            )
        )
        return store.all().first()
    }

    @Test
    fun playingPauses() {
        assertEquals("pause", RoonClient.playPauseControl(zone("playing")))
    }

    @Test
    fun pausedPlays() {
        assertEquals("play", RoonClient.playPauseControl(zone("paused")))
    }

    @Test
    fun stoppedPlaysRatherThanToggling() {
        // The regression: a zone left paused drifts to stopped, and Roon's
        // playpause toggle does nothing from there. Pressing play appeared to
        // fail while skipping a track started the music again.
        assertEquals("play", RoonClient.playPauseControl(zone("stopped")))
    }

    @Test
    fun loadingIsNotYetPlayingSoPlayIsStillTheRightAsk() {
        assertEquals("play", RoonClient.playPauseControl(zone("loading")))
    }
}
