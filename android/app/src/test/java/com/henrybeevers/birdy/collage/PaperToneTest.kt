package com.henrybeevers.birdy.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classic plates keep a margin of scan paper; it must take the canvas colour. */
class PaperToneTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `paper margin is shifted to the canvas colour and plumage is left alone`() {
        val w = 60
        val h = 60
        val paper = argb(255, 250, 246, 238)
        val bird = argb(255, 40, 60, 120)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = when {
                x in 25..35 && y in 25..35 -> bird
                x in 10..50 && y in 10..50 -> paper
                else -> 0
            }
        }
        val target = argb(255, 244, 237, 224)
        assertTrue(PaperTone.level(px, w, h, target))
        assertEquals("paper not toned", target and 0xFFFFFF, px[20 * w + 20] and 0xFFFFFF)
        assertEquals("plumage changed", bird, px[30 * w + 30])
        assertEquals("outside the cut must stay clear", 0, px[2 * w + 2] ushr 24)
        assertTrue("cut edge not feathered", (px[10 * w + 30] ushr 24) < 255)
        assertEquals("interior lost opacity", 255, px[30 * w + 30] ushr 24)
    }

    @Test
    fun `a plate with no margin to sample is left untouched`() {
        val px = IntArray(16 * 16) { argb(255, 10, 20, 30) }
        val before = px.copyOf()
        assertFalse(PaperTone.level(px, 16, 16, argb(255, 244, 237, 224)))
        assertTrue(before.contentEquals(px))
    }
}
