package com.henrybeevers.birdy.collage

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The collage's geometry, with no Android types in sight so it can be unit
 * tested on the JVM (the drawing itself lives in [CollageRenderer]).
 *
 * Mirrors the desktop packer's shape: count-weighted tile sizing normalised
 * against an area budget, packed centre-out, then relaxed apart so tiles
 * overlap like a flock rather than stacking into a pile.
 */
object CollageLayout {

    /** Where one bird ends up, in pixels, on the collage canvas. */
    data class Placement(val index: Int, val cx: Float, val cy: Float, val size: Float)

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
     * three birds or thirty.
     */
    fun tileSizes(
        counts: List<Int>,
        width: Int,
        drawableHeight: Float,
        budgetFraction: Double = 0.62,
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
     * Packs [sizes] centre-out on a golden-angle (phyllotaxis) spiral, then
     * relaxes overlapping tiles apart and pulls everything back inside the
     * canvas.
     *
     * Phyllotaxis rather than a fixed-step spiral because the radius grows as
     * `sqrt(i)`: tile 40 lands just outside tile 39 instead of a canvas-width
     * away, so a big flock still fills the middle of the wallpaper.
     *
     * Tiles are placed largest-first so the loudest birds hold the centre.
     */
    fun placeTiles(
        sizes: List<Float>,
        width: Int,
        top: Float,
        bottom: Float,
        passes: Int = 24,
        seed: Long = 7L,
    ): List<Placement> {
        if (sizes.isEmpty()) return emptyList()
        val order = sizes.indices.sortedByDescending { sizes[it] }
        val centerX = width / 2f
        val centerY = (top + bottom) / 2f
        val spanX = max(1f, width.toFloat())
        val spanY = max(1f, bottom - top)
        // Radius that would fit every tile's area inside the canvas, spread out
        // by the usual phyllotaxis packing constant.
        val totalArea = sizes.sumOf { (it * it).toDouble() }
        val spread = sqrt(totalArea / PI).toFloat() * 0.92f
        val maxRadius = max(spread, sizes.max() * 0.6f)
        val golden = (PI * (3.0 - sqrt(5.0))).toFloat() // ~2.39996 rad

        val xs = FloatArray(sizes.size)
        val ys = FloatArray(sizes.size)
        val aspect = spanY / spanX
        order.forEachIndexed { rank, idx ->
            val t = if (sizes.size == 1) 0f else sqrt(rank / (sizes.size - 1f))
            val radius = maxRadius * t
            val angle = golden * rank + (seed % 360) * 0.01f
            xs[idx] = centerX + cos(angle) * radius
            ys[idx] = centerY + sin(angle) * radius * aspect.coerceIn(0.6f, 1.8f)
        }

        // Relax: push overlapping pairs apart, but only enough to leave the
        // flock touching — a little overlap is the look, a pile is not.
        val minGap = 0.50f // fraction of the two tiles' combined half-sizes
        repeat(passes) {
            for (a in sizes.indices) {
                for (b in a + 1 until sizes.size) {
                    val dx = xs[b] - xs[a]
                    val dy = ys[b] - ys[a]
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                    val want = (sizes[a] + sizes[b]) / 2f * minGap
                    if (dist >= want) continue
                    val push = (want - dist) / 2f
                    val ux = dx / dist
                    val uy = dy / dist
                    xs[a] -= ux * push
                    ys[a] -= uy * push
                    xs[b] += ux * push
                    ys[b] += uy * push
                }
            }
            for (i in sizes.indices) {
                xs[i] = clampAxis(xs[i], sizes[i], 0f, width.toFloat())
                ys[i] = clampAxis(ys[i], sizes[i], top, bottom)
            }
        }

        return sizes.indices.map { Placement(it, xs[it], ys[it], sizes[it]) }
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
        val half = it.size / 2f
        val fitsX = it.size > width || (it.cx - half >= -tolerance && it.cx + half <= width + tolerance)
        val fitsY = it.size > (bottom - top) ||
            (it.cy - half >= top - tolerance && it.cy + half <= bottom + tolerance)
        fitsX && fitsY
    }

    /** Worst overlap between any two tiles, as a fraction of their mean size. */
    fun worstOverlapFraction(placements: List<Placement>): Float {
        var worst = 0f
        for (a in placements.indices) {
            for (b in a + 1 until placements.size) {
                val p = placements[a]
                val q = placements[b]
                val dist = sqrt(
                    (p.cx - q.cx) * (p.cx - q.cx) + (p.cy - q.cy) * (p.cy - q.cy),
                )
                val mean = (p.size + q.size) / 2f
                if (mean <= 0f) continue
                worst = max(worst, ((mean - dist) / mean).coerceAtLeast(0f))
            }
        }
        return worst
    }

    /** True when two tiles land on top of each other (used by tests/guards). */
    fun isStacked(a: Placement, b: Placement): Boolean =
        abs(a.cx - b.cx) < 1f && abs(a.cy - b.cy) < 1f
}
