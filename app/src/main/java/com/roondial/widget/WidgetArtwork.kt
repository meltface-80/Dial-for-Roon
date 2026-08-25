package com.roondial.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * The widget's artwork: a circular cover with the volume level as a ring
 * around it, echoing the dial without pretending to be one — a widget only
 * receives clicks, so the ring here reads a level rather than setting it.
 *
 * Every bitmap handed to RemoteViews crosses the 1 MB Binder buffer shared by
 * the whole process, so this renders small on purpose. 192px square in
 * ARGB_8888 is 144 KB, with plenty of headroom.
 */
object WidgetArtwork {

    private const val TAG = "WidgetArtwork"
    const val SIZE = 192

    private const val RING_TRACK = 0xFF232B34.toInt()
    private const val RING_FILL = 0xFF7AC8FF.toInt()
    private const val PLACEHOLDER = 0xFF141A21.toInt()

    private const val CACHE_FILE = "widget-art.jpg"

    private var cachedKey: String? = null
    private var cachedCover: Bitmap? = null

    /**
     * Draws the cover, or a placeholder, with [volumeFraction] of a ring around
     * it. A negative fraction means the zone has no volume control to show.
     */
    fun render(cover: Bitmap?, volumeFraction: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val ringWidth = SIZE * 0.09f
        val centre = SIZE / 2f
        val ringRadius = centre - ringWidth / 2f
        val coverRadius = centre - ringWidth - SIZE * 0.02f

        if (cover != null && !cover.isRecycled) {
            val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(
                2f * coverRadius / cover.width,
                2f * coverRadius / cover.height
            )
            shader.setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(
                        centre - cover.width * scale / 2f,
                        centre - cover.height * scale / 2f
                    )
                }
            )
            paint.shader = shader
            canvas.drawCircle(centre, centre, coverRadius, paint)
            paint.shader = null
        } else {
            paint.color = PLACEHOLDER
            canvas.drawCircle(centre, centre, coverRadius, paint)
        }

        if (volumeFraction >= 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ringWidth
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = RING_TRACK
            canvas.drawCircle(centre, centre, ringRadius, paint)

            paint.color = RING_FILL
            val rect = RectF(
                centre - ringRadius, centre - ringRadius,
                centre + ringRadius, centre + ringRadius
            )
            canvas.drawArc(rect, -90f, 360f * volumeFraction.coerceIn(0f, 1f), false, paint)
        }

        return bitmap
    }

    /**
     * Cover art for [imageKey], from memory, then disk, then the Core. Returns
     * null rather than blocking on a fetch; the caller re-renders when
     * [fetch] has something.
     */
    @Synchronized
    fun cached(context: Context, imageKey: String): Bitmap? {
        if (imageKey.isEmpty()) return null
        if (imageKey == cachedKey) return cachedCover
        val file = File(context.cacheDir, CACHE_FILE)
        if (file.exists() && context.readCachedKey() == imageKey) {
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            if (decoded != null) {
                cachedKey = imageKey
                cachedCover = decoded
                return decoded
            }
        }
        return null
    }

    /** Blocking fetch; call from a background thread. */
    fun fetch(context: Context, url: String, imageKey: String): Bitmap? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
            }
            val decoded = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            val scaled = downscale(decoded)
            synchronized(this) {
                cachedKey = imageKey
                cachedCover = scaled
            }
            File(context.cacheDir, CACHE_FILE).outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            context.writeCachedKey(imageKey)
            scaled
        } catch (e: Exception) {
            Log.w(TAG, "artwork fetch failed: ${e.message}")
            null
        }
    }

    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= SIZE) return source
        val scale = SIZE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        ).also { if (it !== source) source.recycle() }
    }

    private fun Context.readCachedKey(): String? =
        getSharedPreferences("roon_widget", Context.MODE_PRIVATE).getString("cached_art_key", null)

    private fun Context.writeCachedKey(key: String) {
        getSharedPreferences("roon_widget", Context.MODE_PRIVATE).edit()
            .putString("cached_art_key", key).apply()
    }

    @Synchronized
    fun clearMemoryCache() {
        cachedKey = null
        cachedCover = null
    }
}
