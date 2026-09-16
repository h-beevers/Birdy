package com.henrybeevers.birdy.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keying the paper out of an opaque plate — the fix for the pale discs that
 * were showing up behind birds on the phone wallpaper.
 */
class PaperKeyTest {

    private val paper = 0xFFF4EDE0.toInt()
    private val ink = 0xFF2E2620.toInt()

    /** A bird-ish blob of ink on a sheet of paper. */
    private fun plate(w: Int = 40, h: Int = 30, bg: Int = paper): IntArray {
        val px = IntArray(w * h) { bg }
        for (y in h / 4 until h * 3 / 4) {
            for (x in w / 4 until w * 3 / 4) {
                px[y * w + x] = ink
            }
        }
        return px
    }

    @Test
    fun `paper is keyed out and the bird is left alone`() {
        val w = 40
        val h = 30
        val px = plate(w, h)
        assertTrue(PaperKey.removePaper(px, w, h))
        assertEquals(0, px[0] ushr 24)
        assertEquals(0, px[h * w - 1] ushr 24)
        assertEquals(255, px[(h / 2) * w + w / 2] ushr 24)
    }

    @Test
    fun `nearly-paper pixels next to the bird go too, so no ring survives`() {
        val w = 40
        val h = 30
        val px = plate(w, h)
        // The pale ring that used to show on the wallpaper: paper a shade off.
        px[2 * w + 2] = 0xFFF0E9DC.toInt()
        assertTrue(PaperKey.removePaper(px, w, h))
        assertEquals(0, px[2 * w + 2] ushr 24)
    }

    @Test
    fun `art with no flat border is left for the disc path`() {
        val w = 30
        val h = 30
        val px = IntArray(w * h) { i -> 0xFF000000.toInt() or (i * 7919 and 0xFFFFFF) }
        assertFalse(PaperKey.removePaper(px, w, h))
    }

    @Test
    fun `a blank sheet is not turned into an empty cutout`() {
        val w = 20
        val h = 20
        val px = IntArray(w * h) { paper }
        assertFalse(PaperKey.removePaper(px, w, h))
    }

    @Test
    fun `an already transparent plate is rejected rather than re-keyed`() {
        val w = 20
        val h = 20
        val px = IntArray(w * h) { 0 }
        px[10 * w + 10] = ink
        assertFalse(PaperKey.removePaper(px, w, h))
    }

    @Test
    fun `trimming finds the ink, not the margin`() {
        val w = 40
        val h = 30
        val px = plate(w, h)
        PaperKey.removePaper(px, w, h)
        val bounds = PaperKey.opaqueBounds(px, w, h)!!
        assertTrue("trim should start inside the margin", bounds[0] >= w / 4 - 1)
        assertTrue("trim should not keep the whole sheet", bounds[2] < w)
        assertTrue("trim should not keep the whole sheet", bounds[3] < h)
    }

    @Test
    fun `colour distance is per channel`() {
        assertEquals(0, PaperKey.distance(paper, paper))
        assertEquals(255, PaperKey.distance(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
    }
}
