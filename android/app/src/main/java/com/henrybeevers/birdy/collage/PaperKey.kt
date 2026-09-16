package com.henrybeevers.birdy.collage

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns an opaque illustration plate into a cutout by removing the paper it
 * was scanned/exported on.
 *
 * The phone collage used to mask those plates into a circle filled with the
 * plate's own paper colour, which is what put the pale discs behind half the
 * birds on the wallpaper: paper that is nearly — but not exactly — the canvas
 * cream reads as a ring. Keying the paper out instead leaves the bird alone
 * on the canvas, the way the desktop packer's cutouts are.
 *
 * Pure ARGB array work, so it can be unit tested on the JVM.
 */
object PaperKey {

    /** Below this share of matching border pixels, it isn't a plain plate. */
    private const val BORDER_AGREEMENT = 0.82f

    /** Sanity band: a key that removes almost nothing (or almost all) is wrong. */
    private const val MIN_REMOVED = 0.04f
    private const val MAX_REMOVED = 0.96f

    /**
     * Flood-fills the paper colour inward from the edges and makes it
     * transparent, in place.
     *
     * Only the background connected to the border is removed, so a cream
     * highlight in the bird's eye stays put. Returns false (leaving [pixels]
     * untouched) when the border isn't one flat colour, or when the fill would
     * eat the whole image — a photographic or full-bleed plate, which the
     * caller should keep drawing the old way.
     */
    fun removePaper(pixels: IntArray, width: Int, height: Int, tolerance: Int = 30): Boolean {
        if (width <= 2 || height <= 2 || pixels.size < width * height) return false
        val paper = borderColour(pixels, width, height, tolerance) ?: return false

        val removed = BooleanArray(width * height)
        val queue = ArrayDeque<Int>()
        fun seed(i: Int) {
            if (!removed[i] && near(pixels[i], paper, tolerance)) {
                removed[i] = true
                queue.addLast(i)
            }
        }
        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }
        var count = queue.size
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % width
            val y = i / width
            if (x > 0) count += push(pixels, removed, queue, i - 1, paper, tolerance)
            if (x < width - 1) count += push(pixels, removed, queue, i + 1, paper, tolerance)
            if (y > 0) count += push(pixels, removed, queue, i - width, paper, tolerance)
            if (y < height - 1) count += push(pixels, removed, queue, i + width, paper, tolerance)
        }

        val share = count.toFloat() / (width * height)
        if (share < MIN_REMOVED || share > MAX_REMOVED) return false

        for (i in removed.indices) {
            if (removed[i]) pixels[i] = pixels[i] and 0x00FFFFFF
        }
        feather(pixels, removed, width, height, paper, tolerance)
        return true
    }

    private fun push(
        pixels: IntArray,
        removed: BooleanArray,
        queue: ArrayDeque<Int>,
        i: Int,
        paper: Int,
        tolerance: Int,
    ): Int {
        if (removed[i] || !near(pixels[i], paper, tolerance)) return 0
        removed[i] = true
        queue.addLast(i)
        return 1
    }

    /**
     * Softens the keyed edge: a kept pixel touching a removed one is faded in
     * proportion to how close it still is to the paper, so the cutout doesn't
     * show the hard stair-step the flood fill leaves behind.
     */
    private fun feather(
        pixels: IntArray,
        removed: BooleanArray,
        width: Int,
        height: Int,
        paper: Int,
        tolerance: Int,
    ) {
        val edge = ArrayList<Int>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (removed[i]) continue
                val touches = (x > 0 && removed[i - 1]) ||
                    (x < width - 1 && removed[i + 1]) ||
                    (y > 0 && removed[i - width]) ||
                    (y < height - 1 && removed[i + width])
                if (touches) edge.add(i)
            }
        }
        val band = max(1, tolerance * 2)
        for (i in edge) {
            val d = distance(pixels[i], paper)
            if (d >= band) continue
            val alpha = (255f * d / band).toInt().coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or (pixels[i] and 0x00FFFFFF)
        }
    }

    /**
     * The plate's paper colour, or null when the border isn't flat enough to
     * be paper (a full-bleed photo, a plate with a scene behind the bird).
     */
    private fun borderColour(pixels: IntArray, width: Int, height: Int, tolerance: Int): Int? {
        val corners = intArrayOf(
            pixels[0],
            pixels[width - 1],
            pixels[(height - 1) * width],
            pixels[height * width - 1],
        )
        if (corners.any { (it ushr 24) != 0xFF }) return null
        val candidate = corners[0]
        if (corners.any { distance(it, candidate) > tolerance }) return null

        var agree = 0
        var total = 0
        for (x in 0 until width) {
            for (i in intArrayOf(x, (height - 1) * width + x)) {
                total++
                if (near(pixels[i], candidate, tolerance)) agree++
            }
        }
        for (y in 0 until height) {
            for (i in intArrayOf(y * width, y * width + width - 1)) {
                total++
                if (near(pixels[i], candidate, tolerance)) agree++
            }
        }
        return if (agree.toFloat() / max(1, total) >= BORDER_AGREEMENT) candidate else null
    }

    private fun near(pixel: Int, paper: Int, tolerance: Int): Boolean =
        (pixel ushr 24) != 0 && distance(pixel, paper) <= tolerance

    /** Largest per-channel difference between two packed colours. */
    fun distance(a: Int, b: Int): Int = max(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        max(
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF)),
        ),
    )

    /** Opaque bounding box of [pixels], or null when nothing is opaque. */
    fun opaqueBounds(
        pixels: IntArray,
        width: Int,
        height: Int,
        alphaThreshold: Int = 12,
    ): IntArray? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((pixels[y * width + x] ushr 24) <= alphaThreshold) continue
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }
}
