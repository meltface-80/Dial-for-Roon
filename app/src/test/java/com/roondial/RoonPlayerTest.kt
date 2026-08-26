package com.roondial

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import com.roondial.media.RoonPlayer
import com.roondial.roon.Zone
import com.roondial.roon.ZoneControl
import com.roondial.roon.ZoneStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The surface Gemini actually drives. Voice commands arrive as ordinary
 * Player calls from a MediaController, so these tests call the same Player
 * methods a controller would and assert what reaches Roon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoonPlayerTest {

    private class FakeRoon : ZoneControl {
        val commands = ArrayList<String>()
        val volumeSteps = ArrayList<Int>()
        val mutes = ArrayList<Boolean>()
        val seeks = ArrayList<Pair<Long, String>>()
        var zone: Zone? = null

        override fun control(control: String) { commands += control }
        override fun changeVolumeSteps(steps: Int) { volumeSteps += steps }
        override fun setMuted(muted: Boolean) { mutes += muted }
        override fun seek(seconds: Long, how: String) { seeks += seconds to how }
        override fun imageUrl(imageKey: String, size: Int) =
            "http://10.0.0.5:9330/api/image/$imageKey?scale=fit&width=$size&height=$size"
        override fun selectedZone(): Zone? = zone
    }

    private lateinit var roon: FakeRoon
    private lateinit var player: RoonPlayer

    @Before
    fun setUp() {
        roon = FakeRoon()
        player = RoonPlayer(roon, Looper.getMainLooper())
    }

    private fun zone(
        state: String = "playing",
        volume: String? = """"volume":{"type":"db","min":-80,"max":0,"value":-32.5,"step":0.5,"is_muted":false}""",
        next: Boolean = true,
        previous: Boolean = true,
        seekAllowed: Boolean = true,
        nowPlaying: Boolean = true
    ): Zone {
        val vol = volume?.let { ",$it" } ?: ""
        val np = if (nowPlaying) {
            ""","now_playing":{"length":300,"seek_position":126,"image_key":"art1",
             "three_line":{"line1":"Teardrop","line2":"Massive Attack","line3":"Mezzanine"}}"""
        } else ""
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject(
                """
                {"zones":[{"zone_id":"z1","display_name":"Living Room","state":"$state",
                  "is_play_allowed":${state != "playing"},"is_pause_allowed":${state == "playing"},
                  "is_next_allowed":$next,"is_previous_allowed":$previous,
                  "is_seek_allowed":$seekAllowed,
                  "outputs":[{"output_id":"o1","display_name":"Living Room"$vol}]$np}]}
                """.trimIndent()
            )
        )
        return store.all().first()
    }

    private fun useZone(z: Zone?) {
        roon.zone = z
        player.updateZone(z)
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ------------------------------------------------------------- transport

    @Test
    fun pauseReachesRoon() {
        useZone(zone(state = "playing"))
        player.pause()
        assertEquals(listOf("pause"), roon.commands)
    }

    @Test
    fun playReachesRoon() {
        useZone(zone(state = "paused"))
        player.play()
        assertEquals(listOf("play"), roon.commands)
    }

    @Test
    fun nextAndPreviousReachRoon() {
        useZone(zone())
        player.seekToNext()
        player.seekToPrevious()
        assertEquals(listOf("next", "previous"), roon.commands)
    }

    @Test
    fun previousMeansPreviousEvenLateInATrack() {
        // The track is 126s in. A default player would restart the current
        // track instead of skipping back, which is not what "go back" means
        // when Roon already has its own rule for that.
        useZone(zone())
        assertEquals(126_000L, player.currentPosition)
        player.seekToPrevious()
        assertEquals(listOf("previous"), roon.commands)
        assertTrue(roon.seeks.isEmpty())
    }

    @Test
    fun skipStaysOfferedEvenWhenRoonSaysNo() {
        // media3 derives the platform PlaybackState actions from the player's
        // commands, so withdrawing these strips ACTION_SKIP_TO_NEXT/PREVIOUS
        // from what an assistant sees and makes the session look half-dead.
        // Roon is the right place to refuse a skip it cannot do.
        useZone(zone(next = false, previous = false))

        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))

        player.seekToNext()
        player.seekToPrevious()
        assertEquals(listOf("next", "previous"), roon.commands)
    }

    @Test
    fun prepareIsOffered() {
        // ACTION_PREPARE, which the assistant documentation lists as expected
        // before it will send a play command.
        useZone(zone())
        assertTrue(player.isCommandAvailable(Player.COMMAND_PREPARE))
    }

    @Test
    fun seekReachesRoonInSeconds() {
        useZone(zone())
        player.seekTo(90_000L)
        assertEquals(listOf(90L to "absolute"), roon.seeks)
    }

    @Test
    fun seekIsUnavailableWhenRoonForbidsIt() {
        useZone(zone(seekAllowed = false))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }

    // ---------------------------------------------------------------- volume

    @Test
    fun volumeScaleIsTheZonesOwnSteps() {
        val z = zone()
        useZone(z)
        // -80..0 dB in 0.5 dB steps is 160 steps; -32.5 dB is step 95.
        assertEquals(160, player.deviceInfo.maxVolume)
        assertEquals(95, player.deviceVolume)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, player.deviceInfo.playbackType)
    }

    @Test
    fun volumeKeysMoveExactlyOneRoonStep() {
        useZone(zone())
        player.increaseDeviceVolume(0)
        player.decreaseDeviceVolume(0)
        assertEquals(listOf(1, -1), roon.volumeSteps)
    }

    @Test
    fun draggingTheSystemSliderSendsTheDifference() {
        useZone(zone())
        player.setDeviceVolume(105, 0) // from step 95
        assertEquals(listOf(10), roon.volumeSteps)
    }

    @Test
    fun muteReachesRoon() {
        useZone(zone())
        player.setDeviceMuted(true, 0)
        player.setDeviceMuted(false, 0)
        assertEquals(listOf(true, false), roon.mutes)
    }

    @Test
    fun fixedVolumeZoneLeavesTheVolumeKeysAlone() {
        useZone(zone(volume = null))
        // Reporting a remote volume control that does not exist would swallow
        // the volume keys; instead they keep adjusting the phone.
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, player.deviceInfo.playbackType)
        assertFalse(player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS))
    }

    @Test
    fun incrementalVolumeOnlyOffersRelativeAdjustment() {
        useZone(zone(volume = """"volume":{"type":"incremental","is_muted":false}"""))
        assertTrue(player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
        assertFalse(
            "an incremental control has no scale to set an absolute value on",
            player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
        )
        player.increaseDeviceVolume(0)
        assertEquals(listOf(1), roon.volumeSteps)
    }

    // ----------------------------------------------------------------- state

    @Test
    fun playbackStateFollowsTheZone() {
        useZone(zone(state = "playing"))
        assertTrue(player.playWhenReady)
        assertEquals(Player.STATE_READY, player.playbackState)

        useZone(zone(state = "paused"))
        assertFalse(player.playWhenReady)
    }

    @Test
    fun metadataIsWhatTheAssistantWillReadBack() {
        useZone(zone())
        val metadata = player.mediaMetadata
        assertEquals("Teardrop", metadata.title)
        assertEquals("Massive Attack", metadata.artist)
        assertEquals("Mezzanine", metadata.albumTitle)
        assertEquals(
            "http://10.0.0.5:9330/api/image/art1?scale=fit&width=512&height=512",
            metadata.artworkUri.toString()
        )
        assertEquals(300_000L, player.duration)
    }

    @Test
    fun nothingPlayingIsIdleSoNoNotificationAppears() {
        useZone(null)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        assertEquals(0, player.currentTimeline.windowCount)
        assertNull(player.currentMediaItem)
    }

    @Test
    fun aZoneWithNothingPlayingIsStillControllable() {
        // Reporting STATE_IDLE here took the notification away and stopped the
        // session counting as engaged, so an assistant asked to play found an
        // empty timeline and no play command. A zone Roon has stopped rather
        // than paused lands here — exactly when "play" has to work.
        useZone(zone(nowPlaying = false))

        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.playWhenReady)
        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertTrue("timeline must not be empty", player.currentTimeline.windowCount > 0)

        player.play()
        assertEquals(listOf("play"), roon.commands)
    }

    @Test
    fun theZoneIsNamedWhileNothingPlays() {
        useZone(zone(nowPlaying = false))
        assertEquals("Living Room", player.mediaMetadata.title)
    }
}
