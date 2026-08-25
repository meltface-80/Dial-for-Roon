package com.roondial.roon

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.roondial.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Speaks the Roon extension protocol directly: SOOD to find the Core, then a
 * MOO session over a plain WebSocket. No bridge, no companion service.
 *
 * Lifecycle:
 *   discover -> ws://host:port/api -> registry:1/info -> registry:1/register
 *   -> (user enables the extension in Roon once) -> "Registered" + token
 *   -> transport:2/subscribe_zones
 *
 * The token is persisted per Core id, so approval only ever happens once.
 */
class RoonClient(context: Context) : ZoneControl {

    companion object {
        private const val TAG = "RoonClient"
        private const val PREFS = "roon_dial"
        private const val KEY_HOST = "last_host"
        private const val KEY_PORT = "last_port"
        private const val KEY_ZONE = "selected_zone"

        const val EXTENSION_ID = "com.roondial.android"
        const val SERVICE_TRANSPORT = "com.roonlabs.transport:2"
        const val SERVICE_IMAGE = "com.roonlabs.image:1"
        const val SERVICE_PING = "com.roonlabs.ping:1"
        const val SERVICE_REGISTRY = "com.roonlabs.registry:1"
    }

    enum class Stage { IDLE, DISCOVERING, CONNECTING, AWAITING_APPROVAL, CONNECTED, ERROR }

    /**
     * A control surface's intent, resolved against whatever the zone turns out
     * to be. Widget presses are queued as these rather than as concrete
     * requests, because when the press arrives there may be no zone yet — and
     * "volume up" means a different number of steps on different outputs.
     */
    enum class Action { PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, TOGGLE_MUTE }

    data class Status(val stage: Stage, val coreName: String? = null, val detail: String? = null)

    interface Listener {
        fun onStatus(status: Status)
        fun onZones(zones: List<Zone>, selected: Zone?)
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val netThread = HandlerThread("roon-net").apply { start() }
    private val net = Handler(netThread.looper)

    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: WebSocket? = null
    private var requestId = 0
    private val pending = HashMap<String, (Moo.Message) -> Unit>()

    private val pendingActions = PendingActions()
    private val zoneStore = ZoneStore()
    private var selectedZoneId: String? = prefs.getString(KEY_ZONE, null)

    /** Read by the UI thread; only ever written on the network thread. */
    @Volatile private var selectedSnapshot: Zone? = null
    @Volatile private var zonesSnapshot: List<Zone> = emptyList()

    private var coreId: String? = null
    private var coreName: String? = null
    private var host: String? = null
    private var port: Int = 0
    private var running = false
    private var backoffMs = 1000L

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    /** Replays the current state to the new listener so it starts in sync. */
    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        val zones = zonesSnapshot
        val snapshot = selectedSnapshot
        val current = status
        main.post {
            listener.onStatus(current)
            listener.onZones(zones, snapshot)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private var status = Status(Stage.IDLE)
        set(value) {
            field = value
            main.post { listeners.forEach { it.onStatus(value) } }
        }

    val currentHost: String? get() = host
    val currentPort: Int get() = port

    // ---------------------------------------------------------------- control

    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        net.post { connectOrDiscover() }
    }

    fun stop() {
        running = false
        net.post {
            socket?.close(1000, "stopping")
            socket = null
            pending.clear()
        }
        releaseMulticastLock()
    }

    /** Drop the current socket and connect again to the same Core. */
    fun reconnect() {
        net.post {
            socket?.close(1000, "reconnect")
            socket = null
            backoffMs = 1000L
            if (running) connectOrDiscover()
        }
    }

    /** Forget the saved Core address and start discovery from scratch. */
    fun rediscover() {
        net.post {
            prefs.edit().remove(KEY_HOST).remove(KEY_PORT).apply()
            host = null
            socket?.close(1000, "rediscover")
            socket = null
            backoffMs = 1000L
            connectOrDiscover()
        }
    }

    /** Connect to a manually entered Core address. */
    fun connectTo(hostName: String, portNumber: Int) {
        net.post {
            socket?.close(1000, "manual reconnect")
            socket = null
            host = hostName
            port = portNumber
            backoffMs = 1000L
            openSocket(hostName, portNumber)
        }
    }

    private fun connectOrDiscover() {
        if (!running) return
        val savedHost = prefs.getString(KEY_HOST, null)
        val savedPort = prefs.getInt(KEY_PORT, 0)
        if (savedHost != null && savedPort > 0) {
            host = savedHost
            port = savedPort
            openSocket(savedHost, savedPort)
            return
        }
        discover()
    }

    private fun discover() {
        if (!running) return
        status = Status(Stage.DISCOVERING, detail = "Looking for a Roon Core on this network")
        var found = false
        try {
            Sood.discover(8000) { core ->
                if (!found) {
                    found = true
                    host = core.host
                    port = core.port
                    coreName = core.displayName
                    prefs.edit().putString(KEY_HOST, core.host).putInt(KEY_PORT, core.port).apply()
                    net.post { openSocket(core.host, core.port) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed", e)
        }
        if (!found && running) {
            status = Status(
                Stage.ERROR,
                detail = "No Roon Core found. Check you're on the same Wi-Fi, or enter the address manually."
            )
            net.postDelayed({ connectOrDiscover() }, 10_000)
        }
    }

    private fun openSocket(h: String, p: Int) {
        if (!running) return
        status = Status(Stage.CONNECTING, coreName, "Connecting to $h:$p")
        val request = Request.Builder().url("ws://$h:$p/api").build()
        socket = http.newWebSocket(request, SocketListener())
    }

    private fun scheduleReconnect() {
        if (!running) return
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        net.postDelayed({
            if (!running) return@postDelayed
            val h = host
            if (h != null && port > 0) openSocket(h, port) else connectOrDiscover()
        }, delay)
    }

    // ------------------------------------------------------------------- MOO

    private fun sendRequest(
        service: String,
        method: String,
        body: JSONObject? = null,
        onReply: ((Moo.Message) -> Unit)? = null
    ) {
        val ws = socket ?: return
        val id = requestId++
        if (onReply != null) pending[id.toString()] = onReply
        val payload = Moo.encode(
            Moo.VERB_REQUEST,
            "$service/$method",
            id,
            body?.toString()?.toByteArray(Charsets.UTF_8)
        )
        ws.send(payload.toByteString(0, payload.size))
    }

    private fun sendReply(verb: String, name: String, requestId: String, body: JSONObject? = null) {
        val ws = socket ?: return
        val id = requestId.toIntOrNull() ?: return
        val payload = Moo.encode(verb, name, id, body?.toString()?.toByteArray(Charsets.UTF_8))
        ws.send(payload.toByteString(0, payload.size))
    }

    private fun handleMessage(msg: Moo.Message) {
        if (msg.verb == Moo.VERB_REQUEST) {
            // The Core calls into the services we advertise.
            if (msg.service == SERVICE_PING && msg.name == "ping") {
                sendReply(Moo.VERB_COMPLETE, "Success", msg.requestId)
            } else {
                sendReply(Moo.VERB_COMPLETE, "InvalidRequest", msg.requestId)
            }
            return
        }

        val handler = pending[msg.requestId]
        if (handler == null) {
            Log.d(TAG, "unmatched response ${msg.verb} ${msg.name} id=${msg.requestId}")
            return
        }
        if (msg.verb == Moo.VERB_COMPLETE) pending.remove(msg.requestId)
        handler(msg)
    }

    private fun onSocketOpen() {
        backoffMs = 1000L
        sendRequest(SERVICE_REGISTRY, "info") { msg ->
            val body = msg.bodyText?.let { JSONObject(it) }
            coreId = body?.optString("core_id")?.takeIf { it.isNotEmpty() }
            coreName = body?.optString("display_name")?.takeIf { it.isNotEmpty() } ?: coreName
            register()
        }
    }

    private fun register() {
        val reginfo = JSONObject().apply {
            put("extension_id", EXTENSION_ID)
            put("display_name", "Dial for Roon (Android)")
            put("display_version", BuildConfig.VERSION_NAME)
            put("publisher", "Display-extension-apk")
            put("email", "noreply@example.com")
            put("website", "https://github.com/meltface-80/Display-extension-apk")
            put("required_services", JSONArray().put(SERVICE_TRANSPORT))
            put("optional_services", JSONArray().put(SERVICE_IMAGE))
            put("provided_services", JSONArray().put(SERVICE_PING))
            tokenFor(coreId)?.let { put("token", it) }
        }

        status = Status(
            Stage.AWAITING_APPROVAL,
            coreName,
            "Enable “Dial for Roon” in Roon → Settings → Extensions"
        )

        sendRequest(SERVICE_REGISTRY, "register", reginfo) { msg ->
            if (msg.name == "Registered") {
                val body = msg.bodyText?.let { JSONObject(it) }
                val id = body?.optString("core_id")?.takeIf { it.isNotEmpty() } ?: coreId
                coreId = id
                coreName = body?.optString("display_name")?.takeIf { it.isNotEmpty() } ?: coreName
                body?.optString("token")?.takeIf { it.isNotEmpty() }?.let { saveToken(id, it) }
                status = Status(Stage.CONNECTED, coreName, "Paired with ${coreName ?: "Roon"}")
                subscribeZones()
            } else {
                Log.w(TAG, "register replied ${msg.name}: ${msg.bodyText}")
                status = Status(Stage.ERROR, coreName, "Roon refused registration (${msg.name})")
            }
        }
    }

    private fun subscribeZones() {
        val body = JSONObject().put("subscription_key", 0)
        sendRequest(SERVICE_TRANSPORT, "subscribe_zones", body) { msg ->
            val json = msg.bodyText?.let { JSONObject(it) } ?: return@sendRequest
            when (msg.name) {
                "Subscribed" -> zoneStore.applySubscribed(json)
                "Changed" -> zoneStore.applyChanged(json)
                "Unsubscribed" -> zoneStore.clear()
                else -> return@sendRequest
            }
            publishZones()
        }
    }

    private fun publishZones() {
        val all = zoneStore.all()
        if (selectedZoneId == null || zoneStore.byId(selectedZoneId) == null) {
            // Prefer something that is actually playing.
            selectedZoneId = (all.firstOrNull { it.isPlaying } ?: all.firstOrNull())?.zoneId
            selectedZoneId?.let { prefs.edit().putString(KEY_ZONE, it).apply() }
        }
        val sel = zoneStore.byId(selectedZoneId)
        selectedSnapshot = sel
        zonesSnapshot = all
        if (sel != null && !pendingActions.isEmpty()) {
            pendingActions.drain(System.currentTimeMillis()).forEach { execute(it) }
        }
        main.post { listeners.forEach { it.onZones(all, sel) } }
    }

    override fun selectedZone(): Zone? = selectedSnapshot

    fun selectZone(zoneId: String) {
        net.post {
            selectedZoneId = zoneId
            prefs.edit().putString(KEY_ZONE, zoneId).apply()
            publishZones()
        }
    }

    /**
     * Runs [action] now if there is a zone, otherwise holds it until there is.
     * Safe to call from any thread.
     */
    fun perform(action: Action) {
        if (selectedZone() == null) {
            pendingActions.add(action, System.currentTimeMillis())
            start()
            return
        }
        execute(action)
    }

    private fun execute(action: Action) {
        val zone = selectedZone() ?: return
        when (action) {
            Action.PLAY_PAUSE -> control("playpause")
            Action.NEXT -> control("next")
            Action.PREVIOUS -> control("previous")
            Action.VOLUME_UP -> changeVolumeSteps(nudgeSteps(zone))
            Action.VOLUME_DOWN -> changeVolumeSteps(-nudgeSteps(zone))
            Action.TOGGLE_MUTE -> toggleMute()
        }
    }

    /**
     * How far one press of a button moves the volume. A single step is right
     * for the volume rocker but too fine for a button you have to aim at: on a
     * 0.5 dB output that would be 160 presses end to end. A sixty-fourth of the
     * range lands near 1 dB on a typical DAC.
     */
    private fun nudgeSteps(zone: Zone): Int {
        val volume = zone.primaryVolume ?: return 1
        if (volume.isIncremental) return 1
        val span = volume.effectiveMax - volume.min
        if (span <= 0.0 || volume.step <= 0.0) return 1
        val total = (span / volume.step).toInt()
        return (total / 64).coerceAtLeast(1)
    }

    // -------------------------------------------------------- transport verbs

    /** control: "play", "pause", "playpause", "stop", "previous", "next". */
    override fun control(control: String) {
        val zone = selectedZone() ?: return
        net.post {
            sendRequest(
                SERVICE_TRANSPORT, "control",
                JSONObject().put("zone_or_output_id", zone.zoneId).put("control", control)
            )
        }
    }

    /**
     * Volume is per output. `relative_step` moves by whole steps of whatever
     * the device's native scale is, which is what a detented ring wants.
     */
    override fun changeVolumeSteps(steps: Int) {
        if (steps == 0) return
        val zone = selectedZone() ?: return
        net.post {
            for (out in zone.volumeOutputs) {
                val vol = out.volume ?: continue
                // An incremental control has no scale to step through: Roon's
                // guidance is to send relative +1/-1 only.
                val how = if (vol.isIncremental) "relative" else "relative_step"
                val value = if (vol.isIncremental) (if (steps > 0) 1 else -1) else steps
                sendRequest(
                    SERVICE_TRANSPORT, "change_volume",
                    JSONObject()
                        .put("output_id", out.outputId)
                        .put("how", how)
                        .put("value", value)
                )
            }
        }
    }

    fun toggleMute() {
        val muted = selectedZone()?.primaryVolume?.isMuted ?: return
        setMuted(!muted)
    }

    override fun setMuted(muted: Boolean) {
        val zone = selectedZone() ?: return
        net.post {
            for (out in zone.volumeOutputs) {
                sendRequest(
                    SERVICE_TRANSPORT, "mute",
                    JSONObject()
                        .put("output_id", out.outputId)
                        .put("how", if (muted) "mute" else "unmute")
                )
            }
        }
    }

    /** how is "relative" (seconds from the current position) or "absolute". */
    override fun seek(seconds: Long, how: String) {
        val zone = selectedZone() ?: return
        if (!zone.isSeekAllowed) return
        net.post {
            sendRequest(
                SERVICE_TRANSPORT, "seek",
                JSONObject()
                    .put("zone_or_output_id", zone.zoneId)
                    .put("how", how)
                    .put("seconds", seconds)
            )
        }
    }

    /**
     * Album art. The image service is also reachable over plain HTTP on the
     * same host and port, which lets any image loader fetch it directly.
     */
    override fun imageUrl(imageKey: String, size: Int): String? {
        val h = host ?: return null
        if (port <= 0) return null
        return "http://$h:$port/api/image/$imageKey" +
            "?scale=fit&width=$size&height=$size&format=image/jpeg"
    }

    // ------------------------------------------------------------- plumbing

    private fun tokenFor(id: String?): String? =
        if (id == null) null else prefs.getString("token_$id", null)

    private fun saveToken(id: String?, token: String) {
        if (id == null) return
        prefs.edit().putString("token_$id", token).apply()
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("roon-dial-sood").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not acquire multicast lock", e)
        }
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.release() } catch (_: Exception) { }
        multicastLock = null
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            net.post { if (socket === webSocket) onSocketOpen() }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val parsed = Moo.parse(bytes.toByteArray()) ?: return
            net.post { if (socket === webSocket) handleMessage(parsed) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val parsed = Moo.parse(text.toByteArray(Charsets.UTF_8)) ?: return
            net.post { if (socket === webSocket) handleMessage(parsed) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message}")
            net.post {
                if (socket !== webSocket) return@post
                socket = null
                pending.clear()
                zoneStore.clear()
                selectedSnapshot = null
                zonesSnapshot = emptyList()
                status = Status(Stage.ERROR, coreName, "Lost connection: ${t.message}")
                main.post { listeners.forEach { it.onZones(emptyList(), null) } }
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            net.post {
                if (socket !== webSocket) return@post
                socket = null
                pending.clear()
                zoneStore.clear()
                selectedSnapshot = null
                zonesSnapshot = emptyList()
                status = Status(Stage.IDLE, coreName, "Disconnected")
                main.post { listeners.forEach { it.onZones(emptyList(), null) } }
                scheduleReconnect()
            }
        }
    }
}
