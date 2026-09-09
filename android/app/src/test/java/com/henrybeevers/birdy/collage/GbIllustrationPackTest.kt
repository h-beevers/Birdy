package com.henrybeevers.birdy.collage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GB pack's URL derivation and its bundled species index.
 *
 * A plate's URL is a pure string join off the species' scientific name, so
 * these are exactly the parts that decide whether a phone fetches the right
 * file — worth pinning without a device.
 */
class GbIllustrationPackTest {

    private val manifestFile = File("src/main/assets/gb_pack_manifest.txt")

    private fun manifest(): Set<String> =
        GbIllustrationPack.parseManifest(manifestFile.readLines())

    @Test
    fun `scientific names become the pack's own filenames`() {
        assertEquals("erithacus-rubecula", GbIllustrationPack.slugFor("Erithacus rubecula"))
        assertEquals("parus-major", GbIllustrationPack.slugFor("  Parus major  "))
        // BirdWeather occasionally hands back a subspecies or an odd separator.
        assertEquals("corvus-corone-cornix", GbIllustrationPack.slugFor("Corvus corone/cornix"))
        assertNull(GbIllustrationPack.slugFor(""))
        assertNull(GbIllustrationPack.slugFor(null))
        assertNull(GbIllustrationPack.slugFor("!!!"))
    }

    @Test
    fun `plate urls point at the source repo over https`() {
        val url = GbIllustrationPack.urlFor("erithacus-rubecula")
        assertEquals(
            "https://raw.githubusercontent.com/jonnywright/AvianAssets/main/" +
                "illustrations/erithacus-rubecula.png",
            url,
        )
        assertTrue(url.startsWith("https://"))
    }

    @Test
    fun `the bundled index covers the pack and holds only slugs`() {
        val species = manifest()
        assertTrue("index looks truncated: ${species.size}", species.size > 250)
        assertTrue("robin missing from the index", "erithacus-rubecula" in species)
        assertTrue("great tit missing from the index", "parus-major" in species)
        // The pack ships an alternate "-2" pose for some birds; Birdy wants one
        // plate per species, so those must not be in the index.
        assertTrue(
            "alternate poses leaked into the index",
            species.none { it.endsWith("-2") },
        )
        species.forEach {
            assertTrue("index entry is not a usable slug: $it", Regex("[a-z0-9-]+").matches(it))
            assertEquals("index entry is not round-trip stable: $it", it, GbIllustrationPack.slugFor(it))
        }
    }

    @Test
    fun `index parsing ignores comments and blank lines`() {
        val parsed = GbIllustrationPack.parseManifest(
            listOf("# a note", "", "  ", "parus-major", "Not A Slug", "erithacus-rubecula"),
        )
        assertEquals(setOf("parus-major", "erithacus-rubecula"), parsed)
    }

    @Test
    fun `slugs render back into readable species names for progress text`() {
        assertEquals("Erithacus rubecula", GbIllustrationPack.displayName("erithacus-rubecula"))
    }
}
