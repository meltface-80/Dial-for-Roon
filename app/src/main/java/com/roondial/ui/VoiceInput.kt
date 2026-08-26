package com.roondial.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Push-to-talk: tap, speak, get a string.
 *
 * This is deliberately the app's own recogniser rather than the system
 * assistant. Nothing here depends on which assistant is installed, on the
 * media button session, or on anything Google is retiring — a tap starts
 * listening, Android turns speech into text, and the text goes to Roon's
 * browse API as a search. The one hard requirement is a recognition service
 * on the device, which Android provides.
 */
class VoiceInput(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInput"
    }

    interface Listener {
        fun onListening()
        /** Words so far, while the user is still speaking. */
        fun onPartial(text: String)
        fun onHeard(text: String)

        /** Every hypothesis the recogniser offered, best first. */
        fun onHeardAny(options: List<String>) = onHeard(options.first())
        fun onFailed(reason: String)
    }

    private var recognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: Listener) {
        if (isListening) return
        if (!isAvailable()) {
            listener.onFailed("No speech recognition on this device")
            return
        }

        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        isListening = true

        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListening()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { listener.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                stop()
                val heard = allResults(results).filter { it.isNotBlank() }
                if (heard.isEmpty()) {
                    listener.onFailed("Didn't catch that")
                } else {
                    listener.onHeardAny(heard)
                }
            }

            override fun onError(error: Int) {
                stop()
                listener.onFailed(describe(error))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // More than one guess: a command misheard as a title is expensive,
            // and the right words are often further down the list.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            speech.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "could not start listening: ${e.message}")
            stop()
            listener.onFailed("Could not start listening")
        }
    }

    fun stop() {
        isListening = false
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "could not release the recogniser: ${e.message}")
        }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? = allResults(bundle).firstOrNull()

    private fun allResults(bundle: Bundle?): List<String> =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recogniser stopped"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs a network"
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Heard nothing"
        else -> "Speech recognition failed"
    }
}
