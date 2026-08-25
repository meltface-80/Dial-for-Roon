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
    private var audioFocus: AudioFocusHolder? = null
    private lateinit var client: RoonClient

    companion object {
        private const val PREFS = "roon_dial"
        const val KEY_TAKE_AUDIO_FOCUS = "take_audio_focus"

        /** Read by the in-app diagnostic; written only on the main thread. */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var sessionPublished: Boolean = false
            private set

        @Volatile
        var holdsAudioFocus: Boolean = false
            private set

        @Volatile
        var activePlayer: RoonPlayer? = null
            private set

        fun takesAudioFocus(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TAKE_AUDIO_FOCUS, true)

        fun setTakesAudioFocus(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_TAKE_AUDIO_FOCUS, enabled).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = (application as RoonApp).roon

        val roonPlayer = RoonPlayer(client, Looper.getMainLooper())
        player = roonPlayer
        session = MediaSession.Builder(this, roonPlayer)
            .setId("roon-dial")
            .build()

        audioFocus = AudioFocusHolder(this)
        isRunning = true
        sessionPublished = session != null
        activePlayer = roonPlayer

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
        // A placed widget wants live state, so the connection outlives the
        // task being swiped away; without one there is nothing left to serve.
        if (RoonWidgetProvider.hasWidgets(this)) return
        val zone = client.selectedZone()
        if (zone == null || !zone.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        sessionPublished = false
        activePlayer = null
        holdsAudioFocus = false
        audioFocus?.hold(false)
        audioFocus = null
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
        audioFocus?.let { focus ->
            focus.hold(playing && takesAudioFocus(this))
            holdsAudioFocus = focus.isHeld
        }
    }
}
