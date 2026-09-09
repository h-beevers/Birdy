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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Intentional flock-style collage (simpler than desktop Pillow packer):
 * cream/pastel canvas, overlapping tiles sized by log(count), local
 * illustrations preferred over BirdWeather thumbs.
 *
 * Geometry lives in [CollageLayout] so it can be unit tested without a device.
 */
class CollageRenderer(
    private val context: Context,
    private val matcher: IllustrationMatcher = IllustrationMatcher.fromContext(context),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val pack: GbIllustrationPack = GbIllustrationPack(context, http),
) {

    /** A finished collage, plus what it was actually drawn from. */
    data class Rendered(
        val bitmap: Bitmap,
        val illustrated: Int,
        val photos: Int,
        val downloaded: Int,
    )

    fun render(
        species: List<SpeciesDetection>,
        settings: BirdySettings,
        width: Int = 1080,
        height: Int = 1920,
    ): Bitmap = renderDetailed(species, settings, width, height).bitmap

    fun renderDetailed(
        species: List<SpeciesDetection>,
        settings: BirdySettings,
        width: Int = 1080,
        height: Int = 1920,
    ): Rendered {
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
        val bottomMargin = height - height * 0.045f

        val birds = species.take(MAX_BIRDS)
        if (birds.isEmpty()) {
            val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                textAlign = Paint.Align.CENTER
                textSize = width * 0.04f
            }
            canvas.drawText("No detections nearby", width / 2f, height / 2f, empty)
            return Rendered(bitmap, illustrated = 0, photos = 0, downloaded = 0)
        }

        var downloaded = 0
        val loaded = birds.mapNotNull { s ->
            val art = loadArt(s, settings.downloadGbPack) ?: return@mapNotNull null
            if (art.fromPackDownload) downloaded++
            s to art
        }
        if (loaded.isEmpty()) {
            val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                textAlign = Paint.Align.CENTER
                textSize = width * 0.04f
            }
            canvas.drawText("No artwork available yet", width / 2f, height / 2f, empty)
            return Rendered(bitmap, illustrated = 0, photos = 0, downloaded = downloaded)
        }

        val sizes = CollageLayout.tileSizes(
            counts = loaded.map { it.first.count },
            width = width,
            drawableHeight = bottomMargin - topMargin,
        )
        val placements = CollageLayout.placeTiles(
            sizes = sizes,
            width = width,
            top = topMargin,
            bottom = bottomMargin,
        )

        // Draw the largest last so the loudest birds sit on top of the flock.
        for (p in placements.sortedBy { it.size }) {
            val (detection, art) = loaded[p.index]
            val size = p.size.roundToInt().coerceAtLeast(1)
            val style = CollageLayout.styleFor(art.isIllustration, art.hasAlpha)
            drawTile(canvas, art, style, p.cx, p.cy, size)
            if (settings.showLabels) {
                canvas.drawText(detection.name, p.cx, p.cy + size * 0.56f, labelPaint)
            }
        }
        loaded.forEach { (_, art) -> if (!art.bitmap.isRecycled) art.bitmap.recycle() }

        // Footer credit strip
        val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 46, 38, 32)
            textAlign = Paint.Align.CENTER
            textSize = width * 0.022f
        }
        canvas.drawText("Birdy · BirdWeather · local illustrations", width / 2f, height - 36f, foot)
        return Rendered(
            bitmap = bitmap,
            illustrated = loaded.count { it.second.isIllustration },
            photos = loaded.count { !it.second.isIllustration },
            downloaded = downloaded,
        )
    }

    /** Bundled illustration, downloaded GB plate, or a remote photo. */
    data class Art(
        val bitmap: Bitmap,
        val isIllustration: Boolean,
        val hasAlpha: Boolean,
        val fromPackDownload: Boolean = false,
    )

    private fun loadArt(s: SpeciesDetection, allowPackDownload: Boolean): Art? {
        // 1. Birdy's own bundled plate — hand-picked, so it wins.
        matcher.findLocal(s.name, s.scientific)?.let { assetPath ->
            decode { context.assets.open(assetPath).use { it.readBytes() } }?.let { bmp ->
                return Art(bmp, isIllustration = true, hasAlpha = bmp.hasAlpha())
            }
        }
        // 2. A GB pack plate already on this device.
        pack.cached(s.scientific)?.let { file ->
            decode { file.readBytes() }?.let { bmp ->
                return Art(bmp, isIllustration = true, hasAlpha = bmp.hasAlpha())
            }
        }
        // 3. Fetch one from the GB pack, if the user has that turned on.
        if (allowPackDownload) {
            pack.ensure(s.scientific)?.let { file ->
                decode { file.readBytes() }?.let { bmp ->
                    return Art(
                        bmp,
                        isIllustration = true,
                        hasAlpha = bmp.hasAlpha(),
                        fromPackDownload = true,
                    )
                }
            }
        }
        // 4. Last resort: BirdWeather's own photo thumbnail.
        if (s.thumb.isNotBlank()) {
            val bytes = try {
                val req = Request.Builder().url(s.thumb).get().build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            } catch (_: Exception) {
                null
            } ?: return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return Art(bmp, isIllustration = false, hasAlpha = false)
        }
        return null
    }

    private inline fun decode(read: () -> ByteArray): Bitmap? = try {
        val bytes = read()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    }

    /**
     * Draws one bird, in whichever way its art wants to be drawn.
     *
     * A cutout (transparent PNG, e.g. a GB pack plate) is drawn straight onto
     * the canvas: masking it into a disc would clip its own wingtips, and the
     * old paper-fill trick would have painted a fully transparent corner pixel
     * over the disc and erased the bird outright. An opaque plate keeps the
     * disc — letterboxed over its own paper colour so the padding is
     * invisible — and a photo is centre-cropped to fill one.
     */
    private fun drawTile(
        canvas: Canvas,
        art: Art,
        style: CollageLayout.TileStyle,
        cx: Float,
        cy: Float,
        size: Int,
    ) {
        val src = art.bitmap
        if (style == CollageLayout.TileStyle.CUTOUT) {
            val scale = CollageLayout.containScale(src.width, src.height, size)
            val w = max(1, (src.width * scale).roundToInt())
            val h = max(1, (src.height * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            // A soft ellipse under the bird instead of a hard disc: a cutout
            // has no plate behind it, so a full circle would read as a blob.
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 30, 20, 10) }
            canvas.drawOval(
                cx - w * 0.34f,
                cy + h * 0.30f,
                cx + w * 0.34f,
                cy + h * 0.46f,
                shadow,
            )
            canvas.drawBitmap(scaled, cx - w / 2f, cy - h / 2f, null)
            if (scaled != src) scaled.recycle()
            return
        }

        val disc = toSoftCircle(art, style, size)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 30, 20, 10) }
        canvas.drawCircle(cx + 6f, cy + 8f, disc.width * 0.42f, shadow)
        canvas.drawBitmap(disc, cx - disc.width / 2f, cy - disc.height / 2f, null)
        if (disc != src) disc.recycle()
    }

    /**
     * Masks [art] into a circular tile of [size] px without ever squashing it:
     * an opaque plate is letterboxed ("contain") over its own paper colour, a
     * photo is centre-cropped ("cover") to fill the disc.
     */
    private fun toSoftCircle(art: Art, style: CollageLayout.TileStyle, size: Int): Bitmap {
        val src = art.bitmap
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val save = canvas.saveLayer(0f, 0f, size.toFloat(), size.toFloat(), null)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val scale = if (style == CollageLayout.TileStyle.PLATE) {
            CollageLayout.containScale(src.width, src.height, size)
        } else {
            CollageLayout.coverScale(src.width, src.height, size)
        }
        val drawW = max(1, (src.width * scale).roundToInt())
        val drawH = max(1, (src.height * scale).roundToInt())
        if (style == CollageLayout.TileStyle.PLATE && (drawW < size || drawH < size)) {
            // Fill the disc with the plate's own paper colour so the
            // letterboxing reads as part of the illustration, not a gap. Only
            // safe because a PLATE is opaque; a transparent corner here would
            // punch the disc back out through SRC_IN.
            val corner = src.getPixel(0, 0)
            if (Color.alpha(corner) == 255) {
                val fill = Paint(paint).apply { color = corner }
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), fill)
            }
        }
        val scaled = Bitmap.createScaledBitmap(src, drawW, drawH, true)
        canvas.drawBitmap(scaled, (size - drawW) / 2f, (size - drawH) / 2f, paint)
        paint.xfermode = null
        canvas.restoreToCount(save)
        if (scaled != src) scaled.recycle()
        return out
    }

    companion object {
        /** Beyond this the tiles are too small to read as birds. */
        const val MAX_BIRDS = 60

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
