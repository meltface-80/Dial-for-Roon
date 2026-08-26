package com.roondial.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Makes this app eligible to receive voice transport commands.
 *
 * Android awards the "media button session" — the session that
 * getMediaKeyEventSession returns and that assistants drive — by walking
 * MediaSessionStack.updateMediaButtonSessionIfNeeded, which iterates exactly
 * one list: the UIDs that have recently rendered audio, most recent first. An
 * app that has never played a sample is never a candidate, however correct its
 * media session is. Audio focus is not consulted anywhere in that path.
 *
 * This app plays nothing: the music is on the hi-fi. So to be eligible at all
 * it has to render something, and what it renders is silence — a looping
 * buffer of zeros, which is enough to register an AudioPlaybackConfiguration
 * and put this UID in the list.
 *
 * That is a real cost and the reason this is opt-in. It draws a little power,
 * and while it runs this app is "the last app that played audio", which takes
 * the media button session away from an app that is genuinely playing on the
 * phone. Scoping it to while the zone is playing keeps that defensible.
 *
 * SoundPool would not work: MediaSessionService's playback listener explicitly
 * skips PLAYER_TYPE_JAM_SOUNDPOOL. AudioTrack is what counts.
 */
class MediaControlClaim(context: Context) {

    companion object {
        private const val TAG = "MediaControlClaim"
        private const val SAMPLE_RATE = 8000
        private const val FRAMES = SAMPLE_RATE // one second of silence, looped
    }

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    var isClaimed: Boolean = false
        private set

    fun claim(shouldClaim: Boolean) {
        if (shouldClaim == isClaimed) return
        if (shouldClaim) start() else stop()
    }

    private fun start() {
        try {
            requestFocus()

            val silence = ShortArray(FRAMES)
            val bytes = silence.size * 2
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(silence, 0, silence.size)
            // Loop for ever rather than running a thread to keep feeding it.
            audioTrack.setLoopPoints(0, FRAMES, -1)
            audioTrack.setVolume(0f)
            audioTrack.play()

            track = audioTrack
            isClaimed = true
        } catch (e: Exception) {
            Log.w(TAG, "could not claim media control: ${e.message}")
            stop()
        }
    }

    private fun stop() {
        try {
            track?.run {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not release the silent track: ${e.message}")
        }
        track = null
        abandonFocus()
        isClaimed = false
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // Losing focus is not a reason to stop the zone: the music is on
            // the hi-fi, not here.
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        try {
            audioManager.requestAudioFocus(request)
        } catch (e: Exception) {
            Log.w(TAG, "could not request audio focus: ${e.message}")
        }
    }

    private fun abandonFocus() {
        focusRequest?.let {
            try {
                audioManager.abandonAudioFocusRequest(it)
            } catch (e: Exception) {
                Log.w(TAG, "could not abandon audio focus: ${e.message}")
            }
        }
        focusRequest = null
    }
}
