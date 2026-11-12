package com.henrybeevers.birdy.collage

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Blends a classic plate's scrap of scan paper into Birdy's canvas.
 *
 * The classic plates ([ClassicPlatePack]) are cut out with a margin of the
 * original page left round the bird, and every scan's paper is a slightly
 * different tone: drawn as-is, each bird sits on a pale sticker. Following the
 * approach Fugleramme's own renderer takes (`render/paper.py`, MIT), the paper
 * tone is sampled from the opaque ring just inside the cut, every paper-like
 * pixel is shifted by the difference between that tone and the canvas colour,
 * and the cut edge is feathered so the patch melts into the page. The shift
 * only touches pale, unsaturated pixels close to the sampled tone, so plumage
 * is left alone.
 *
 * Pure ARGB array work (the same mirror exists on the desktop as
 * `level_plate_paper`), so it is unit tested on the JVM.
 */
object PaperTone {

    /** Alpha at or below this is scan noise, not picture. */
    private const val ALPHA_FLOOR = 24

    /** Too small a ring to trust its median. */
    private const val MIN_RING = 50

    /**
     * Levels [pixels] (non-premultiplied ARGB, [width] x [height]) towards the
     * canvas colour [target], in place. Returns false, leaving the colours
     * untouched, when the plate has no paper margin to read a tone from.
     */
    fun level(pixels: IntArray, width: Int, height: Int, target: Int): Boolean {
        val n = width * height
        if (width < 3 || height < 3 || pixels.size < n) return false
        val reach = max(2, max(width, height) / 140)

        val opaque = IntArray(n) { if ((pixels[it] ushr 24) > ALPHA_FLOOR) 1 else 0 }
        val clear = IntArray(n) { 1 - opaque[it] }
        val nearCut = boxSum(clear, width, height, reach, replicate = false)

        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var ring = 0
        for (i in 0 until n) {
            if (opaque[i] == 0 || nearCut[i] == 0) continue
            val p = pixels[i]
            histR[(p shr 16) and 0xFF]++
            histG[(p shr 8) and 0xFF]++
            histB[p and 0xFF]++
            ring++
        }
        if (ring < MIN_RING) return false
        val pr = median(histR, ring)
        val pg = median(histG, ring)
        val pb = median(histB, ring)
        val dr = ((target shr 16) and 0xFF) - pr
        val dg = ((target shr 8) and 0xFF) - pg
        val db = (target and 0xFF) - pb

        // Feathered alpha: a blurred copy of the hard mask, never allowed to
        // make a pixel more opaque than the plate had it.
        val mask = IntArray(n) { opaque[it] * 255 }
        val side = 2 * reach + 1
        val once = boxSum(mask, width, height, reach, replicate = true)
        for (i in 0 until n) once[i] = once[i] / (side * side)
        val soft = boxSum(once, width, height, reach, replicate = true)

        for (i in 0 until n) {
            val p = pixels[i]
            val a = p ushr 24
            if (a <= ALPHA_FLOOR) {
                pixels[i] = 0
                continue
            }
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val dist = max(abs(r - pr), max(abs(g - pg), abs(b - pb)))
            if (dist < 50 && hi - lo < 55 && hi > 160) {
                r = (r + dr).coerceIn(0, 255)
                g = (g + dg).coerceIn(0, 255)
                b = (b + db).coerceIn(0, 255)
            }
            val feathered = min(a, soft[i] / (side * side))
            pixels[i] = (feathered shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun median(hist: IntArray, total: Int): Int {
        var seen = 0
        for (v in 0..255) {
            seen += hist[v]
            if (seen * 2 >= total) return v
        }
        return 255
    }

    /**
     * Sum of [src] over the (2r+1)-square window round each pixel. Outside the
     * image counts as zero, or as the nearest edge pixel when [replicate].
     */
    internal fun boxSum(src: IntArray, w: Int, h: Int, r: Int, replicate: Boolean): IntArray {
        val rows = IntArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                var sum = 0
                for (dx in -r..r) {
                    val xx = x + dx
                    sum += when {
                        xx in 0 until w -> src[base + xx]
                        replicate -> src[base + xx.coerceIn(0, w - 1)]
                        else -> 0
                    }
                }
                rows[base + x] = sum
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in -r..r) {
                    val yy = y + dy
                    sum += when {
                        yy in 0 until h -> rows[yy * w + x]
                        replicate -> rows[yy.coerceIn(0, h - 1) * w + x]
                        else -> 0
                    }
                }
                out[y * w + x] = sum
            }
        }
        return out
    }
}
