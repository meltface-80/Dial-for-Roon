package com.roondial.media

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.roondial.RoonApp
import com.roondial.roon.RoonClient
import com.roondial.roon.Zone
import com.roondial.widget.RoonWidgetProvider

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
    private var claim: MediaControlClaim? = null
    private lateinit var client: RoonClient

    companion object {
        private const val PREFS = "roon_dial"
        const val KEY_CLAIM_MEDIA_CONTROL = "claim_media_control"

        /** Read by the in-app diagnostic; written only on the main thread. */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var sessionPublished: Boolean = false
            private set

        @Volatile
        var claimsMediaControl: Boolean = false
            private set

        @Volatile
        var activePlayer: RoonPlayer? = null
            private set

        /**
         * Off by default: it renders silent audio, which costs a little power
         * and takes the media button session from an app genuinely playing on
         * the phone. See MediaControlClaim.
         */
        fun claimsMediaControlEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CLAIM_MEDIA_CONTROL, false)

        fun setClaimsMediaControl(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_CLAIM_MEDIA_CONTROL, enabled).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = (application as RoonApp).roon

        val roonPlayer = RoonPlayer(client, Looper.getMainLooper())
        player = roonPlayer
        session = MediaSession.Builder(this, roonPlayer)
            .setId("roon-dial")
            .setCallback(SessionCallback())
            .setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    packageManager.getLaunchIntentForPackage(packageName)
                        ?: android.content.Intent(),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        claim = MediaControlClaim(this)
        isRunning = true
        sessionPublished = session != null
        activePlayer = roonPlayer

        client.addListener(this)
        client.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * media3 requires this once a MediaButtonReceiver is declared: without it
     * a PLAY key that cold-starts the service resolves to a failure. There is
     * no queue to restore — Roon owns that — so this hands back the zone as it
     * stands and lets play do the rest.
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val player = player
            val items = if (player != null && player.mediaItemCount > 0) {
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            } else {
                emptyList()
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, 0, 0L)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Survive being restarted by the system with a null intent.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while the zone is paused should let go; while
        // it is playing the session stays so the notification keeps working.
        // A placed widget wants live state, and any selected zone means there
        // is still something for a voice command or a headset button to reach.
        // Tearing the session down here is how the app ends up with nothing
        // for an assistant to talk to.
        if (RoonWidgetProvider.hasWidgets(this)) return
        if (client.selectedZone() != null) return
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        sessionPublished = false
        activePlayer = null
        claimsMediaControl = false
        claim?.claim(false)
        claim = null
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
        RoonWidgetProvider.publish(this, selected)

        val playing = selected?.isPlaying == true
        claim?.let { mediaControl ->
            mediaControl.claim(playing && claimsMediaControlEnabled(this))
            claimsMediaControl = mediaControl.isClaimed
        }
    }
}
