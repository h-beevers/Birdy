package com.henrybeevers.birdy.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import com.henrybeevers.birdy.data.BirdySettings
import com.henrybeevers.birdy.data.SpeciesDetection
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Intentional flock-style collage (simpler than desktop Pillow packer):
 * cream/pastel canvas, overlapping circular/soft tiles sized by log(count),
 * local illustrations preferred over BirdWeather thumbs.
 */
class CollageRenderer(
    private val context: Context,
    private val matcher: IllustrationMatcher = IllustrationMatcher.fromContext(context),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    fun render(
        species: List<SpeciesDetection>,
        settings: BirdySettings,
        width: Int = 1080,
        height: Int = 1920,
    ): Bitmap {
        val bg = parseBgColor(settings.bgColor)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bg)

        val ink = Color.rgb(46, 38, 32)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            textSize = width * 0.055f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            textSize = width * 0.028f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }

        var topMargin = height * 0.04f
        if (settings.showTitle) {
            canvas.drawText(settings.titleText, width / 2f, height * 0.07f, titlePaint)
            topMargin = height * 0.11f
        }

        val birds = species.take(60)
        if (birds.isEmpty()) {
            val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                textAlign = Paint.Align.CENTER
                textSize = width * 0.04f
            }
            canvas.drawText("No detections nearby", width / 2f, height / 2f, empty)
            return bitmap
        }

        data class Tile(
            val species: SpeciesDetection,
            val bmp: Bitmap,
            val size: Float,
            var cx: Float,
            var cy: Float,
        )

        val scores = birds.map { ln((it.count + 1).toDouble()) + 0.5 }
        val totalScore = scores.sum().coerceAtLeast(0.001)
        val budgetFraction = 0.42
        val budgetArea = budgetFraction * width * height

        val tiles = mutableListOf<Tile>()
        birds.forEachIndexed { i, s ->
            val bmp = loadArt(s) ?: return@forEachIndexed
            val area = (scores[i] / totalScore) * budgetArea
            val size = sqrt(area).toFloat().coerceIn(width * 0.12f, width * 0.42f)
            val rnd = Random(s.name.hashCode())
            val cx = width * (0.18f + rnd.nextFloat() * 0.64f)
            val cy = topMargin + (height - topMargin) * (0.15f + rnd.nextFloat() * 0.7f)
            tiles.add(Tile(s, bmp, size, cx, cy))
        }

        // Simple spiral pack from center to reduce heavy overlap
        val centerX = width / 2f
        val centerY = topMargin + (height - topMargin) * 0.5f
        tiles.sortByDescending { it.size }
        tiles.forEachIndexed { idx, t ->
            val angle = idx * 2.4
            val radius = 40.0 + idx * (min(width, height) * 0.045)
            t.cx = (centerX + cos(angle) * radius).toFloat().coerceIn(t.size / 2, width - t.size / 2)
            t.cy = (centerY + sin(angle) * radius * 0.85).toFloat()
                .coerceIn(topMargin + t.size / 2, height - t.size / 2)
        }

        // Draw larger last so they sit on top
        for (t in tiles.sortedBy { it.size }) {
            val circle = toSoftCircle(t.bmp, t.size.roundToInt())
            val left = t.cx - circle.width / 2f
            val top = t.cy - circle.height / 2f
            // soft shadow
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(50, 30, 20, 10)
            }
            canvas.drawCircle(t.cx + 6f, t.cy + 8f, circle.width * 0.42f, shadow)
            canvas.drawBitmap(circle, left, top, null)
            if (settings.showLabels) {
                canvas.drawText(t.species.name, t.cx, t.cy + circle.height * 0.55f, labelPaint)
            }
            if (circle != t.bmp) circle.recycle()
        }

        // Footer credit strip
        val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 46, 38, 32)
            textAlign = Paint.Align.CENTER
            textSize = width * 0.022f
        }
        canvas.drawText("Birdy · BirdWeather · local illustrations", width / 2f, height - 36f, foot)
        return bitmap
    }

    private fun loadArt(s: SpeciesDetection): Bitmap? {
        val local = matcher.findLocal(s.name, s.scientific)
        if (local != null) {
            try {
                context.assets.open(local).use { stream ->
                    return BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) {
                // fall through to thumb
            }
        }
        if (s.thumb.isNotBlank()) {
            try {
                val req = Request.Builder().url(s.thumb).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val bytes = resp.body?.bytes() ?: return null
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun toSoftCircle(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val save = canvas.saveLayer(0f, 0f, size.toFloat(), size.toFloat(), null)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        paint.xfermode = null
        canvas.restoreToCount(save)
        if (scaled != src) scaled.recycle()
        return out
    }

    companion object {
        fun parseBgColor(raw: String, fallback: Int = Color.rgb(244, 237, 224)): Int {
            val s = raw.trim().lowercase(LocaleAware)
            when (s.replace(" ", "_").replace("-", "_")) {
                "cream", "default" -> return Color.rgb(244, 237, 224)
                "pastel_blue", "pastelblue", "blue" -> return Color.rgb(197, 216, 232)
                "pastel_green", "pastelgreen", "green" -> return Color.rgb(214, 228, 210)
            }
            if (s.startsWith("#")) {
                val h = s.removePrefix("#")
                try {
                    if (h.length == 3) {
                        val r = h[0].toString().repeat(2).toInt(16)
                        val g = h[1].toString().repeat(2).toInt(16)
                        val b = h[2].toString().repeat(2).toInt(16)
                        return Color.rgb(r, g, b)
                    }
                    if (h.length == 6) {
                        return Color.parseColor("#$h")
                    }
                } catch (_: Exception) {
                    return fallback
                }
            }
            if ("," in s) {
                val parts = s.split(",").map { it.trim() }
                if (parts.size == 3) {
                    try {
                        return Color.rgb(
                            parts[0].toFloat().toInt().coerceIn(0, 255),
                            parts[1].toFloat().toInt().coerceIn(0, 255),
                            parts[2].toFloat().toInt().coerceIn(0, 255),
                        )
                    } catch (_: Exception) {
                        return fallback
                    }
                }
            }
            return fallback
        }

        private val LocaleAware = java.util.Locale.US
    }
}
