package com.henrybeevers.birdy.collage

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The collage's geometry, with no Android types in sight so it can be unit
 * tested on the JVM (the drawing itself lives in [CollageRenderer]).
 *
 * Mirrors the desktop packer's shape: count-weighted tile sizing normalised
 * against an area budget, then packed centre-out on a spiral using real
 * silhouette collision — birds nest into each other's gaps the way they do
 * on the desktop wallpaper, rather than being scattered and then shoved
 * apart as bounding circles (which left them visibly piled on top of one
 * another on a phone-shaped canvas).
 */
object CollageLayout {

    /** Where one bird ends up, in pixels, on the collage canvas. */
    data class Placement(
        val index: Int,
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
    )

    /**
     * A tile's shape, downsampled from its alpha channel: the collision test
     * works on this rather than the bounding box, so a magpie's tail can
     * slide under a rook's belly instead of reserving a whole square.
     */
    class Silhouette(val cells: BooleanArray, val gw: Int, val gh: Int) {
        operator fun get(gx: Int, gy: Int): Boolean =
            gx in 0 until gw && gy in 0 until gh && cells[gy * gw + gx]

        /** Fraction of the grid the shape actually covers. */
        val coverage: Float get() = cells.count { it } / max(1, cells.size).toFloat()
    }

    /** One bird waiting to be packed: its natural box, and its shape. */
    data class Tile(
        val index: Int,
        val w: Float,
        val h: Float,
        val silhouette: Silhouette? = null,
    )

    /** How a given piece of art wants to be drawn into its tile. */
    enum class TileStyle {
        /** Transparent cutout: draw as-is, no disc mask, no disc shadow. */
        CUTOUT,

        /** Opaque illustration on its own paper: letterbox inside a disc. */
        PLATE,

        /** Remote photo: centre-crop to fill the disc. */
        PHOTO,
    }

    fun styleFor(isIllustration: Boolean, hasAlpha: Boolean): TileStyle = when {
        isIllustration && hasAlpha -> TileStyle.CUTOUT
        isIllustration -> TileStyle.PLATE
        else -> TileStyle.PHOTO
    }

    /**
     * Tile edge lengths in pixels, weighted by how often each species was
     * heard: `ln(count + 1)` so a frequently-heard bird visibly outsizes a
     * one-off without one very common species swallowing the canvas.
     *
     * Sizes are normalised against [budgetFraction] of the drawable area, so
     * the flock covers a similar share of the wallpaper whether there are
     * three birds or thirty. The budget runs ahead of the area the flock
     * actually ends up covering: the packer no longer lets birds overlap, so
     * roughly half of it is spent on the gaps between them.
     */
    fun tileSizes(
        counts: List<Int>,
        width: Int,
        drawableHeight: Float,
        budgetFraction: Double = 0.70,
        minFraction: Float = 0.10f,
        maxFraction: Float = 0.40f,
    ): List<Float> {
        if (counts.isEmpty()) return emptyList()
        val scores = counts.map { ln((max(0, it) + 1).toDouble()) + 0.5 }
        val total = scores.sum().coerceAtLeast(0.001)
        val budget = budgetFraction * width * max(1f, drawableHeight)
        // With many birds the per-tile minimum would blow the budget, so scale
        // the floor down once the flock is bigger than the canvas can hold.
        val crowding = sqrt((budget / counts.size).coerceAtLeast(1.0)).toFloat()
        val floor = min(width * minFraction, crowding * 0.72f)
        val ceiling = width * maxFraction
        val raw = scores.map { score -> sqrt((score / total) * budget).toFloat() }
        // A small flock can push every tile past the ceiling, and clamping each
        // one there would flatten a 120-detection bird and a 40-detection bird
        // into the same size. Scale the whole set instead, so the weighting
        // survives however few birds turned up.
        val overshoot = raw.max() / ceiling
        val fitted = if (overshoot > 1f) raw.map { it / overshoot } else raw
        return fitted.map { it.coerceIn(min(floor, ceiling), ceiling) }
    }

    /**
     * Builds a tile box from an edge length and the art's own aspect ratio,
     * so a long-tailed magpie is drawn long rather than squeezed into the
     * same square as a robin.
     */
    fun tileBox(size: Float, artW: Int, artH: Int): Pair<Float, Float> {
        val w = max(1, artW).toFloat()
        val h = max(1, artH).toFloat()
        val scale = size / max(w, h)
        return w * scale to h * scale
    }

    /**
     * Packs [tiles] centre-out on a spiral, largest first, rejecting any
     * position whose silhouette touches an already-placed bird. If anything
     * ends up off-canvas the whole flock is shrunk and repacked — the same
     * shrink-and-retry the desktop packer uses.
     *
     * The spiral is biased along the canvas's long axis, so a portrait phone
     * fills top-to-bottom instead of piling everything across the middle.
     */
    fun packFlock(
        tiles: List<Tile>,
        width: Int,
        top: Float,
        bottom: Float,
        maxIterations: Int = 12,
        shrinkFactor: Float = 0.93f,
    ): List<Placement> {
        if (tiles.isEmpty()) return emptyList()
        val centerX = width / 2f
        val centerY = (top + bottom) / 2f
        val spanX = max(1f, width.toFloat())
        val spanY = max(1f, bottom - top)
        val biasX = if (spanX >= spanY) (spanX / spanY).coerceAtMost(2.2f) else 1f
        val biasY = if (spanY > spanX) (spanY / spanX).coerceAtMost(2.2f) else 1f
        val maxRadius = hypot(spanX, spanY) * 0.75f
        val ordered = tiles.sortedByDescending { it.w * it.h }

        var scale = 1f
        var best: List<Placement> = emptyList()
        repeat(maxIterations) {
            val placed = ArrayList<Placed>(ordered.size)
            var overflowed = false
            for ((rank, tile) in ordered.withIndex()) {
                val w = tile.w * scale
                val h = tile.h * scale
                var spot = Placed(tile.index, centerX - w / 2f, centerY - h / 2f, w, h, tile.silhouette)
                if (rank > 0) {
                    var theta = 0.0
                    var radius = 0.0
                    var found = false
                    var hint = 0
                    while (radius < maxRadius) {
                        theta += 0.13
                        radius = 2.6 * theta
                        val cx = centerX + (radius * cos(theta)).toFloat() * biasX
                        val cy = centerY + (radius * sin(theta)).toFloat() * biasY
                        val candidate = Placed(tile.index, cx - w / 2f, cy - h / 2f, w, h, tile.silhouette)
                        // Whatever blocked the last spiral step usually blocks
                        // this one too — retest it first so the scan below can
                        // bail on its first comparison.
                        if (hint < placed.size && collide(candidate, placed[hint])) {
                            spot = candidate
                            continue
                        }
                        var blocked = false
                        for (i in placed.indices) {
                            if (i == hint) continue
                            if (collide(candidate, placed[i])) {
                                hint = i
                                blocked = true
                                break
                            }
                        }
                        spot = candidate
                        if (!blocked) {
                            found = true
                            break
                        }
                    }
                    if (!found) overflowed = true
                }
                if (spot.x < 0f || spot.y < top ||
                    spot.x + spot.w > width || spot.y + spot.h > bottom
                ) {
                    overflowed = true
                }
                placed.add(spot)
            }
            best = placed.map { Placement(it.index, it.x + it.w / 2f, it.y + it.h / 2f, it.w, it.h) }
                .sortedBy { it.index }
            if (!overflowed) return best
            scale *= shrinkFactor
        }
        // Best effort: nudge whatever still hangs off the edge back inside.
        return best.map {
            it.copy(
                cx = clampAxis(it.cx, it.w, 0f, width.toFloat()),
                cy = clampAxis(it.cy, it.h, top, bottom),
            )
        }
    }

    private class Placed(
        val index: Int,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val silhouette: Silhouette?,
    )

    /** Silhouette overlap between two placed tiles, bounding box first. */
    private fun collide(a: Placed, b: Placed, samples: Int = 15): Boolean {
        val x0 = max(a.x, b.x)
        val x1 = min(a.x + a.w, b.x + b.w)
        val y0 = max(a.y, b.y)
        val y1 = min(a.y + a.h, b.y + b.h)
        if (x1 <= x0 || y1 <= y0) return false
        val sa = a.silhouette ?: return true
        val sb = b.silhouette ?: return true
        for (iy in 0 until samples) {
            val py = y0 + (y1 - y0) * (iy + 0.5f) / samples
            for (ix in 0 until samples) {
                val px = x0 + (x1 - x0) * (ix + 0.5f) / samples
                if (!lookup(sa, a, px, py)) continue
                if (lookup(sb, b, px, py)) return true
            }
        }
        return false
    }

    private fun lookup(s: Silhouette, t: Placed, px: Float, py: Float): Boolean {
        val gx = ((px - t.x) / t.w * s.gw).toInt()
        val gy = ((py - t.y) / t.h * s.gh).toInt()
        return s[gx, gy]
    }

    /**
     * Downsamples an ARGB image's alpha channel into a collision grid, then
     * pads it outward by one cell so packed birds keep a sliver of daylight
     * between them instead of touching at the pixel edge.
     */
    fun silhouetteFrom(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int = 26,
        alphaThreshold: Int = 96,
    ): Silhouette {
        val w = max(1, srcW)
        val h = max(1, srcH)
        val gw = max(1, min(gridW, w))
        val gh = max(1, (gw * h.toFloat() / w).roundToInt())
        val raw = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            val y0 = gy * h / gh
            val y1 = max(y0 + 1, (gy + 1) * h / gh)
            for (gx in 0 until gw) {
                val x0 = gx * w / gw
                val x1 = max(x0 + 1, (gx + 1) * w / gw)
                var hit = false
                var y = y0
                loop@ while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        if ((pixels[y * w + x] ushr 24) > alphaThreshold) {
                            hit = true
                            break@loop
                        }
                        x++
                    }
                    y++
                }
                raw[gy * gw + gx] = hit
            }
        }
        return Silhouette(dilate(raw, gw, gh), gw, gh)
    }

    /** A filled disc grid — what a circular photo thumbnail collides with. */
    fun discSilhouette(gridW: Int = 26): Silhouette {
        val cells = BooleanArray(gridW * gridW)
        val r = gridW / 2f
        for (y in 0 until gridW) {
            for (x in 0 until gridW) {
                val dx = x + 0.5f - r
                val dy = y + 0.5f - r
                cells[y * gridW + x] = dx * dx + dy * dy <= r * r
            }
        }
        return Silhouette(cells, gridW, gridW)
    }

    private fun dilate(cells: BooleanArray, gw: Int, gh: Int): BooleanArray {
        val out = BooleanArray(cells.size)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                if (!cells[y * gw + x]) continue
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = y + dy
                        val nx = x + dx
                        if (ny in 0 until gh && nx in 0 until gw) out[ny * gw + nx] = true
                    }
                }
            }
        }
        return out
    }

    /**
     * Keeps a tile's centre inside [lo]..[hi] with room for its own size; if
     * the tile is bigger than the axis it is centred instead of clamped, which
     * would otherwise slam it against one edge.
     */
    private fun clampAxis(value: Float, size: Float, lo: Float, hi: Float): Float {
        val half = size / 2f
        if (hi - lo <= size) return (lo + hi) / 2f
        return value.coerceIn(lo + half, hi - half)
    }

    /** Scale that fits the whole source inside a [size] px square. */
    fun containScale(srcW: Int, srcH: Int, size: Int): Float =
        min(size.toFloat() / max(1, srcW), size.toFloat() / max(1, srcH))

    /** Scale that fills a [size] px square, cropping the overhang. */
    fun coverScale(srcW: Int, srcH: Int, size: Int): Float =
        max(size.toFloat() / max(1, srcW), size.toFloat() / max(1, srcH))

    /** True when every tile sits fully inside the canvas. */
    fun allInside(
        placements: List<Placement>,
        width: Int,
        top: Float,
        bottom: Float,
        tolerance: Float = 0.5f,
    ): Boolean = placements.all {
        val fitsX = it.w > width ||
            (it.cx - it.w / 2f >= -tolerance && it.cx + it.w / 2f <= width + tolerance)
        val fitsY = it.h > (bottom - top) ||
            (it.cy - it.h / 2f >= top - tolerance && it.cy + it.h / 2f <= bottom + tolerance)
        fitsX && fitsY
    }

    /** Worst bounding-box overlap between any two tiles, 0..1 of the smaller. */
    fun worstOverlapFraction(placements: List<Placement>): Float {
        var worst = 0f
        for (a in placements.indices) {
            for (b in a + 1 until placements.size) {
                val p = placements[a]
                val q = placements[b]
                val ox = min(p.cx + p.w / 2f, q.cx + q.w / 2f) - max(p.cx - p.w / 2f, q.cx - q.w / 2f)
                val oy = min(p.cy + p.h / 2f, q.cy + q.h / 2f) - max(p.cy - p.h / 2f, q.cy - q.h / 2f)
                if (ox <= 0f || oy <= 0f) continue
                val smaller = min(p.w * p.h, q.w * q.h)
                if (smaller <= 0f) continue
                worst = max(worst, (ox * oy) / smaller)
            }
        }
        return worst
    }

    /** True when two tiles land on top of each other (used by tests/guards). */
    fun isStacked(a: Placement, b: Placement): Boolean =
        abs(a.cx - b.cx) < 1f && abs(a.cy - b.cy) < 1f
}
