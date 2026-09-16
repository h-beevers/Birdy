package com.henrybeevers.birdy.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collage geometry, checked without a device.
 *
 * These cover the ways the layout has actually gone wrong: tiles pushed
 * off-canvas, tiles landing on top of each other, and — the phone-only
 * failure — birds drawn piled over one another instead of packed into a
 * collage the way the desktop wallpaper arranges them.
 */
class CollageLayoutTest {

    private val width = 1080
    private val top = 210f
    private val bottom = 1834f

    private fun sizesFor(counts: List<Int>) =
        CollageLayout.tileSizes(counts, width, bottom - top)

    /** A plausible bird shape: an oval that leaves the tile's corners empty. */
    private fun birdSilhouette(gw: Int = 26, gh: Int = 18): CollageLayout.Silhouette {
        val pixels = IntArray(gw * gh)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val dx = (x + 0.5f) / gw - 0.5f
                val dy = (y + 0.5f) / gh - 0.5f
                val inside = (dx * dx) / 0.25f + (dy * dy) / 0.25f <= 1f
                pixels[y * gw + x] = if (inside) 0xFF804020.toInt() else 0
            }
        }
        return CollageLayout.silhouetteFrom(pixels, gw, gh, gridW = gw)
    }

    private fun tilesFor(counts: List<Int>): List<CollageLayout.Tile> {
        val sizes = sizesFor(counts)
        return sizes.mapIndexed { i, size ->
            val (w, h) = CollageLayout.tileBox(size, 26, 18)
            CollageLayout.Tile(i, w, h, birdSilhouette())
        }
    }

    @Test
    fun `every tile stays on the canvas for flocks of any size`() {
        for (n in listOf(1, 2, 5, 12, 30, CollageRendererLimits.MAX)) {
            val counts = List(n) { it % 7 + 1 }
            val placements = CollageLayout.packFlock(tilesFor(counts), width, top, bottom)
            assertEquals(n, placements.size)
            assertTrue(
                "flock of $n put a tile off-canvas",
                CollageLayout.allInside(placements, width, top, bottom),
            )
        }
    }

    @Test
    fun `placements come back in the order they were handed in`() {
        val placements = CollageLayout.packFlock(tilesFor(List(9) { it + 1 }), width, top, bottom)
        assertEquals((0 until 9).toList(), placements.map { it.index })
    }

    @Test
    fun `birds are packed into a collage, not piled on top of each other`() {
        val placements = CollageLayout.packFlock(tilesFor(List(24) { 3 }), width, top, bottom)
        for (a in placements.indices) {
            for (b in a + 1 until placements.size) {
                assertTrue(
                    "tiles $a and $b landed in the same spot",
                    !CollageLayout.isStacked(placements[a], placements[b]),
                )
            }
        }
        // Silhouettes may nest into each other's corners, but no bird may sit
        // half-buried under a neighbour the way the old relaxed-circle layout
        // left them on a phone-shaped canvas.
        assertTrue(
            "tiles overlap too heavily",
            CollageLayout.worstOverlapFraction(placements) < 0.34f,
        )
    }

    @Test
    fun `the flock fills the canvas rather than hugging the middle`() {
        val placements = CollageLayout.packFlock(tilesFor(List(18) { 4 }), width, top, bottom)
        val spreadY = placements.maxOf { it.cy } - placements.minOf { it.cy }
        assertTrue(
            "flock only used ${spreadY.toInt()}px of a ${(bottom - top).toInt()}px canvas",
            spreadY > (bottom - top) * 0.45f,
        )
    }

    @Test
    fun `a tile keeps the art's aspect ratio`() {
        val (w, h) = CollageLayout.tileBox(300f, 800, 400)
        assertEquals(300f, w, 0.5f)
        assertEquals(150f, h, 0.5f)
    }

    @Test
    fun `a silhouette follows the art's shape, not its bounding box`() {
        val sil = birdSilhouette()
        assertTrue("an oval should not fill its whole grid", sil.coverage < 0.95f)
        assertTrue("an oval should cover most of its middle row", sil.coverage > 0.5f)
        assertTrue("the centre of the bird is solid", sil[sil.gw / 2, sil.gh / 2])
        assertTrue("the corner of the tile is empty", !sil[0, 0])
    }

    @Test
    fun `a disc silhouette is round`() {
        val disc = CollageLayout.discSilhouette(20)
        assertTrue(disc[10, 10])
        assertTrue(!disc[0, 0])
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
        assertTrue(CollageLayout.packFlock(emptyList(), width, top, bottom).isEmpty())
    }

    private object CollageRendererLimits {
        const val MAX = 60 // CollageRenderer.MAX_BIRDS, without pulling in Android types
    }
}
