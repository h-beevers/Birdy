package com.henrybeevers.birdy.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The GB illustration pack from
 * [AvianAssets](https://github.com/jonnywright/AvianAssets) — ~300 pre-cutout
 * UK species plates, the same pack `import_avianassets_illustrations.py` pulls
 * on the desktop.
 *
 * Birdy does **not** bundle that artwork in the APK: it is not covered by this
 * repo's GPL-3.0 and the pack ships no licence of its own, so shipping it
 * inside a release would be a redistribution this repo can't make. The plates
 * are instead fetched on the user's own device, straight from the source repo,
 * and cached under `filesDir/gb_illustrations`.
 *
 * Which species the pack has is looked up in `assets/gb_pack_manifest.txt`
 * (species names, not artwork), so a plate's URL is a pure string join and no
 * GitHub API call is needed on the phone. Regenerate that index with
 * `android/tools/sync_gb_pack_manifest.py`.
 */
class GbIllustrationPack(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val dir: File = File(context.filesDir, DIR_NAME),
) {

    /** Progress of a bulk download, reported per plate. */
    data class Progress(val done: Int, val total: Int, val species: String)

    /** Outcome of a bulk download. */
    data class Result(val downloaded: Int, val skipped: Int, val failed: Int) {
        val message: String
            get() = "GB pack: $downloaded new, $skipped already here" +
                if (failed > 0) ", $failed failed" else ""
    }

    private val failed = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var manifest: Set<String>? = null

    /** Scientific-name slugs the pack ships, from the bundled index. */
    fun species(): Set<String> {
        manifest?.let { return it }
        val loaded = try {
            context.assets.open(MANIFEST_ASSET).bufferedReader().use { reader ->
                parseManifest(reader.readLines())
            }
        } catch (_: Exception) {
            emptySet()
        }
        manifest = loaded
        return loaded
    }

    /** True when the pack has a plate for this species (downloaded or not). */
    fun covers(scientific: String?): Boolean = slugFor(scientific)?.let { it in species() } == true

    /** An already-downloaded plate for this species, or null. */
    fun cached(scientific: String?): File? {
        val slug = slugFor(scientific) ?: return null
        val file = File(dir, "$slug.webp")
        return if (file.isFile && file.length() > 0) file else null
    }

    /**
     * A plate for this species, downloading it once if needed. Returns null for
     * a species the pack doesn't cover, or when the download fails (the caller
     * then falls back to a BirdWeather photo). Blocking — call off the main
     * thread.
     */
    fun ensure(scientific: String?): File? {
        cached(scientific)?.let { return it }
        val slug = slugFor(scientific) ?: return null
        if (slug !in species() || slug in failed) return null
        return try {
            download(slug)
        } catch (_: Exception) {
            failed.add(slug)
            null
        }
    }

    /**
     * Downloads the plates for [scientificNames] that aren't cached yet.
     * Blocking; [onProgress] is called before each fetch.
     */
    fun downloadAll(
        scientificNames: Collection<String>,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val slugs = scientificNames.mapNotNull { slugFor(it) }.filter { it in species() }.distinct()
        var downloaded = 0
        var skipped = 0
        var failures = 0
        slugs.forEachIndexed { i, slug ->
            onProgress(Progress(i, slugs.size, displayName(slug)))
            val existing = File(dir, "$slug.webp")
            if (existing.isFile && existing.length() > 0) {
                skipped++
                return@forEachIndexed
            }
            try {
                if (download(slug) != null) downloaded++ else failures++
            } catch (_: Exception) {
                failures++
            }
        }
        onProgress(Progress(slugs.size, slugs.size, ""))
        return Result(downloaded, skipped, failures)
    }

    /** How many plates are cached on this device. */
    fun installedCount(): Int = dir.listFiles()?.count { it.isFile && it.length() > 0 } ?: 0

    /** How much disk the cached plates take, in bytes. */
    fun bytesOnDisk(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes every cached plate. */
    fun clear(): Int {
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        failed.clear()
        return removed
    }

    private fun download(slug: String): File? {
        require(SLUG.matches(slug)) { "refusing to fetch odd slug: $slug" }
        val request = Request.Builder().url(urlFor(slug)).get().build()
        val bytes = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                failed.add(slug)
                return null
            }
            resp.body?.bytes()
        } ?: return null

        // The source plates are ~1024 px / ~370 KB PNGs. A phone collage never
        // draws one above ~450 px, so re-encode to a 512 px WebP (alpha kept —
        // the cutout *is* the point) and keep the cache around 40 KB a bird.
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val shrunk = shrink(decoded, MAX_EDGE)
        dir.mkdirs()
        val tmp = File(dir, "$slug.webp.tmp")
        val out = File(dir, "$slug.webp")
        try {
            tmp.outputStream().use { stream ->
                shrunk.compress(webpFormat(), WEBP_QUALITY, stream)
            }
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (shrunk != decoded) shrunk.recycle()
            decoded.recycle()
            tmp.delete()
        }
        return if (out.isFile && out.length() > 0) out else null
    }

    private fun shrink(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * scale).roundToInt()),
            max(1, (src.height * scale).roundToInt()),
            true,
        )
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    companion object {
        const val DIR_NAME = "gb_illustrations"
        const val MANIFEST_ASSET = "gb_pack_manifest.txt"
        const val SOURCE_URL = "https://github.com/jonnywright/AvianAssets"
        const val RAW_BASE =
            "https://raw.githubusercontent.com/jonnywright/AvianAssets/main/illustrations/"
        private const val MAX_EDGE = 512
        private const val WEBP_QUALITY = 88
        private val SLUG = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        /** Raw download URL for one species slug. */
        fun urlFor(slug: String): String = "$RAW_BASE$slug.png"

        /**
         * `"Erithacus rubecula"` → `"erithacus-rubecula"`, the pack's own
         * filename convention. Null for a name that isn't usable as a slug.
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

        /** Species slugs from the bundled index, ignoring blanks and `#` notes. */
        fun parseManifest(lines: List<String>): Set<String> =
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && SLUG.matches(it) }
                .toSet()
    }
}
