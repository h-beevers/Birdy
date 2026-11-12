package com.henrybeevers.birdy.collage

import java.util.Locale

/**
 * A downloadable illustration pack's species index, as shipped in the app's
 * assets: which scientific-name slugs the pack has a plate for.
 *
 * Besides one slug per line, an index may carry
 * - `@commit <sha>`: the commit of the source repo every URL is pinned to, and
 * - `old-name=current-name`: a scientific name detectors still report, and the
 *   name the pack files that bird's plate under.
 *
 * Plain Kotlin, so it is unit tested on the JVM.
 */
class PackIndex(
    val commit: String,
    val species: Set<String>,
    val aliases: Map<String, String>,
) {
    /** The slug of the plate for this scientific name, or null. */
    fun resolve(scientific: String?): String? {
        val slug = slugFor(scientific) ?: return null
        if (slug in species) return slug
        val target = aliases[slug] ?: return null
        return if (target in species) target else null
    }

    companion object {
        val EMPTY = PackIndex("", emptySet(), emptyMap())

        val SLUG = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val COMMIT = Regex("[0-9a-f]{40}")
        private const val COMMIT_TAG = "@commit"

        /** Parses an index file, ignoring blanks, `#` notes and odd lines. */
        fun parse(lines: List<String>): PackIndex {
            var commit = ""
            val species = mutableSetOf<String>()
            val aliases = mutableMapOf<String, String>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith(COMMIT_TAG)) {
                    val value = line.removePrefix(COMMIT_TAG).trim()
                    if (COMMIT.matches(value)) commit = value
                    continue
                }
                val eq = line.indexOf('=')
                if (eq >= 0) {
                    val old = line.substring(0, eq).trim()
                    val new = line.substring(eq + 1).trim()
                    if (SLUG.matches(old) && SLUG.matches(new)) aliases[old] = new
                    continue
                }
                if (SLUG.matches(line)) species.add(line)
            }
            return PackIndex(commit, species, aliases)
        }

        /**
         * `"Erithacus rubecula"` → `"erithacus-rubecula"`, the filename
         * convention both packs use. Null for a name that isn't usable.
         */
        fun slugFor(scientific: String?): String? {
            if (scientific.isNullOrBlank()) return null
            val slug = scientific.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            return slug.ifEmpty { null }
        }

        /** `"erithacus-rubecula"` → `"Erithacus rubecula"`, for progress text. */
        fun displayName(slug: String): String =
            slug.split("-").filter { it.isNotEmpty() }
                .joinToString(" ")
                .replaceFirstChar { it.uppercase(Locale.US) }
    }
}
