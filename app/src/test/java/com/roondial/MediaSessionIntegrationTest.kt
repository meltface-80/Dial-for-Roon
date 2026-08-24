package com.roondial

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.roondial.media.RoonPlayer
import com.roondial.roon.Zone
import com.roondial.roon.ZoneControl
import com.roondial.roon.ZoneStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * End of the chain: a MediaController talking to the published session.
 *
 * Gemini is a MediaController. It does not know anything about this app — it
 * finds the active session and issues Player commands over it. So driving the
 * session through a real controller is the closest a test on a build machine
 * can get to saying the voice commands work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSessionIntegrationTest {

    private class FakeRoon : ZoneControl {
        val commands = ArrayList<String>()
        val volumeSteps = ArrayList<Int>()
        val mutes = ArrayList<Boolean>()
        var zone: Zone? = null
        override fun control(control: String) { commands += control }
        override fun changeVolumeSteps(steps: Int) { volumeSteps += steps }
        override fun setMuted(muted: Boolean) { mutes += muted }
        override fun seek(seconds: Long, how: String) = Unit
        override fun imageUrl(imageKey: String, size: Int) = "http://core/api/image/$imageKey"
        override fun selectedZone(): Zone? = zone
    }

    private lateinit var roon: FakeRoon
    private lateinit var player: RoonPlayer
    private lateinit var session: MediaSession
    private var controller: MediaController? = null

    @Before
    fun setUp() {
        roon = FakeRoon()
        player = RoonPlayer(roon, Looper.getMainLooper())
        session = MediaSession.Builder(RuntimeEnvironment.getApplication(), player)
            .setId("test-session")
            .build()

        val zone = playingZone()
        roon.zone = zone
        player.updateZone(zone)
        idle()
    }

    @After
    fun tearDown() {
        controller?.release()
        session.release()
        player.release()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun connect(): MediaController {
        val future = MediaController.Builder(RuntimeEnvironment.getApplication(), session.token)
            .buildAsync()
        var attempts = 0
        while (!future.isDone && attempts++ < 200) {
            idle()
            Thread.sleep(5)
        }
        assertTrue("controller did not connect", future.isDone)
        val c = future.get()
        controller = c
        idle()
        return c
    }

    private fun playingZone(): Zone {
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject(
                """
                {"zones":[{"zone_id":"z1","display_name":"Living Room","state":"playing",
                  "is_play_allowed":false,"is_pause_allowed":true,
                  "is_next_allowed":true,"is_previous_allowed":true,"is_seek_allowed":true,
                  "outputs":[{"output_id":"o1","display_name":"Living Room",
                    "volume":{"type":"db","min":-80,"max":0,"value":-32.5,"step":0.5,"is_muted":false}}],
                  "now_playing":{"length":300,"seek_position":126,"image_key":"art1",
                    "three_line":{"line1":"Teardrop","line2":"Massive Attack","line3":"Mezzanine"}}}]}
                """.trimIndent()
            )
        )
        return store.all().first()
    }

    @Test
    fun controllerSeesTheZone() {
        val c = connect()
        assertEquals("Teardrop", c.mediaMetadata.title)
        assertEquals("Massive Attack", c.mediaMetadata.artist)
        assertTrue(c.playWhenReady)
    }

    @Test
    fun controllerAdvertisesTheCommandsGeminiUses() {
        val c = connect()
        assertTrue("pause", c.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertTrue("next", c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue("previous", c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue("volume", c.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
    }

    @Test
    fun pauseFromAControllerReachesRoon() {
        val c = connect()
        c.pause()
        idle()
        assertEquals(listOf("pause"), roon.commands)
    }

    @Test
    fun skipFromAControllerReachesRoon() {
        val c = connect()
        c.seekToNext()
        idle()
        c.seekToPrevious()
        idle()
        assertEquals(listOf("next", "previous"), roon.commands)
    }

    @Test
    fun volumeFromAControllerReachesRoon() {
        val c = connect()
        assertEquals(160, c.deviceInfo.maxVolume)
        assertEquals(95, c.deviceVolume)
        c.increaseDeviceVolume(0)
        idle()
        c.decreaseDeviceVolume(0)
        idle()
        assertEquals(listOf(1, -1), roon.volumeSteps)
    }

    @Test
    fun muteFromAControllerReachesRoon() {
        val c = connect()
        c.setDeviceMuted(true, 0)
        idle()
        assertEquals(listOf(true), roon.mutes)
    }
}
