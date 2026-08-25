package com.roondial.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import com.roondial.R
import com.roondial.RoonApp
import com.roondial.roon.RoonClient
import com.roondial.roon.Zone
import java.util.concurrent.Executors

/**
 * Home-screen control for the selected Roon zone.
 *
 * Two things shape this. A widget is [RemoteViews], so there are no custom
 * views and no gestures — the ring becomes a rendered bitmap that reads the
 * level, and the controls become buttons. And a widget is usually pressed when
 * the app is not running, so a press has to survive the wait for the extension
 * to register: it is queued as an intent and run once there is a zone.
 */
class RoonWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "RoonWidget"

        const val ACTION_PLAY_PAUSE = "com.roondial.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.roondial.widget.NEXT"
        const val ACTION_PREVIOUS = "com.roondial.widget.PREVIOUS"
        const val ACTION_VOLUME_UP = "com.roondial.widget.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.roondial.widget.VOLUME_DOWN"

        /**
         * How long a press may hold the broadcast open while the connection
         * comes up. Comfortably inside the receiver timeout, and cut short as
         * soon as the zone reports back.
         */
        private const val WAKE_BUDGET_MS = 6_000L

        /** Time to wait for the zone's echo before releasing the broadcast. */
        private const val ECHO_GRACE_MS = 1_200L

        /**
         * Drawing the dial means laying out a view and compressing a 512px
         * image, so a volume sweep in the app — which changes the ring on
         * every frame — must not turn into a render per frame out here.
         */
        private const val MIN_RENDER_INTERVAL_MS = 500L

        private val artLoader = Executors.newSingleThreadExecutor()

        @Volatile
        private var lastRenderAt = 0L
        private var trailingRender: Runnable? = null

        @Volatile
        private var lastPublished: WidgetSnapshot? = null

        /** Kept so artwork arriving late, or a resize, can redraw the dial. */
        @Volatile
        private var lastZone: Zone? = null

        fun actionFor(intentAction: String?): RoonClient.Action? = when (intentAction) {
            ACTION_PLAY_PAUSE -> RoonClient.Action.PLAY_PAUSE
            ACTION_NEXT -> RoonClient.Action.NEXT
            ACTION_PREVIOUS -> RoonClient.Action.PREVIOUS
            ACTION_VOLUME_UP -> RoonClient.Action.VOLUME_UP
            ACTION_VOLUME_DOWN -> RoonClient.Action.VOLUME_DOWN
            else -> null
        }

        fun hasWidgets(context: Context): Boolean = widgetIds(context).isNotEmpty()

        private fun widgetIds(context: Context): IntArray = try {
            AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, RoonWidgetProvider::class.java)
            )
        } catch (e: Exception) {
            IntArray(0)
        }

        /**
         * Pushes zone state to every placed widget. Called on each zone update,
         * so it drops out early when nothing a widget shows has changed — Roon
         * sends a seek update every second and none of them matter here.
         */
        fun publish(context: Context, zone: Zone?) {
            val snapshot = WidgetSnapshot.of(zone)
            lastZone = zone
            if (snapshot == lastPublished) return
            lastPublished = snapshot
            snapshot.save(context)

            val main = Handler(Looper.getMainLooper())
            trailingRender?.let { main.removeCallbacks(it) }

            val now = android.os.SystemClock.uptimeMillis()
            val since = now - lastRenderAt
            if (since >= MIN_RENDER_INTERVAL_MS) {
                lastRenderAt = now
                render(context, zone, snapshot)
                return
            }
            // Mid-sweep: let it settle, then draw where it landed.
            val trailing = Runnable {
                lastRenderAt = android.os.SystemClock.uptimeMillis()
                val latest = lastZone
                render(context, latest, WidgetSnapshot.of(latest))
            }
            trailingRender = trailing
            main.postDelayed(trailing, MIN_RENDER_INTERVAL_MS - since)
        }

        fun render(
            context: Context,
            zone: Zone?,
            snapshot: WidgetSnapshot = WidgetSnapshot.load(context)
        ) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val cover = WidgetArtwork.cached(context, snapshot.imageKey)
            val dial = WidgetDial.render(context, zone, statusFor(snapshot), cover)
            if (dial != null) WidgetDial.cache(context, dial)
            AppWidgetManager.getInstance(context)
                .updateAppWidget(ids, buildViews(context, dial))
            ensureArtwork(context, snapshot)
        }

        private fun statusFor(snapshot: WidgetSnapshot): String =
            if (snapshot.hasZone) "" else "Open Dial for Roon to connect"

        /**
         * The dial with whatever art and state was last drawn — for a widget
         * whose process has since been killed, where re-rendering would mean
         * showing an empty dial until the extension reconnects.
         */
        private fun lastRendered(context: Context): ByteArray? = WidgetDial.cached(context)

        /** Fetches cover art off the main thread, then redraws once it lands. */
        private fun ensureArtwork(context: Context, snapshot: WidgetSnapshot) {
            val key = snapshot.imageKey
            if (key.isEmpty()) return
            if (WidgetArtwork.cached(context, key) != null) return
            val app = context.applicationContext as? RoonApp ?: return
            val url = app.roon.imageUrl(key, WidgetArtwork.SIZE) ?: return
            artLoader.execute {
                val cover = WidgetArtwork.fetch(context, url, key) ?: return@execute
                Handler(Looper.getMainLooper()).post {
                    val ids = widgetIds(context)
                    if (ids.isEmpty()) return@post
                    val dial = WidgetDial.render(
                        context, lastZone, statusFor(snapshot), cover
                    ) ?: return@post
                    WidgetDial.cache(context, dial)
                    AppWidgetManager.getInstance(context)
                        .updateAppWidget(ids, buildViews(context, dial))
                }
            }
        }

        fun buildViews(context: Context, dial: ByteArray?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_dial)

            val image = dial ?: lastRendered(context)
            if (image != null) views.setImageViewIcon(R.id.widget_dial, WidgetDial.icon(image))

            views.setOnClickPendingIntent(R.id.widget_play_pause, broadcast(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_previous, broadcast(context, ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_volume_up, broadcast(context, ACTION_VOLUME_UP))
            views.setOnClickPendingIntent(R.id.widget_volume_down, broadcast(context, ACTION_VOLUME_DOWN))
            views.setOnClickPendingIntent(R.id.widget_open, openApp(context))
            views.setOnClickPendingIntent(R.id.widget_open_centre, openApp(context))

            return views
        }

        private fun broadcast(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, RoonWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun openApp(context: Context): PendingIntent {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent()
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val snapshot = lastPublished ?: WidgetSnapshot.load(context)
        val zone = lastZone
        val dial = if (zone != null) {
            WidgetDial.render(
                context, zone, statusFor(snapshot),
                WidgetArtwork.cached(context, snapshot.imageKey)
            )?.also { WidgetDial.cache(context, it) }
        } else {
            // Nothing live to draw yet; the last picture beats an empty dial.
            lastRendered(context)
                ?: WidgetDial.render(context, null, statusFor(snapshot), null)
        }
        appWidgetManager.updateAppWidget(appWidgetIds, buildViews(context, dial))
        ensureArtwork(context, snapshot)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = actionFor(intent.action)
        if (action == null) {
            super.onReceive(context, intent)
            return
        }

        // The press has to outlive this method: with the app cold there is no
        // connection yet, so the broadcast is held open just long enough for
        // the extension to register and the queued action to run.
        val result = goAsync()
        val app = context.applicationContext as? RoonApp
        if (app == null) {
            result.finish()
            return
        }

        val client = app.roon
        val main = Handler(Looper.getMainLooper())
        var finished = false

        lateinit var listener: RoonClient.Listener
        val finish = Runnable {
            if (finished) return@Runnable
            finished = true
            client.removeListener(listener)
            result.finish()
        }

        listener = object : RoonClient.Listener {
            override fun onStatus(status: RoonClient.Status) = Unit
            override fun onZones(zones: List<Zone>, selected: Zone?) {
                publish(context, selected)
                if (selected != null && !finished) {
                    // The zone answered, so the action has landed or is about
                    // to. Hold on briefly for its echo, then let go rather
                    // than sitting on the broadcast for the full budget.
                    main.removeCallbacks(finish)
                    main.postDelayed(finish, ECHO_GRACE_MS)
                }
            }
        }

        client.addListener(listener)
        try {
            client.perform(action)
        } catch (e: Exception) {
            Log.w(TAG, "widget action failed: ${e.message}")
        }

        main.postDelayed(finish, WAKE_BUDGET_MS)
    }

    override fun onDisabled(context: Context) {
        WidgetArtwork.clearMemoryCache()
        lastPublished = null
        lastZone = null
    }
}
