package com.roondial

import com.roondial.media.VoiceControlStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceControlStatusTest {

    private fun report(
        serviceRunning: Boolean = true,
        sessionPublished: Boolean = true,
        zoneName: String? = "Living Room",
        canPlayPause: Boolean = true,
        isPlaying: Boolean = true,
        holdsAudioFocus: Boolean = true,
        takesAudioFocus: Boolean = true
    ) = VoiceControlStatus.Report(
        serviceRunning = serviceRunning,
        sessionPublished = sessionPublished,
        notificationsAllowed = true,
        mediaNotificationPosted = true,
        holdsAudioFocus = holdsAudioFocus,
        takesAudioFocus = takesAudioFocus,
        zoneName = zoneName,
        isPlaying = isPlaying,
        canPlayPause = canPlayPause,
        canSkip = true,
        canVolume = true
    )

    @Test
    fun healthyWhenEveryLinkIsPresent() {
        assertTrue(report().looksHealthy)
        assertTrue(report().asText().contains("The session looks reachable"))
    }

    @Test
    fun aDeadServiceIsNotHealthyHoweverGoodTheRest() {
        val dead = report(serviceRunning = false)
        assertFalse(dead.looksHealthy)
        assertTrue(dead.asText().contains("not reachable"))
    }

    @Test
    fun noZoneIsNotHealthy() {
        assertFalse(report(zoneName = null).looksHealthy)
    }

    @Test
    fun aSessionThatCannotPauseIsNotHealthy() {
        assertFalse(report(canPlayPause = false).looksHealthy)
    }

    @Test
    fun refusedAudioFocusIsCalledOut() {
        val text = report(holdsAudioFocus = false).asText()
        assertTrue(text.contains("Audio focus was refused"))
    }

    @Test
    fun refusedFocusIsNotMentionedWhenNotAskingForIt() {
        val text = report(holdsAudioFocus = false, takesAudioFocus = false).asText()
        assertFalse(text.contains("Audio focus was refused"))
    }

    @Test
    fun everyLinkIsNamedInTheReport() {
        val text = report().asText()
        for (label in listOf(
            "Media session running", "Zone", "Playing",
            "Session offers play/pause", "Session offers next/previous",
            "Session offers volume", "Notifications allowed",
            "Media notification showing", "Holds audio focus now"
        )) {
            assertTrue("missing '$label'", text.contains(label))
        }
    }
}
