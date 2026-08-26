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
        claimsMediaControl: Boolean = true,
        claimEnabled: Boolean = true
    ) = VoiceControlStatus.Report(
        serviceRunning = serviceRunning,
        sessionPublished = sessionPublished,
        notificationsAllowed = true,
        mediaNotificationPosted = true,
        claimsMediaControl = claimsMediaControl,
        claimEnabled = claimEnabled,
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
    fun explainsWhyVoiceCannotReachTheAppWhenNotClaiming() {
        // The one thing that actually decides whether spoken transport reaches
        // this app, so the report has to say it rather than leave the user
        // guessing at notifications and audio focus.
        val text = report(claimEnabled = false).asText()
        assertTrue(text.contains("media button session"))
        assertTrue(text.contains("Claim media control"))
    }

    @Test
    fun doesNotNagAboutClaimingWhenAlreadyOn() {
        assertFalse(report(claimEnabled = true).asText().contains("Claim media control"))
    }

    @Test
    fun alwaysPointsAtGeminisConnectedApp() {
        assertTrue(report().asText().contains("Device assistance"))
    }

    @Test
    fun everyLinkIsNamedInTheReport() {
        val text = report().asText()
        for (label in listOf(
            "Media session running", "Zone", "Playing",
            "Session offers play/pause", "Session offers next/previous",
            "Session offers volume", "Notifications allowed",
            "Media notification showing", "Claiming right now"
        )) {
            assertTrue("missing '$label'", text.contains(label))
        }
    }
}
