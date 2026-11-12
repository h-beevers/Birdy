package com.henrybeevers.birdy.collage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The classic (Fugleramme) pack's index and pinned URLs. */
class ClassicPlatePackTest {

    private val index = PackIndex.parse(File("src/main/assets/classic_pack_manifest.txt").readLines())

    @Test
    fun `the bundled index is pinned to a commit and covers common birds`() {
        assertTrue("no pinned commit", Regex("[0-9a-f]{40}").matches(index.commit))
        assertTrue("index looks truncated: ${index.species.size}", index.species.size > 500)
        for (bird in listOf("Erithacus rubecula", "Turdus merula", "Pica pica", "Parus major")) {
            assertEquals(PackIndex.slugFor(bird), index.resolve(bird))
        }
        assertTrue("alternate plates leaked in", index.species.none { Regex(".*-\\d+").matches(it) })
    }

    @Test
    fun `older scientific names reach the plate filed under the current one`() {
        // BirdNET still reports the jackdaw as Corvus monedula.
        assertEquals("coloeus-monedula", index.resolve("Corvus monedula"))
        assertEquals("coloeus-monedula", index.resolve("Coloeus monedula"))
        index.aliases.forEach { (old, new) ->
            assertTrue("alias $old points at a missing plate", new in index.species)
        }
        assertNull(index.resolve("Notabird imaginarius"))
        assertNull(index.resolve(""))
    }

    @Test
    fun `plate urls are https and pinned`() {
        val url = ClassicPlatePack.urlFor(index.commit, "erithacus-rubecula")
        assertEquals(
            "https://raw.githubusercontent.com/arnegiacomo/fugleramme/${index.commit}" +
                "/assets/artwork/classic/birds/erithacus-rubecula.webp",
            url,
        )
        assertNull(ClassicPlatePack.urlFor("main", "erithacus-rubecula"))
        assertNull(ClassicPlatePack.urlFor(index.commit, "../etc/passwd"))
    }

    @Test
    fun `index parsing keeps commit species and aliases apart`() {
        val parsed = PackIndex.parse(
            listOf("# note", "@commit " + "a".repeat(40), "parus-major", "old-name=parus-major", "Bad Line", "@commit nope"),
        )
        assertEquals("a".repeat(40), parsed.commit)
        assertEquals(setOf("parus-major"), parsed.species)
        assertEquals(mapOf("old-name" to "parus-major"), parsed.aliases)
    }
}
