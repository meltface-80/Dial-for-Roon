package com.roondial.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.roondial.RoonApp
import com.roondial.media.RoonPlaybackService
import com.roondial.media.VoiceControlStatus
import com.roondial.roon.RoonClient
import com.roondial.roon.Zone
import com.roondial.widget.RoonWidgetProvider
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity(), RoonClient.Listener, DialView.Callbacks {

    companion object {
        private const val TAG = "RoonDial"
        private const val DEFAULT_PORT = 9330
        private const val REQUEST_NOTIFICATIONS = 1
        private const val REQUEST_MICROPHONE = 2

        /** Set by the widget's microphone so the app opens already listening. */
        const val EXTRA_START_VOICE = "com.roondial.START_VOICE"
    }

    private lateinit var dial: DialView
    private lateinit var statusView: TextView
    private lateinit var client: RoonClient

    private var zones: List<Zone> = emptyList()
    private var artKey: String? = null
    private val artLoader = Executors.newSingleThreadExecutor()
    private val voiceInput by lazy { VoiceInput(this) }
    private val voiceHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        dial = DialView(this).apply { callbacks = this@MainActivity }
        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#8B96A2"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(6), dp(20), dp(14))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#07080A"))
            addView(
                FrameLayout(this@MainActivity).apply { addView(dial) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
            addView(
                statusView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(root)

        // Shared with the media session service, which owns the connection's
        // lifetime so voice control survives leaving the app.
        client = (application as RoonApp).roon
        requestNotificationPermission()
        if (intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true) startVoice()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true) startVoice()
    }

    override fun onStart() {
        super.onStart()
        client.addListener(this)
        client.start()
        startService(Intent(this, RoonPlaybackService::class.java))
    }

    override fun onStop() {
        super.onStop()
        client.removeListener(this)
        // The connection deliberately stays up: it belongs to the service now.
    }

    /**
     * Without this the media notification never appears, and with it goes the
     * lock-screen control and part of the voice-control surface.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceInput.stop()
        artLoader.shutdownNow()
    }

    // ----------------------------------------------------------------- voice

    override fun onVoiceTapped() {
        if (voiceInput.isListening) {
            voiceInput.stop()
            setVoice(DialView.Voice.Idle)
            return
        }
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            return
        }
        startVoice()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            say("The microphone is needed to ask for music")
        }
    }

    private fun startVoice() {
        setVoice(DialView.Voice.Listening(""))
        voiceInput.start(object : VoiceInput.Listener {
            override fun onListening() = setVoice(DialView.Voice.Listening(""))

            override fun onPartial(text: String) =
                setVoice(DialView.Voice.Listening(text))

            override fun onHeard(text: String) {
                // "play Iron Maiden" is what people say; "Iron Maiden" is what
                // Roon should be asked to find.
                val query = SpokenQuery.clean(text)
                if (query.isEmpty()) {
                    say("Didn't catch that")
                    return
                }
                setVoice(DialView.Voice.Working(query))
                client.searchAndPlay(query) { result ->
                    when (result) {
                        is RoonClient.SearchResult.Playing ->
                            say("Playing ${result.what}")
                        is RoonClient.SearchResult.NotFound ->
                            say(result.reason)
                    }
                }
            }

            override fun onFailed(reason: String) = say(reason)
        })
    }

    private fun setVoice(state: DialView.Voice) {
        voiceHandler.removeCallbacksAndMessages(null)
        dial.voice = state
    }

    /** Shows a line on the dial, then hands the dial back to what's playing. */
    private fun say(message: String) {
        dial.voice = DialView.Voice.Said(message)
        voiceHandler.removeCallbacksAndMessages(null)
        voiceHandler.postDelayed({ dial.voice = DialView.Voice.Idle }, 3500)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- listener

    override fun onStatus(status: RoonClient.Status) {
        val text = when (status.stage) {
            RoonClient.Stage.IDLE -> status.detail ?: "Idle"
            RoonClient.Stage.DISCOVERING -> "Looking for a Roon Core…"
            RoonClient.Stage.CONNECTING -> status.detail ?: "Connecting…"
            RoonClient.Stage.AWAITING_APPROVAL ->
                "Waiting for approval — Roon → Settings → Extensions → Enable “Dial for Roon”"
            RoonClient.Stage.CONNECTED -> status.detail ?: "Connected"
            RoonClient.Stage.ERROR -> status.detail ?: "Disconnected"
        }
        statusView.text = text
        if (zones.isEmpty()) dial.setStatus(text)
    }

    override fun onZones(zones: List<Zone>, selected: Zone?) {
        this.zones = zones
        dial.setZone(selected)
        loadArtwork(selected?.nowPlaying?.imageKey)
        // Keep any placed widget in step while the app is in front, rather
        // than leaving it on whatever the service last published.
        RoonWidgetProvider.publish(this, selected)
    }

    // ----------------------------------------------------------- dial input

    override fun onVolumeSteps(steps: Int) = client.changeVolumeSteps(steps)

    override fun onPlayPause() = client.control("playpause")

    override fun onNext() = client.control("next")

    override fun onPrevious() = client.control("previous")

    override fun onMuteTapped() = client.toggleMute()

    override fun onZoneTapped() = showZonePicker()

    override fun onLongPress() = showMenu()

    // -------------------------------------------------------------- dialogs

    private fun showZonePicker() {
        if (zones.isEmpty()) {
            Toast.makeText(this, "No zones yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = zones.map { zone ->
            val marker = if (zone.isPlaying) "▶ " else ""
            val volume = zone.primaryVolume?.let { "  ·  ${it.format()}" } ?: "  ·  fixed volume"
            marker + zone.displayName + volume
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Zone")
            .setItems(labels) { _, which -> client.selectZone(zones[which].zoneId) }
            .show()
    }

    private fun showMenu() {
        val options = arrayOf(
            "Choose zone",
            "Reconnect",
            "Find Core again",
            "Enter Core address…",
            "Voice control status",
            if (RoonPlaybackService.claimsMediaControlEnabled(this)) {
                "Stop claiming media control"
            } else {
                "Claim media control (for voice)"
            },
            "About"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Dial for Roon")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showZonePicker()
                    1 -> client.reconnect()
                    2 -> client.rediscover()
                    3 -> showManualAddress()
                    4 -> showVoiceControlStatus()
                    5 -> toggleMediaControlClaim()
                    6 -> showAbout()
                }
            }
            .show()
    }

    private fun showManualAddress() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "192.168.1.10:$DEFAULT_PORT"
            setText(client.currentHost?.let { "$it:${client.currentPort}" } ?: "")
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Roon Core address")
            .setMessage("Host and port of the Core's extension API. The port is normally 9330.")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val host = raw.substringBefore(':')
                val port = raw.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
                client.connectTo(host, port)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVoiceControlStatus() {
        val report = VoiceControlStatus.report(this)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Voice control")
            .setMessage(report.asText())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toggleMediaControlClaim() {
        val enabled = !RoonPlaybackService.claimsMediaControlEnabled(this)
        RoonPlaybackService.setClaimsMediaControl(this, enabled)
        Toast.makeText(
            this,
            if (enabled) {
                "Claiming media control while the zone plays: renders silence so " +
                    "Android treats this as the media app. Costs a little battery."
            } else {
                "No longer claiming media control. Spoken transport commands will " +
                    "not reach this app."
            },
            Toast.LENGTH_LONG
        ).show()
        // Applied on the next zone update, which is a second away while playing.
    }

    private fun showAbout() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Dial for Roon")
            .setMessage(
                "Connects directly to your Roon Core as an extension — no bridge or " +
                    "companion service.\n\n" +
                    "Sweep the ring to change volume. Tap the zone name to switch zones, " +
                    "tap the volume readout to mute, long-press anywhere for this menu.\n\n" +
                    "Core: ${client.currentHost ?: "not connected"}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------- artwork

    private fun loadArtwork(imageKey: String?) {
        if (imageKey == artKey) return
        artKey = imageKey
        if (imageKey == null) {
            dial.setArtwork(null)
            return
        }
        val url = client.imageUrl(imageKey, 720) ?: return
        artLoader.execute {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 8000
                }
                val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                runOnUiThread { if (artKey == imageKey) dial.setArtwork(bitmap) }
            } catch (e: Exception) {
                Log.w(TAG, "artwork fetch failed: ${e.message}")
            }
        }
    }
}
