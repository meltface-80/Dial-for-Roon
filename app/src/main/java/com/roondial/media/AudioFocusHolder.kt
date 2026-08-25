package com.roondial.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Holds audio focus while the Roon zone is playing, even though nothing plays
 * on the phone.
 *
 * Voice assistants and the system decide which app "the media" means, and an
 * app that has never taken audio focus is a weak candidate however correct its
 * media session is. Taking focus says: the music you are talking about is the
 * music I am in charge of.
 *
 * The cost is real — holding focus pauses audio in other apps on the phone —
 * so this is a preference rather than a given.
 */
class AudioFocusHolder(context: Context) {

    companion object {
        private const val TAG = "AudioFocus"
    }

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var request: AudioFocusRequest? = null

    var isHeld: Boolean = false
        private set

    fun hold(shouldHold: Boolean) {
        if (shouldHold == isHeld) return
        if (shouldHold) acquire() else release()
    }

    private fun acquire() {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Losing focus is not a reason to stop the zone: the music is on
            // the hi-fi, not here. Let go of the claim and carry on.
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    isHeld = false
                    request = null
                }
            }
            .build()
        request = focusRequest
        val result = try {
            audioManager.requestAudioFocus(focusRequest)
        } catch (e: Exception) {
            Log.w(TAG, "could not request audio focus: ${e.message}")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        isHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun release() {
        request?.let {
            try {
                audioManager.abandonAudioFocusRequest(it)
            } catch (e: Exception) {
                Log.w(TAG, "could not abandon audio focus: ${e.message}")
            }
        }
        request = null
        isHeld = false
    }
}
