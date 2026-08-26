package com.roondial

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.FrameLayout
import com.roondial.roon.RoonClient
import com.roondial.roon.ZoneStore
import com.roondial.widget.RoonWidgetProvider
import com.roondial.widget.WidgetDial
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

    /** DialView's geometry, at the density the test renders under. */
    private val density get() = context.resources.displayMetrics.density
    private val dialRadius get() = WidgetDial.SIZE / 2f - 8f * density
    private val ringWidth get() = dialRadius * 0.115f
    private val ringMid get() = dialRadius - ringWidth / 2f
    private val dialInnerRadius get() = dialRadius - ringWidth - 10f * density

    private fun renderDial(zoneOrNull: com.roondial.roon.Zone?, status: String = ""): ByteArray {
        val data = WidgetDial.render(context, zoneOrNull, status, null)
        assertTrue("dial did not render", data != null)
        return data!!
    }

    @Test
    fun theWidgetIsTheAppsDial() {
        val data = renderDial(zone())
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        assertEquals(WidgetDial.SIZE, bitmap.width)
        assertEquals(WidgetDial.SIZE, bitmap.height)

        // The ring's accent has to actually be on the image: this is the same
        // DialView the app draws, so if it rendered, the widget matches.
        // Sampled off the horizontal axis, because 3 and 9 o'clock are where
        // the volume buttons sit.
        val centre = WidgetDial.SIZE / 2f
        val diagonal = ringMid * 0.7071f
        val onRing = bitmap.getPixel(
            (centre + diagonal).toInt(),
            (centre - diagonal).toInt()
        )
        assertTrue(
            "expected the ring's accent, got ${Integer.toHexString(onRing)}",
            isNear(onRing, 0xFF7AC8FF.toInt())
        )

        File(previewDir, "widget-dial.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun volumeIsTwoRealButtonsNotAHint() {
        // On the home screen the ring cannot be swept — a drag there belongs to
        // the launcher — so these buttons are the only way to change volume and
        // have to look like it. They sit at 9 and 3 o'clock, the size of the
        // transport controls.
        val data = renderDial(zone())
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        val centre = WidgetDial.SIZE / 2
        val offset = ringMid.toInt()

        val buttonRadius = (dialInnerRadius * 0.17f).toInt()

        for (x in listOf(centre - offset, centre + offset)) {
            // Dead centre is the - or + mark itself.
            assertTrue(
                "no mark at $x, got ${Integer.toHexString(bitmap.getPixel(x, centre))}",
                isNear(bitmap.getPixel(x, centre), 0xFFF2F5F8.toInt())
            )
            // Just off centre is the button's own dark face, which is what
            // makes it read as a control rather than as decoration on the ring.
            val face = bitmap.getPixel(x, centre - (buttonRadius * 0.55f).toInt())
            assertTrue(
                "no button face at $x, got ${Integer.toHexString(face)}",
                isNear(face, 0xFF0E141C.toInt())
            )
        }

        // As big as the transport controls: same radius, so the accent border
        // lands near the edge rather than close in.
        val border = bitmap.getPixel(centre + offset, centre - buttonRadius + 2)
        assertTrue(
            "expected the button's border, got ${Integer.toHexString(border)}",
            isNear(border, 0xFF7AC8FF.toInt())
        )
    }

    @Test
    fun cornersStayTransparentSoTheWidgetKeepsItsRoundedEdges() {
        val data = renderDial(zone())
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(2, 2)))
    }

    @Test
    fun theDialTravelsAsCompressedDataNotAsAMegabyteOfPixels() {
        val data = renderDial(zone())
        // 512px square in ARGB_8888 is 1 MB raw, which is the entire Binder
        // buffer the process shares. Compressed it has to be a fraction of it.
        assertTrue("dial image is ${data.size} bytes", data.size < 200 * 1024)
    }

    @Test
    fun aColdWidgetShowsTheLastDialItDrew() {
        val data = renderDial(zone())
        WidgetDial.cache(context, data)
        assertTrue(WidgetDial.cached(context)!!.contentEquals(data))
    }

    @Test
    fun buildsViewsWithoutALiveDial() {
        // First placement, nothing rendered yet: must still inflate and be
        // clickable rather than throw.
        val views = RoonWidgetProvider.buildViews(context, renderDial(null, "Open Dial for Roon to connect"))
        val root = views.apply(context, FrameLayout(context))
        assertTrue(root.findViewById<View>(R.id.widget_play_pause).hasOnClickListeners())
        assertTrue(root.findViewById<View>(R.id.widget_volume_up).hasOnClickListeners())
        assertTrue(root.findViewById<View>(R.id.widget_previous).hasOnClickListeners())
    }

    @Test
    fun everyControlHasATapTarget() {
        val root = RoonWidgetProvider.buildViews(context, renderDial(zone()))
            .apply(context, FrameLayout(context))
        for (id in listOf(
            R.id.widget_previous, R.id.widget_play_pause, R.id.widget_next,
            R.id.widget_voice,
            R.id.widget_volume_down, R.id.widget_volume_up,
            R.id.widget_open, R.id.widget_open_centre
        )) {
            assertTrue("no click target on ${'$'}id", root.findViewById<View>(id).hasOnClickListeners())
        }
    }

    /** Loose: the dial reaches the widget as a lossy image, which shifts colours. */
    private fun isNear(actual: Int, expected: Int): Boolean =
        listOf(16, 8, 0).all {
            Math.abs(((actual shr it) and 0xFF) - ((expected shr it) and 0xFF)) <= 30
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
