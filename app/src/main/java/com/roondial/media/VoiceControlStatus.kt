package com.roondial.media

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.roondial.RoonApp

/**
 * What the phone can see of this app's media session.
 *
 * Voice control fails silently by nature — the assistant hears the words, finds
 * no session it wants to talk to, and says nothing useful. This reports each
 * link in the chain separately so a failure can be placed rather than guessed
 * at: if everything here is present and voice still does nothing, the problem
 * is the assistant's routing rather than this app's session.
 */
@UnstableApi
object VoiceControlStatus {

    data class Report(
        val serviceRunning: Boolean,
        val sessionPublished: Boolean,
        val notificationsAllowed: Boolean,
        val mediaNotificationPosted: Boolean,
        val holdsAudioFocus: Boolean,
        val takesAudioFocus: Boolean,
        val zoneName: String?,
        val isPlaying: Boolean,
        val canPlayPause: Boolean,
        val canSkip: Boolean,
        val canVolume: Boolean
    ) {
        /** Everything the system needs in order to route a command here. */
        val looksHealthy: Boolean
            get() = serviceRunning && sessionPublished && zoneName != null && canPlayPause

        fun asText(): String = buildString {
            append(if (looksHealthy) "The session looks reachable.\n\n" else "")
            line("Media session running", serviceRunning && sessionPublished)
            line("Zone", zoneName ?: "none")
            line("Playing", isPlaying)
            append('\n')
            line("Session offers play/pause", canPlayPause)
            line("Session offers next/previous", canSkip)
            line("Session offers volume", canVolume)
            append('\n')
            line("Notifications allowed", notificationsAllowed)
            line("Media notification showing", mediaNotificationPosted)
            line("Takes audio focus", takesAudioFocus)
            line("Holds audio focus now", holdsAudioFocus)
            if (!looksHealthy) {
                append("\nThe session is not reachable. Play something in Roon ")
                append("with this app open, then check again.")
            } else if (!holdsAudioFocus && takesAudioFocus && isPlaying) {
                append("\nAudio focus was refused. Another app is probably holding it.")
            }
        }

        private fun StringBuilder.line(label: String, value: Any) {
            append(label).append(": ")
            append(
                when (value) {
                    true -> "yes"
                    false -> "no"
                    else -> value.toString()
                }
            )
            append('\n')
        }
    }

    /** Must be called on the main thread: it reads the player. */
    fun report(context: Context): Report {
        val player = RoonPlaybackService.activePlayer
        val zone = (context.applicationContext as? RoonApp)?.roon?.selectedZone()

        return Report(
            serviceRunning = RoonPlaybackService.isRunning,
            sessionPublished = RoonPlaybackService.sessionPublished,
            notificationsAllowed = notificationsAllowed(context),
            mediaNotificationPosted = mediaNotificationPosted(context),
            holdsAudioFocus = RoonPlaybackService.holdsAudioFocus,
            takesAudioFocus = RoonPlaybackService.takesAudioFocus(context),
            zoneName = zone?.displayName,
            isPlaying = zone?.isPlaying == true,
            canPlayPause = player?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true,
            canSkip = player?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) == true,
            canVolume =
                player?.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS) == true
        )
    }

    private fun notificationsAllowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun mediaNotificationPosted(context: Context): Boolean = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.activeNotifications?.isNotEmpty() == true
    } catch (e: Exception) {
        false
    }
}
