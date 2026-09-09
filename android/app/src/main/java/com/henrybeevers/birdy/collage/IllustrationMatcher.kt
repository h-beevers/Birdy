package com.henrybeevers.birdy.collage

import android.content.Context
import android.content.res.AssetManager
import java.util.Locale

/**
 * Mirrors docs/android-shared-contract.md illustration matching:
 * slugify + word-boundary fuzzy + mtime tie-break.
 * Asset mtimes are fake (index order); bundled assets have equal "mtime".
 */
class IllustrationMatcher private constructor(
    private val listNames: (String) -> List<String>,
) {
    constructor(assets: AssetManager) : this({ dir ->
        try {
            assets.list(dir)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    })

    data class Entry(
        val assetPath: String,
        val slug: String,
        val tokens: Set<String>,
        val mtime: Long,
    )

    private var index: Map<String, Entry>? = null
    private var bySlugList: List<Entry> = emptyList()

    fun ensureIndex(assetDir: String = "illustrations") {
        if (index != null) return
        val map = mutableMapOf<String, Entry>()
        val list = mutableListOf<Entry>()
        val names = listNames(assetDir)
        names.forEachIndexed { i, fname ->
            val lower = fname.lowercase(Locale.US)
            if (!lower.endsWith(".png") && !lower.endsWith(".jpg") &&
                !lower.endsWith(".jpeg") && !lower.endsWith(".webp")
            ) return@forEachIndexed
            val base = fname.substringBeforeLast('.')
            val slug = slugify(base)
            if (slug.isEmpty()) return@forEachIndexed
            val entry = Entry(
                assetPath = "$assetDir/$fname",
                slug = slug,
                tokens = tokenize(base),
                mtime = i.toLong(), // assets: higher index slightly preferred on tie
            )
            val existing = map[slug]
            if (existing == null || entry.mtime > existing.mtime) {
                map[slug] = entry
            }
            list.add(entry)
        }
        index = map
        bySlugList = list
    }

    fun findLocal(commonName: String, scientific: String? = null): String? {
        ensureIndex()
        val commonHit = lookup(commonName)
        val sciHit = if (!scientific.isNullOrBlank()) lookup(scientific) else null
        return when {
            commonHit != null && sciHit != null ->
                if (sciHit.mtime >= commonHit.mtime) sciHit.assetPath else commonHit.assetPath
            commonHit != null -> commonHit.assetPath
            sciHit != null -> sciHit.assetPath
            else -> null
        }
    }

    private fun lookup(name: String): Entry? {
        val map = index ?: return null
        val exact = map[slugify(name)]
        if (exact != null) return exact
        val query = tokenize(name)
        if (query.isEmpty()) return null
        var best: Entry? = null
        var bestScore: Pair<Int, Int>? = null // shared, -extra
        for (entry in bySlugList) {
            val score = fuzzyScore(query, entry.tokens) ?: continue
            if (best == null || better(score, entry, bestScore!!, best!!)) {
                best = entry
                bestScore = score
            }
        }
        return best
    }

    private fun better(
        score: Pair<Int, Int>,
        entry: Entry,
        bestScore: Pair<Int, Int>,
        best: Entry,
    ): Boolean {
        if (score.first != bestScore.first) return score.first > bestScore.first
        if (score.second != bestScore.second) return score.second > bestScore.second
        if (entry.mtime != best.mtime) return entry.mtime > best.mtime
        return entry.assetPath > best.assetPath
    }

    companion object {
        fun slugify(text: String): String =
            text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")

        fun tokenize(text: String): Set<String> =
            text.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

        fun fuzzyScore(query: Set<String>, key: Set<String>): Pair<Int, Int>? {
            if (query.isEmpty() || key.isEmpty()) return null
            return when {
                key.containsAll(query) -> query.size to -(key.size - query.size)
                query.containsAll(key) -> key.size to -(query.size - key.size)
                else -> null
            }
        }

        fun fromContext(context: Context) = IllustrationMatcher(context.assets)

        /** Matcher over a fixed list of filenames — used by the unit tests. */
        fun forNames(names: List<String>) = IllustrationMatcher { names }
    }
}
