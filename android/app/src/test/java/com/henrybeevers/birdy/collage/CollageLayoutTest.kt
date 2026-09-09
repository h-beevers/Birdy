package com.henrybeevers.birdy.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collage geometry, checked without a device.
 *
 * These cover the two ways the layout has actually gone wrong: tiles pushed
 * off-canvas (the old fixed-step spiral put everything past the edge once a
 * flock got past ~20 birds) and tiles landing on top of each other.
 */
class CollageLayoutTest {

    private val width = 1080
    private val top = 210f
    private val bottom = 1834f

    private fun sizesFor(counts: List<Int>) =
        CollageLayout.tileSizes(counts, width, bottom - top)

    @Test
    fun `every tile stays on the canvas for flocks of any size`() {
        for (n in listOf(1, 2, 5, 12, 30, CollageRendererLimits.MAX)) {
            val counts = List(n) { it % 7 + 1 }
            val placements = CollageLayout.placeTiles(sizesFor(counts), width, top, bottom)
            assertEquals(n, placements.size)
            assertTrue(
                "flock of $n put a tile off-canvas",
                CollageLayout.allInside(placements, width, top, bottom),
            )
        }
    }

    @Test
    fun `tiles do not stack on top of each other`() {
        val placements = CollageLayout.placeTiles(sizesFor(List(24) { 3 }), width, top, bottom)
        for (a in placements.indices) {
            for (b in a + 1 until placements.size) {
                assertTrue(
                    "tiles $a and $b landed in the same spot",
                    !CollageLayout.isStacked(placements[a], placements[b]),
                )
            }
        }
        // Overlap is the look, a pile is not: no pair may sit more than
        // two-thirds on top of another.
        assertTrue(
            "tiles overlap too heavily",
            CollageLayout.worstOverlapFraction(placements) < 0.67f,
        )
    }

    @Test
    fun `a frequently heard bird outsizes a rare one`() {
        val sizes = sizesFor(listOf(120, 40, 1))
        assertTrue("common bird should be biggest", sizes[0] > sizes[1])
        assertTrue("rare bird should be smallest", sizes[1] > sizes[2])
        // …but log weighting keeps it from swallowing the canvas.
        assertTrue("one species swallowed the layout", sizes[0] < sizes[2] * 4f)
    }

    @Test
    fun `tile sizes stay inside the canvas budget`() {
        for (n in listOf(1, 8, 30, 60)) {
            val sizes = sizesFor(List(n) { 5 })
            val covered = sizes.sumOf { (it * it).toDouble() }
            val canvas = width.toDouble() * (bottom - top)
            assertTrue("flock of $n covers too much canvas", covered < canvas * 1.6)
            assertTrue("flock of $n has a tile wider than the canvas", sizes.max() <= width * 0.4f)
            assertTrue("flock of $n produced an invisible tile", sizes.min() > 20f)
        }
    }

    @Test
    fun `art style follows transparency, not just where the art came from`() {
        assertEquals(
            CollageLayout.TileStyle.CUTOUT,
            CollageLayout.styleFor(isIllustration = true, hasAlpha = true),
        )
        assertEquals(
            CollageLayout.TileStyle.PLATE,
            CollageLayout.styleFor(isIllustration = true, hasAlpha = false),
        )
        assertEquals(
            CollageLayout.TileStyle.PHOTO,
            CollageLayout.styleFor(isIllustration = false, hasAlpha = false),
        )
    }

    @Test
    fun `contain never crops and cover never letterboxes`() {
        // A wide plate: contain keeps both edges inside the tile…
        assertEquals(0.5f, CollageLayout.containScale(800, 400, 400), 0.001f)
        // …cover fills it, spilling the long edge over.
        assertEquals(1.0f, CollageLayout.coverScale(800, 400, 400), 0.001f)
    }

    @Test
    fun `an empty flock lays out to nothing rather than crashing`() {
        assertTrue(CollageLayout.tileSizes(emptyList(), width, bottom - top).isEmpty())
        assertTrue(CollageLayout.placeTiles(emptyList(), width, top, bottom).isEmpty())
    }

    private object CollageRendererLimits {
        const val MAX = 60 // CollageRenderer.MAX_BIRDS, without pulling in Android types
    }
}
