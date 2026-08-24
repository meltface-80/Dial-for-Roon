package com.roondial.media

import android.content.Intent
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.roondial.RoonApp
import com.roondial.roon.RoonClient
import com.roondial.roon.Zone

/**
 * Publishes the selected Roon zone as a media session.
 *
 * This is the piece Gemini talks to. "Hey Google, pause" goes to the system's
 * active media session, not to any app-specific API, so owning that session is
 * what makes voice control work — and it brings the notification, lock screen,
 * headset buttons and Wear controls along with it.
 *
 * The service, not the activity, owns the Roon connection: the session has to
 * outlive the UI or voice commands would only work with the app on screen.
 */
@UnstableApi
class RoonPlaybackService : MediaSessionService(), RoonClient.Listener {

    private var session: MediaSession? = null
    private var player: RoonPlayer? = null
    private lateinit var client: RoonClient

    override fun onCreate() {
        super.onCreate()
        client = (application as RoonApp).roon

        val roonPlayer = RoonPlayer(client, Looper.getMainLooper())
        player = roonPlayer
        session = MediaSession.Builder(this, roonPlayer)
            .setId("roon-dial")
            .build()

        client.addListener(this)
        client.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Survive being restarted by the system with a null intent.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while the zone is paused should let go; while
        // it is playing the session stays so the notification keeps working.
        val zone = client.selectedZone()
        if (zone == null || !zone.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        client.removeListener(this)
        client.stop()
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    // RoonClient.Listener — always delivered on the main thread, which is the
    // player's application looper.

    override fun onStatus(status: RoonClient.Status) = Unit

    override fun onZones(zones: List<Zone>, selected: Zone?) {
        player?.updateZone(selected)
    }
}
