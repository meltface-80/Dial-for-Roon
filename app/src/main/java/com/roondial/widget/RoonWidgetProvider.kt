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

        private val artLoader = Executors.newSingleThreadExecutor()

        @Volatile
        private var lastPublished: WidgetSnapshot? = null

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
            if (snapshot == lastPublished) return
            lastPublished = snapshot
            snapshot.save(context)
            render(context, snapshot)
        }

        fun render(context: Context, snapshot: WidgetSnapshot = WidgetSnapshot.load(context)) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            val views = buildViews(context, snapshot)
            manager.updateAppWidget(ids, views)
            ensureArtwork(context, snapshot)
        }

        /** Fetches cover art off the main thread, then redraws once it lands. */
        private fun ensureArtwork(context: Context, snapshot: WidgetSnapshot) {
            val key = snapshot.imageKey
            if (key.isEmpty()) return
            if (WidgetArtwork.cached(context, key) != null) return
            val app = context.applicationContext as? RoonApp ?: return
            val url = app.roon.imageUrl(key, WidgetArtwork.SIZE) ?: return
            artLoader.execute {
                if (WidgetArtwork.fetch(context, url, key) != null) {
                    Handler(Looper.getMainLooper()).post {
                        val ids = widgetIds(context)
                        if (ids.isEmpty()) return@post
                        AppWidgetManager.getInstance(context)
                            .updateAppWidget(ids, buildViews(context, snapshot))
                    }
                }
            }
        }

        fun buildViews(context: Context, snapshot: WidgetSnapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_dial)

            views.setTextViewText(
                R.id.widget_zone,
                if (snapshot.hasZone) snapshot.zoneName else "Dial for Roon"
            )
            views.setTextViewText(
                R.id.widget_title,
                when {
                    !snapshot.hasZone -> "Not connected"
                    snapshot.title.isNotEmpty() -> snapshot.title
                    else -> "Nothing playing"
                }
            )
            views.setTextViewText(R.id.widget_artist, snapshot.artist)
            views.setTextViewText(R.id.widget_volume, snapshot.volumeLabel)
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            )
            views.setImageViewBitmap(
                R.id.widget_art,
                WidgetArtwork.render(
                    WidgetArtwork.cached(context, snapshot.imageKey),
                    snapshot.volumeFraction
                )
            )

            views.setOnClickPendingIntent(R.id.widget_play_pause, broadcast(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_previous, broadcast(context, ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_volume_up, broadcast(context, ACTION_VOLUME_UP))
            views.setOnClickPendingIntent(R.id.widget_volume_down, broadcast(context, ACTION_VOLUME_DOWN))
            views.setOnClickPendingIntent(R.id.widget_art, openApp(context))

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
        appWidgetManager.updateAppWidget(appWidgetIds, buildViews(context, snapshot))
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
    }
}
