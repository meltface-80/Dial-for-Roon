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
import kotlin.math.roundToInt

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
        const val SERVICE_BROWSE = "com.roonlabs.browse:1"
        const val SERVICE_PING = "com.roonlabs.ping:1"
        const val SERVICE_REGISTRY = "com.roonlabs.registry:1"

        /**
         * Which transport control a play/pause press should send.
         *
         * Deliberately not Roon's own `playpause` toggle. A zone left paused
         * drifts to stopped rather than staying paused, and a toggle does
         * nothing useful from there — which is why pressing play would leave
         * the music off while skipping a track started it again. `play`
         * resumes from either state.
         */
        fun playPauseControl(zone: Zone): String = if (zone.isPlaying) "pause" else "play"
    }

    enum class Stage { IDLE, DISCOVERING, CONNECTING, AWAITING_APPROVAL, CONNECTED, ERROR }

    /**
     * A control surface's intent, resolved against whatever the zone turns out
     * to be. Widget presses are queued as these rather than as concrete
     * requests, because when the press arrives there may be no zone yet — and
     * "volume up" means a different number of steps on different outputs.
     */
    enum class Action {
        PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS,
        VOLUME_UP, VOLUME_DOWN, TOGGLE_MUTE, MUTE, UNMUTE
    }

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

    /** True only while registered with a Core and holding a live socket. */
    @Volatile private var connected: Boolean = false
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
            // The close callback bails out once `socket` is null, so it will
            // not do this for us. Leaving a zone behind here is what made a
            // widget press look like it worked and then do nothing: the zone
            // was still there to act on, but the socket to act through was not.
            markDisconnected()
        }
        releaseMulticastLock()
    }

    private fun markDisconnected() {
        connected = false
        zoneStore.clear()
        selectedSnapshot = null
        zonesSnapshot = emptyList()
        main.post { listeners.forEach { it.onZones(emptyList(), null) } }
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
            coreId = body?.str("core_id")?.takeIf { it.isNotEmpty() }
            coreName = body?.str("display_name")?.takeIf { it.isNotEmpty() } ?: coreName
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
            put("optional_services", JSONArray().put(SERVICE_IMAGE).put(SERVICE_BROWSE))
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
                val id = body?.str("core_id")?.takeIf { it.isNotEmpty() } ?: coreId
                coreId = id
                coreName = body?.str("display_name")?.takeIf { it.isNotEmpty() } ?: coreName
                body?.str("token")?.takeIf { it.isNotEmpty() }?.let { saveToken(id, it) }
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
        connected = socket != null
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
        if (!connected || selectedZone() == null) {
            pendingActions.add(action, System.currentTimeMillis())
            start()
            return
        }
        execute(action)
    }

    private fun execute(action: Action) {
        val zone = selectedZone()
        if (zone == null || !connected) {
            // Lost the Core between deciding to act and acting. Hold the press
            // rather than dropping it silently.
            pendingActions.add(action, System.currentTimeMillis())
            start()
            return
        }
        when (action) {
            // Explicit rather than Roon's playpause toggle: a toggle does
            // nothing useful on a zone that has stopped rather than paused,
            // which is the state a zone drifts into when left paused, whereas
            // play resumes it.
            Action.PLAY_PAUSE -> control(playPauseControl(zone))
            Action.PLAY -> control("play")
            Action.PAUSE -> control("pause")
            Action.MUTE -> setMuted(true)
            Action.UNMUTE -> setMuted(false)
            Action.NEXT -> control("next")
            Action.PREVIOUS -> control("previous")
            Action.VOLUME_UP -> changeVolumeSteps(nudgeSteps(zone))
            Action.VOLUME_DOWN -> changeVolumeSteps(-nudgeSteps(zone))
            Action.TOGGLE_MUTE -> toggleMute()
        }
    }

    /** Sets the volume to a percentage of the output's usable range. */
    fun setVolumePercent(percent: Int) {
        val zone = selectedZone() ?: return
        net.post {
            for (out in zone.volumeOutputs) {
                val vol = out.volume ?: continue
                if (vol.isIncremental) continue
                val span = vol.effectiveMax - vol.min
                if (span <= 0.0) continue
                val target = vol.min + span * (percent.coerceIn(0, 100) / 100.0)
                sendRequest(
                    SERVICE_TRANSPORT, "change_volume",
                    JSONObject()
                        .put("output_id", out.outputId)
                        .put("how", "absolute")
                        .put("value", target)
                )
            }
        }
    }

    /**
     * Moves the volume by [multiplier] of a normal nudge. Spoken requests
     * carry a size — "turn it up a bit" against "turn it up a lot".
     */
    fun nudgeVolume(direction: Int, multiplier: Float) {
        val zone = selectedZone() ?: return
        val steps = (nudgeSteps(zone) * multiplier).roundToInt().coerceAtLeast(1)
        changeVolumeSteps(if (direction < 0) -steps else steps)
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

    // ---------------------------------------------------------------- search

    /** How far to walk before deciding Roon is not going to offer a play action. */
    private val maxBrowseDepth = 6

    private var browseSession = 0

    sealed class SearchResult {
        data class Playing(val what: String) : SearchResult()
        data class NotFound(val reason: String) : SearchResult()
    }

    /**
     * Finds [query] in Roon and plays it in the selected zone.
     *
     * Roon's browse API is a hierarchy walk rather than a query language, so
     * this searches, then keeps opening the first real result until it reaches
     * a list of actions, then takes the one that plays. Everything about which
     * item to open lives in BrowsePlan; this only moves between levels.
     */
    fun searchAndPlay(query: String, onResult: (SearchResult) -> Unit) {
        val zone = selectedZone()
        if (zone == null || !connected) {
            main.post { onResult(SearchResult.NotFound("Not connected to a zone")) }
            return
        }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            main.post { onResult(SearchResult.NotFound("Nothing heard")) }
            return
        }

        net.post {
            // Roon keeps the browse cursor server-side, and its auto-pop after
            // an action leaves it mid-tree. A key per search stops one walk
            // reading another's level.
            val session = "dial-${browseSession++}"
            val body = JSONObject()
                .put("hierarchy", "search")
                .put("multi_session_key", session)
                .put("input", trimmed)
                .put("pop_all", true)
                .put("zone_or_output_id", zone.zoneId)
            sendRequest(SERVICE_BROWSE, "browse", body) { msg ->
                handleBrowse(msg, zone.zoneId, session, trimmed, 0, false, onResult)
            }
        }
    }

    private fun finish(onResult: (SearchResult) -> Unit, result: SearchResult) {
        main.post { onResult(result) }
    }

    private fun handleBrowse(
        msg: Moo.Message,
        zoneId: String,
        session: String,
        query: String,
        depth: Int,
        played: Boolean,
        onResult: (SearchResult) -> Unit
    ) {
        val body = msg.bodyText?.let { JSONObject(it) }
        if (msg.name != "Success" || body == null) {
            finish(onResult, SearchResult.NotFound("Roon refused the search"))
            return
        }

        val outcome = BrowsePlan.afterBrowse(
            played = played,
            action = body.str("action"),
            isError = body.optBoolean("is_error", false),
            message = body.str("message")
        )
        when (outcome) {
            is BrowsePlan.Outcome.Playing -> {
                finish(onResult, SearchResult.Playing(query)); return
            }
            is BrowsePlan.Outcome.Stop -> {
                finish(onResult, SearchResult.NotFound(outcome.message)); return
            }
            is BrowsePlan.Outcome.KeepWalking -> Unit
        }

        if (depth >= maxBrowseDepth) {
            finish(onResult, SearchResult.NotFound("Nothing playable found for \"$query\""))
            return
        }

        val listHint = body.optJSONObject("list")?.str("hint")?.takeIf { it.isNotEmpty() }
        val load = JSONObject()
            .put("hierarchy", "search")
            .put("multi_session_key", session)
            .put("offset", 0)
            .put("count", 25)
        sendRequest(SERVICE_BROWSE, "load", load) { loadMsg ->
            handleLoad(loadMsg, listHint, zoneId, session, query, depth, onResult)
        }
    }

    private fun handleLoad(
        msg: Moo.Message,
        listHint: String?,
        zoneId: String,
        session: String,
        query: String,
        depth: Int,
        onResult: (SearchResult) -> Unit
    ) {
        val body = msg.bodyText?.let { JSONObject(it) }
        if (msg.name != "Success" || body == null) {
            finish(onResult, SearchResult.NotFound("Roon returned nothing for that"))
            return
        }

        val items = ArrayList<BrowseItem>()
        body.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) items += BrowseItem.parse(arr.getJSONObject(i))
        }
        val hint = body.optJSONObject("list")?.str("hint")?.takeIf { it.isNotEmpty() }
            ?: listHint

        when (val step = BrowsePlan.next(hint, items)) {
            is BrowsePlan.Step.GiveUp ->
                finish(onResult, SearchResult.NotFound(step.reason))

            is BrowsePlan.Step.Play ->
                browseInto(step.itemKey, zoneId, session, query, depth + 1, true, onResult)

            is BrowsePlan.Step.Descend ->
                browseInto(step.itemKey, zoneId, session, step.title, depth + 1, false, onResult)
        }
    }

    private fun browseInto(
        itemKey: String,
        zoneId: String,
        session: String,
        label: String,
        depth: Int,
        played: Boolean,
        onResult: (SearchResult) -> Unit
    ) {
        val body = JSONObject()
            .put("hierarchy", "search")
            .put("multi_session_key", session)
            .put("item_key", itemKey)
            .put("zone_or_output_id", zoneId)
        sendRequest(SERVICE_BROWSE, "browse", body) { msg ->
            handleBrowse(msg, zoneId, session, label, depth, played, onResult)
        }
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
                connected = false
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
                connected = false
                selectedSnapshot = null
                zonesSnapshot = emptyList()
                status = Status(Stage.IDLE, coreName, "Disconnected")
                main.post { listeners.forEach { it.onZones(emptyList(), null) } }
                scheduleReconnect()
            }
        }
    }
}
