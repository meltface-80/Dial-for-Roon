package com.roondial.media

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.roondial.roon.Zone
import com.roondial.roon.ZoneControl
import kotlin.math.roundToInt

/**
 * Presents the selected Roon zone as a media3 [Player].
 *
 * Nothing plays on the phone — this is the same shape as a Cast player, where
 * the Player is a remote control and the audio happens elsewhere. Publishing it
 * through a MediaSession is what lets Gemini ("pause", "next", "resume"), the
 * notification, the lock screen, headset buttons and Wear reach the zone,
 * because all of them speak to the session rather than to the app.
 *
 * Volume is declared as [DeviceInfo.PLAYBACK_TYPE_REMOTE] with the zone's own
 * step count as its scale, so one press of the volume rocker is exactly one
 * Roon step.
 */
@UnstableApi
class RoonPlayer(
    private val client: ZoneControl,
    looper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(looper) {

    companion object {
        /** Uid of the synthetic "currently playing" item. */
        private const val UID_CURRENT = "roon:current"
        private const val UID_PREVIOUS = "roon:previous"
        private const val UID_NEXT = "roon:next"

        /**
         * "Previous" must always mean Roon's previous, never "restart this
         * track": BasePlayer only forwards it as a previous-item seek while the
         * position is below this threshold.
         */
        private const val MAX_SEEK_TO_PREVIOUS_MS = 24L * 60 * 60 * 1000

        /** Incremental volume controls report no scale, so invent a small one. */
        private const val INCREMENTAL_STEPS = 20
    }

    private var zone: Zone? = null
    private var artworkSize = 512

    /** Called on the application looper thread whenever zone state changes. */
    fun updateZone(newZone: Zone?) {
        zone = newZone
        invalidateState()
    }

    // ------------------------------------------------------------------ state

    override fun getState(): State {
        val z = zone
        val np = z?.nowPlaying

        val builder = State.Builder()
            .setAvailableCommands(commandsFor(z))
            .setMaxSeekToPreviousPositionMs(MAX_SEEK_TO_PREVIOUS_MS)

        applyVolume(builder, z)

        if (z == null) {
            // Not connected to a Core: there is genuinely nothing to control.
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .build()
        }

        if (np == null) {
            // A zone with nothing queued is still a live control surface, and
            // saying so matters. media3 treats STATE_IDLE as nothing to show:
            // it takes the notification away and stops counting the session as
            // engaged, so an assistant asked to play finds a session with an
            // empty timeline and no play command and does nothing. A zone
            // Roon has stopped rather than paused lands here, which is exactly
            // when "play" needs to work.
            return builder
                .setPlaylist(listOf(idleItem(z)))
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setContentPositionMs(0)
                .build()
        }

        // Roon owns the queue, so the playlist is synthetic: neighbours exist
        // only when Roon says skipping that way is allowed, which makes the
        // notification's buttons match the zone.
        // Neighbours always exist. A player with nothing either side reports
        // that it cannot skip, and BasePlayer then swallows the call before it
        // reaches Roon — so gating these on Roon's flags silently disarmed the
        // command instead of letting Roon answer for itself.
        val playlist = ArrayList<MediaItemData>(3)
        playlist += placeholder(UID_PREVIOUS)
        val currentIndex = playlist.size
        playlist += currentItem(z, np.line1, np.line2, np.line3, np.lengthSeconds, np.imageKey)
        playlist += placeholder(UID_NEXT)

        val positionMs = (np.seekPosition ?: 0).toLong() * 1000L
        builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentIndex)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(z.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)

        if (z.isPlaying) {
            // Roon sends a seek update about once a second; extrapolate between
            // them so the notification's progress bar runs smoothly.
            builder.setContentPositionMs(PositionSupplier.getExtrapolating(positionMs, 1f))
        } else {
            builder.setContentPositionMs(positionMs)
        }

        return builder.build()
    }

    private fun commandsFor(z: Zone?): Player.Commands {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE
            )
        if (z == null) return commands.build()

        // Offered whenever there is a zone, not only when Roon currently
        // reports it allowed. Withdrawing it makes the session look
        // uncontrollable to an assistant at precisely the moment the user is
        // asking it to start something.
        commands.add(Player.COMMAND_PLAY_PAUSE)
        commands.add(Player.COMMAND_STOP)
        // COMMAND_PREPARE is what becomes ACTION_PREPARE, which the assistant
        // documentation lists as required before it will send a play command.
        commands.add(Player.COMMAND_PREPARE)
        // Offered unconditionally for the same reason as play/pause: media3
        // derives the platform PlaybackState actions from these commands, so
        // withdrawing them strips ACTION_SKIP_TO_NEXT/PREVIOUS from what an
        // assistant sees. Let Roon reject a skip it cannot do.
        commands.add(Player.COMMAND_SEEK_TO_NEXT)
        commands.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        commands.add(Player.COMMAND_SEEK_TO_PREVIOUS)
        commands.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (z.isSeekAllowed) commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

        if (z.hasVolumeControl) {
            commands.add(Player.COMMAND_GET_DEVICE_VOLUME)
            commands.add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            val volume = z.primaryVolume
            if (volume != null && !volume.isIncremental) {
                commands.add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
            }
        }
        return commands.build()
    }

    private fun applyVolume(builder: State.Builder, z: Zone?) {
        val volume = z?.primaryVolume
        if (volume == null) {
            // Fixed-volume output: leave the keys alone so they keep doing what
            // the user expects, rather than driving a control that isn't there.
            builder.setDeviceInfo(DeviceInfo.UNKNOWN)
            return
        }
        builder
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(0)
                    .setMaxVolume(maxVolumeIndex(z))
                    .build()
            )
            .setDeviceVolume(volumeIndex(z))
            .setIsDeviceMuted(volume.isMuted)
    }

    private fun currentItem(
        z: Zone,
        title: String,
        artist: String,
        album: String,
        lengthSeconds: Int?,
        imageKey: String?
    ): MediaItemData {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setStation(z.displayName)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply {
                imageKey?.let { key ->
                    client.imageUrl(key, artworkSize)?.let { setArtworkUri(android.net.Uri.parse(it)) }
                }
            }
            .build()

        return MediaItemData.Builder(UID_CURRENT)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(UID_CURRENT)
                    .setMediaMetadata(metadata)
                    .build()
            )
            .setMediaMetadata(metadata)
            .setDurationUs(
                lengthSeconds?.let { it.toLong() * 1_000_000L } ?: C.TIME_UNSET
            )
            .setIsSeekable(z.isSeekAllowed)
            .setIsDynamic(false)
            .build()
    }

    /** Stands for a zone that is connected but has nothing playing. */
    private fun idleItem(z: Zone): MediaItemData {
        val metadata = MediaMetadata.Builder()
            .setTitle(z.displayName)
            .setArtist("Nothing playing")
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        return MediaItemData.Builder(UID_CURRENT)
            .setMediaItem(
                MediaItem.Builder().setMediaId(UID_CURRENT).setMediaMetadata(metadata).build()
            )
            .setMediaMetadata(metadata)
            .setDurationUs(C.TIME_UNSET)
            .setIsSeekable(false)
            .setIsDynamic(false)
            .build()
    }

    /**
     * Stand-in for the track either side of the current one. Roon's queue isn't
     * subscribed to yet; these exist so skip commands are available at all,
     * because a player with a single-item playlist reports that it cannot skip.
     */
    private fun placeholder(uid: String): MediaItemData =
        MediaItemData.Builder(uid)
            .setMediaItem(MediaItem.Builder().setMediaId(uid).build())
            .setIsSeekable(false)
            .setIsDynamic(false)
            .setIsPlaceholder(true)
            .build()

    // -------------------------------------------------------- volume mapping

    /**
     * Roon volumes are doubles on the device's own scale (often dB in 0.5
     * steps); media3 wants an integer index. One index is one Roon step, so a
     * press of the volume rocker moves the zone by exactly one step.
     */
    fun maxVolumeIndex(z: Zone?): Int {
        val volume = z?.primaryVolume ?: return 0
        if (volume.isIncremental) return INCREMENTAL_STEPS
        val span = volume.effectiveMax - volume.min
        if (span <= 0.0 || volume.step <= 0.0) return 0
        return (span / volume.step).roundToInt().coerceAtLeast(1)
    }

    fun volumeIndex(z: Zone?): Int {
        val volume = z?.primaryVolume ?: return 0
        if (volume.isIncremental) return INCREMENTAL_STEPS / 2
        if (volume.step <= 0.0) return 0
        return ((volume.value - volume.min) / volume.step)
            .roundToInt()
            .coerceIn(0, maxVolumeIndex(z))
    }

    // ------------------------------------------------------------- commands

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        client.control(if (playWhenReady) "play" else "pause")
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        client.control("stop")
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> client.control("next")

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> client.control("previous")

            else -> {
                if (positionMs != C.TIME_UNSET) client.seek(positionMs / 1000L, "absolute")
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val steps = deviceVolume - volumeIndex(zone)
        client.changeVolumeSteps(steps)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        client.changeVolumeSteps(1)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        client.changeVolumeSteps(-1)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        client.setMuted(muted)
        return Futures.immediateVoidFuture()
    }
}
