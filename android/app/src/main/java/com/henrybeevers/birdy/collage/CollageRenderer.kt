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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Flock-style collage, matching the desktop Pillow packer's look: cream
 * canvas, birds as cutouts packed centre-out until they nest against each
 * other, local illustrations preferred over BirdWeather thumbs.
 *
 * Geometry lives in [CollageLayout] so it can be unit tested without a device,
 * and the paper behind an opaque plate is keyed out by [PaperKey] rather than
 * masked into a disc — the discs were what put pale circles behind half the
 * birds on the phone wallpaper.
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
            drawNotice(canvas, "No detections nearby", width, height, ink)
            return Rendered(bitmap, illustrated = 0, photos = 0, downloaded = 0)
        }

        var downloaded = 0
        val loaded = birds.mapNotNull { s ->
            val art = loadArt(s, settings.downloadGbPack) ?: return@mapNotNull null
            if (art.fromPackDownload) downloaded++
            s to art
        }
        if (loaded.isEmpty()) {
            drawNotice(canvas, "No artwork available yet", width, height, ink)
            return Rendered(bitmap, illustrated = 0, photos = 0, downloaded = downloaded)
        }

        val sizes = CollageLayout.tileSizes(
            counts = loaded.map { it.first.count },
            width = width,
            drawableHeight = bottomMargin - topMargin,
        )
        val tiles = loaded.mapIndexed { i, (_, art) ->
            val style = CollageLayout.styleFor(art.isIllustration, art.hasAlpha)
            if (style == CollageLayout.TileStyle.CUTOUT) {
                val (w, h) = CollageLayout.tileBox(sizes[i], art.bitmap.width, art.bitmap.height)
                CollageLayout.Tile(i, w, h, silhouetteOf(art.bitmap))
            } else {
                // A disc tile is square and collides as a circle.
                CollageLayout.Tile(i, sizes[i], sizes[i], CollageLayout.discSilhouette())
            }
        }
        val placements = CollageLayout.packFlock(
            tiles = tiles,
            width = width,
            top = topMargin,
            bottom = bottomMargin,
        )

        // Draw the largest last so the loudest birds sit on top where the
        // packer had to let two silhouettes touch.
        for (p in placements.sortedBy { it.w * it.h }) {
            val (detection, art) = loaded[p.index]
            val style = CollageLayout.styleFor(art.isIllustration, art.hasAlpha)
            drawTile(canvas, art, style, p)
            if (settings.showLabels) {
                canvas.drawText(detection.name, p.cx, p.cy + p.h * 0.58f, labelPaint)
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

    private fun drawNotice(canvas: Canvas, text: String, width: Int, height: Int, ink: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            textSize = width * 0.04f
        }
        canvas.drawText(text, width / 2f, height / 2f, paint)
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
                return illustration(bmp)
            }
        }
        // 2. A GB pack plate already on this device.
        pack.cached(s.scientific)?.let { file ->
            decode { file.readBytes() }?.let { bmp ->
                return illustration(bmp)
            }
        }
        // 3. Fetch one from the GB pack, if the user has that turned on.
        if (allowPackDownload) {
            pack.ensure(s.scientific)?.let { file ->
                decode { file.readBytes() }?.let { bmp ->
                    return illustration(bmp, fromPackDownload = true)
                }
            }
        }
        // 4. Last resort: BirdWeather's own photo thumbnail.
        // Same http(s)-only gate as desktop is_safe_download_url().
        if (isHttpUrl(s.thumb)) {
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

    /**
     * Prepares an illustration for the flock: an opaque plate has its paper
     * keyed out so it becomes a cutout like every other bird, and any cutout
     * is trimmed to its own ink so the packer reserves the bird's space, not
     * the exporter's margins.
     */
    private fun illustration(source: Bitmap, fromPackDownload: Boolean = false): Art {
        val prepared = cutoutOf(source)
        return Art(
            bitmap = prepared,
            isIllustration = true,
            hasAlpha = prepared.hasAlpha(),
            fromPackDownload = fromPackDownload,
        )
    }

    /**
     * Returns [source] as a transparent cutout when it can: keys out flat
     * paper, then trims the transparent margin. Falls back to the original
     * bitmap (drawn as a disc plate) when the art has no flat background to
     * key — a full-bleed painting, say.
     */
    private fun cutoutOf(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 2 || h <= 2 || w.toLong() * h > MAX_KEY_PIXELS) return source
        val pixels = try {
            IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
        } catch (_: Exception) {
            return source
        }
        val alreadyCutout = source.hasAlpha() && hasTransparency(pixels)
        val keyed = alreadyCutout || PaperKey.removePaper(pixels, w, h)
        if (!keyed) return source

        val bounds = PaperKey.opaqueBounds(pixels, w, h) ?: return source
        val bx = bounds[0]
        val by = bounds[1]
        val bw = bounds[2]
        val bh = bounds[3]
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val row = IntArray(bw)
        for (y in 0 until bh) {
            System.arraycopy(pixels, (by + y) * w + bx, row, 0, bw)
            out.setPixels(row, 0, bw, 0, y, bw, 1)
        }
        if (!source.isRecycled) source.recycle()
        return out
    }

    private fun hasTransparency(pixels: IntArray): Boolean =
        pixels.any { (it ushr 24) < 250 }

    private fun silhouetteOf(bmp: Bitmap): CollageLayout.Silhouette? {
        val w = bmp.width
        val h = bmp.height
        if (w < 1 || h < 1) return null
        // Sample at grid resolution rather than pulling a full-size frame in.
        val gw = min(SILHOUETTE_GRID, w)
        val gh = max(1, (gw * h.toFloat() / w).roundToInt())
        val small = try {
            Bitmap.createScaledBitmap(bmp, gw, gh, true)
        } catch (_: Exception) {
            return null
        }
        val pixels = IntArray(gw * gh)
        small.getPixels(pixels, 0, gw, 0, 0, gw, gh)
        if (small != bmp) small.recycle()
        return CollageLayout.silhouetteFrom(pixels, gw, gh, gridW = gw)
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
     * A cutout — which is now every illustration whose paper could be keyed
     * out — is drawn straight onto the canvas at its own aspect ratio, with a
     * soft ellipse under it for grounding. Only art with no flat background to
     * key (and a remote photo) still gets a disc.
     */
    private fun drawTile(
        canvas: Canvas,
        art: Art,
        style: CollageLayout.TileStyle,
        p: CollageLayout.Placement,
    ) {
        val src = art.bitmap
        if (style == CollageLayout.TileStyle.CUTOUT) {
            val w = max(1, p.w.roundToInt())
            val h = max(1, p.h.roundToInt())
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            // A soft ellipse under the bird instead of a hard disc: a cutout
            // has no plate behind it, so a full circle would read as a blob.
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 30, 20, 10) }
            canvas.drawOval(
                p.cx - w * 0.34f,
                p.cy + h * 0.30f,
                p.cx + w * 0.34f,
                p.cy + h * 0.46f,
                shadow,
            )
            canvas.drawBitmap(scaled, p.cx - w / 2f, p.cy - h / 2f, null)
            if (scaled != src) scaled.recycle()
            return
        }

        val size = max(1, min(p.w, p.h).roundToInt())
        val disc = toSoftCircle(art, style, size)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 30, 20, 10) }
        canvas.drawCircle(p.cx + 6f, p.cy + 8f, disc.width * 0.42f, shadow)
        canvas.drawBitmap(disc, p.cx - disc.width / 2f, p.cy - disc.height / 2f, null)
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

        /** Collision grid width — matches [CollageLayout.silhouetteFrom]. */
        private const val SILHOUETTE_GRID = 26

        /** Plates bigger than this aren't worth a full-frame key on a phone. */
        private const val MAX_KEY_PIXELS = 4_500_000L

        /** Same http(s)-only gate as desktop `is_safe_download_url()`. */
        fun isHttpUrl(url: String): Boolean {
            val t = url.trim().lowercase(LocaleAware)
            return t.startsWith("http://") || t.startsWith("https://")
        }

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
