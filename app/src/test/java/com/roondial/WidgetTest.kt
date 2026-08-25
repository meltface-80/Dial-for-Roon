package com.roondial

import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.roondial.roon.RoonClient
import com.roondial.roon.ZoneStore
import com.roondial.widget.RoonWidgetProvider
import com.roondial.widget.WidgetArtwork
import com.roondial.widget.WidgetSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class WidgetTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val previewDir = File("build/preview").apply { mkdirs() }

    private fun zone(state: String = "playing", volume: Boolean = true) = ZoneStore().apply {
        val vol = if (volume) {
            ""","volume":{"type":"db","min":-80,"max":0,"value":-32.5,"step":0.5,"is_muted":false}"""
        } else ""
        applySubscribed(
            JSONObject(
                """
                {"zones":[{"zone_id":"z1","display_name":"Living Room","state":"$state",
                  "is_next_allowed":true,"is_previous_allowed":true,
                  "outputs":[{"output_id":"o1","display_name":"Living Room"$vol}],
                  "now_playing":{"length":300,"seek_position":126,"image_key":"art1",
                    "three_line":{"line1":"Teardrop","line2":"Massive Attack","line3":"Mezzanine"}}}]}
                """.trimIndent()
            )
        )
    }.all().first()

    // ------------------------------------------------------------- snapshot

    @Test
    fun snapshotCarriesWhatTheWidgetShows() {
        val snapshot = WidgetSnapshot.of(zone())
        assertEquals("Living Room", snapshot.zoneName)
        assertEquals("Teardrop", snapshot.title)
        assertEquals("Massive Attack", snapshot.artist)
        assertEquals("-32.5 dB", snapshot.volumeLabel)
        assertEquals(0.594f, snapshot.volumeFraction, 0.01f)
        assertTrue(snapshot.isPlaying)
    }

    @Test
    fun seekUpdatesDoNotCountAsAChange() {
        // Roon pushes a seek update every second while playing. Re-rendering
        // the widget for each one would be pure waste, so equality has to
        // ignore anything the widget does not draw.
        val before = WidgetSnapshot.of(zone())
        val store = ZoneStore()
        store.applySubscribed(
            JSONObject(
                """
                {"zones":[{"zone_id":"z1","display_name":"Living Room","state":"playing",
                  "outputs":[{"output_id":"o1","display_name":"Living Room",
                    "volume":{"type":"db","min":-80,"max":0,"value":-32.5,"step":0.5,"is_muted":false}}],
                  "now_playing":{"length":300,"seek_position":126,"image_key":"art1",
                    "three_line":{"line1":"Teardrop","line2":"Massive Attack","line3":"Mezzanine"}}}]}
                """.trimIndent()
            )
        )
        store.applyChanged(
            JSONObject("""{"zones_seek_changed":[{"zone_id":"z1","seek_position":190}]}""")
        )
        assertEquals(before, WidgetSnapshot.of(store.all().first()))
    }

    @Test
    fun snapshotSurvivesTheProcessDying() {
        WidgetSnapshot.of(zone()).save(context)
        assertEquals(WidgetSnapshot.of(zone()), WidgetSnapshot.load(context))
    }

    @Test
    fun snapshotOfNoZoneIsEmpty() {
        assertEquals(WidgetSnapshot.EMPTY, WidgetSnapshot.of(null))
        assertEquals(WidgetSnapshot.EMPTY, WidgetSnapshot.load(context))
    }

    // ------------------------------------------------------------- rendering

    private fun inflate(snapshot: WidgetSnapshot): View {
        val views = RoonWidgetProvider.buildViews(context, snapshot)
        return views.apply(context, FrameLayout(context))
    }

    private fun textOf(root: View, id: Int) = root.findViewById<TextView>(id).text.toString()

    @Test
    fun rendersTheZone() {
        val root = inflate(WidgetSnapshot.of(zone()))
        assertEquals("Living Room", textOf(root, R.id.widget_zone))
        assertEquals("Teardrop", textOf(root, R.id.widget_title))
        assertEquals("Massive Attack", textOf(root, R.id.widget_artist))
        assertEquals("-32.5 dB", textOf(root, R.id.widget_volume))

        // A 4x2 home-screen cell is about 250x110dp; render at that size so
        // the preview shows the real proportions rather than a blown-up one.
        val density = context.resources.displayMetrics.density
        val width = (250 * density).toInt()
        val height = (110 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        File(previewDir, "widget.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun saysSoWhenThereIsNoZone() {
        val root = inflate(WidgetSnapshot.EMPTY)
        assertEquals("Dial for Roon", textOf(root, R.id.widget_zone))
        assertEquals("Not connected", textOf(root, R.id.widget_title))
        assertEquals("", textOf(root, R.id.widget_volume))
    }

    @Test
    fun fixedVolumeZoneShowsNoVolume() {
        val root = inflate(WidgetSnapshot.of(zone(volume = false)))
        assertEquals("", textOf(root, R.id.widget_volume))
        assertEquals(-1f, WidgetSnapshot.of(zone(volume = false)).volumeFraction, 0.001f)
    }

    @Test
    fun artworkStaysUnderTheBinderBudget() {
        val bitmap = WidgetArtwork.render(null, 0.6f)
        assertEquals(WidgetArtwork.SIZE, bitmap.width)
        // Every bitmap in a RemoteViews crosses the 1 MB Binder buffer shared
        // by the whole process; a full-size cover would blow it.
        assertTrue(
            "widget bitmap is ${bitmap.byteCount} bytes",
            bitmap.byteCount < 256 * 1024
        )
        File(previewDir, "widget-art.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    // --------------------------------------------------------------- actions

    @Test
    fun buttonsMapToRoonActions() {
        assertEquals(
            RoonClient.Action.PLAY_PAUSE,
            RoonWidgetProvider.actionFor(RoonWidgetProvider.ACTION_PLAY_PAUSE)
        )
        assertEquals(
            RoonClient.Action.NEXT,
            RoonWidgetProvider.actionFor(RoonWidgetProvider.ACTION_NEXT)
        )
        assertEquals(
            RoonClient.Action.PREVIOUS,
            RoonWidgetProvider.actionFor(RoonWidgetProvider.ACTION_PREVIOUS)
        )
        assertEquals(
            RoonClient.Action.VOLUME_UP,
            RoonWidgetProvider.actionFor(RoonWidgetProvider.ACTION_VOLUME_UP)
        )
        assertEquals(
            RoonClient.Action.VOLUME_DOWN,
            RoonWidgetProvider.actionFor(RoonWidgetProvider.ACTION_VOLUME_DOWN)
        )
    }

    @Test
    fun widgetUpdateBroadcastIsNotAnAction() {
        assertNull(RoonWidgetProvider.actionFor("android.appwidget.action.APPWIDGET_UPDATE"))
        assertNull(RoonWidgetProvider.actionFor(null))
    }
}
