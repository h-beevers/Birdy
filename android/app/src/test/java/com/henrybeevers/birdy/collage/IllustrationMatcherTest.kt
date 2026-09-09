package com.henrybeevers.birdy.collage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bundled-plate matching, over the same filenames the APK actually ships.
 *
 * A miss here is what puts a desaturated BirdWeather photo in the middle of a
 * painted flock, so the names are worth checking against the real asset list.
 */
class IllustrationMatcherTest {

    private val assetDir = File("src/main/assets/illustrations")

    private fun bundled() = IllustrationMatcher.forNames(
        assetDir.listFiles()?.map { it.name }?.sorted().orEmpty(),
    )

    @Test
    fun `common names match however the file was capitalised or spaced`() {
        val matcher = bundled()
        assertNotNull("Great Tit", matcher.findLocal("Great Tit"))
        assertNotNull("great tit", matcher.findLocal("great tit"))
        assertNotNull("Long-tailed Tit", matcher.findLocal("Long-tailed Tit"))
        assertNotNull("Eurasian Blue Tit", matcher.findLocal("Eurasian Blue Tit"))
    }

    @Test
    fun `a species with no bundled plate falls through to the photo path`() {
        assertNull(bundled().findLocal("Greater Roadrunner", "Geococcyx californianus"))
    }

    @Test
    fun `the bundled assets are all image files with usable names`() {
        val names = assetDir.listFiles()?.map { it.name }.orEmpty()
        assertTrue("expected the full bundled set, found ${names.size}", names.size > 40)
        names.forEach {
            val ext = it.substringAfterLast('.', "").lowercase()
            assertEquals("unexpected asset format: $it", "webp", ext)
            assertTrue(
                "asset name does not slugify: $it",
                IllustrationMatcher.slugify(it.substringBeforeLast('.')).isNotEmpty(),
            )
        }
    }

    @Test
    fun `fuzzy matching prefers the closer name`() {
        val matcher = IllustrationMatcher.forNames(
            listOf("Great Tit.webp", "Great Spotted Woodpecker.webp", "coal tit.webp"),
        )
        assertEquals("illustrations/Great Tit.webp", matcher.findLocal("Great Tit"))
        assertEquals("illustrations/coal tit.webp", matcher.findLocal("Coal Tit"))
        assertEquals(
            "illustrations/Great Spotted Woodpecker.webp",
            matcher.findLocal("Great Spotted Woodpecker"),
        )
    }

    @Test
    fun `slugify and tokenize ignore case, spaces and punctuation`() {
        assertEquals("hoodedcrow", IllustrationMatcher.slugify("Hooded_Crow"))
        assertEquals("hoodedcrow", IllustrationMatcher.slugify("hooded crow"))
        assertEquals(setOf("hooded", "crow"), IllustrationMatcher.tokenize("Hooded-Crow"))
    }
}
