package com.henrybeevers.birdy.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallpaper is drawn at the screen's own size so Android has nothing to
 * crop: these pin the sizing, and that a flock packed into the frame stays
 * centred and fully on a tall phone screen.
 */
class WallpaperCanvasTest {

    @Test
    fun `a phone gets its own tall screen size, not 9 by 16`() {
        // Pixel 6: 1080 x 2400.
        assertEquals(WallpaperCanvas.Size(1080, 2400), WallpaperCanvas.targetSize(1080, 2400, 411))
    }

    @Test
    fun `a phone held sideways during a refresh still gets a portrait wallpaper`() {
        assertEquals(WallpaperCanvas.Size(1080, 2400), WallpaperCanvas.targetSize(2400, 1080, 411))
    }

    @Test
    fun `a tablet keeps the orientation it reports`() {
        assertEquals(WallpaperCanvas.Size(2560, 1600), WallpaperCanvas.targetSize(2560, 1600, 800))
    }

    @Test
    fun `an unmeasurable display falls back and a huge one is capped`() {
        assertEquals(WallpaperCanvas.FALLBACK, WallpaperCanvas.targetSize(0, 0, 0))
        val capped = WallpaperCanvas.targetSize(2160, 3840, 480)
        assertEquals(WallpaperCanvas.MAX_EDGE, capped.height)
        assertEquals(1800, capped.width)
    }

    @Test
    fun `the frame sits inside the canvas with equal side margins`() {
        for ((w, h) in listOf(1080 to 2400, 1080 to 1920, 1440 to 3120, 2560 to 1600)) {
            for (title in listOf(true, false)) {
                val f = WallpaperCanvas.frame(w, h, title)
                assertTrue(f.left > 0f && f.right < w)
                assertTrue(f.top > 0f && f.bottom < h && f.top < f.bottom)
                assertEquals("side margins differ on ${w}x$h", f.left, w - f.right, 0.01f)
                if (title) assertTrue("title overlaps the flock", f.titleBaseline < f.top)
                assertTrue("footer overlaps the flock", f.footerBaseline > f.bottom)
            }
        }
    }

    @Test
    fun `a flock packed into a Pixel 6 frame is centred and fully on screen`() {
        val w = 1080
        val h = 2400
        val frame = WallpaperCanvas.frame(w, h, showTitle = true)
        val counts = List(28) { (it * 7) % 40 + 1 }
        val sizes = CollageLayout.tileSizes(counts, (frame.right - frame.left).toInt(), frame.bottom - frame.top)
        val tiles = sizes.mapIndexed { i, s -> CollageLayout.Tile(i, s, s * 0.8f, CollageLayout.discSilhouette()) }
        val placed = CollageLayout.packFlock(
            tiles = tiles,
            width = w,
            top = frame.top,
            bottom = frame.bottom,
            left = frame.left,
            right = frame.right,
        )
        assertEquals(tiles.size, placed.size)
        assertTrue(
            "a bird is outside the visible frame",
            CollageLayout.allInside(placed, w, frame.top, frame.bottom, left = frame.left, right = frame.right),
        )
        val minX = placed.minOf { it.cx - it.w / 2f }
        val maxX = placed.maxOf { it.cx + it.w / 2f }
        val offCentre = (minX + maxX) / 2f - w / 2f
        assertTrue("flock is off-centre by $offCentre px", kotlin.math.abs(offCentre) < w * 0.08f)
    }
}
