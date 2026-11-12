package com.henrybeevers.birdy.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A pack of bird plates that lives in someone else's repository and is
 * downloaded on the user's own device, one plate per species, rather than
 * bundled in the APK.
 *
 * Which species a pack has is looked up in a plain-text index shipped in the
 * app's assets ([PackIndex]: species names, not artwork), so a plate's URL is
 * a pure string join and the phone never calls the GitHub API. Plates are
 * re-encoded on arrival and cached under `filesDir/<dirName>`.
 *
 * The two packs are [GbIllustrationPack] and [ClassicPlatePack].
 */
open class IllustrationPack(
    private val context: Context,
    /** Short name used in status text, e.g. "GB pack". */
    val label: String,
    dirName: String,
    private val manifestAsset: String,
    private val http: OkHttpClient = defaultHttp(),
    private val dir: File = File(context.filesDir, dirName),
    /** Download URL for one resolved slug, or null if it can't be built. */
    private val urlFor: (index: PackIndex, slug: String) -> String?,
) {

    /** Progress of a bulk download, reported per plate. */
    data class Progress(val done: Int, val total: Int, val species: String)

    /** Outcome of a bulk download. */
    data class Result(val label: String, val downloaded: Int, val skipped: Int, val failed: Int) {
        val message: String
            get() = "$label: $downloaded new, $skipped already here" +
                if (failed > 0) ", $failed failed" else ""
    }

    private val failed = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var loaded: PackIndex? = null

    /** The pack's index, read once from the bundled asset. */
    fun index(): PackIndex {
        loaded?.let { return it }
        val parsed = try {
            context.assets.open(manifestAsset).bufferedReader().use { reader ->
                PackIndex.parse(reader.readLines())
            }
        } catch (_: Exception) {
            PackIndex.EMPTY
        }
        loaded = parsed
        return parsed
    }

    /** Scientific-name slugs the pack ships. */
    fun species(): Set<String> = index().species

    /** True when the pack has a plate for this species (downloaded or not). */
    fun covers(scientific: String?): Boolean = index().resolve(scientific) != null

    /** An already-downloaded plate for this species, or null. */
    fun cached(scientific: String?): File? {
        val slug = index().resolve(scientific) ?: return null
        return fileFor(slug).takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * A plate for this species, downloading it once if needed. Returns null for
     * a species the pack doesn't cover, or when the download fails (the caller
     * then falls back to another source). Blocking: call off the main thread.
     */
    fun ensure(scientific: String?): File? {
        val slug = index().resolve(scientific) ?: return null
        fileFor(slug).takeIf { it.isFile && it.length() > 0 }?.let { return it }
        if (slug in failed) return null
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
        val index = index()
        val slugs = scientificNames.mapNotNull { index.resolve(it) }.distinct()
        var downloaded = 0
        var skipped = 0
        var failures = 0
        slugs.forEachIndexed { i, slug ->
            onProgress(Progress(i, slugs.size, PackIndex.displayName(slug)))
            val existing = fileFor(slug)
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
        return Result(label, downloaded, skipped, failures)
    }

    /** How many plates are cached on this device. */
    fun installedCount(): Int =
        dir.listFiles()?.count { it.isFile && it.name.endsWith(".webp") && it.length() > 0 } ?: 0

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

    private fun fileFor(slug: String) = File(dir, "$slug.webp")

    private fun download(slug: String): File? {
        require(PackIndex.SLUG.matches(slug)) { "refusing to fetch odd slug: $slug" }
        val url = urlFor(index(), slug) ?: return null
        require(url.startsWith("https://")) { "refusing a non-https plate url" }
        val request = Request.Builder().url(url).get().build()
        val bytes = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                failed.add(slug)
                return null
            }
            resp.body?.bytes()
        } ?: return null

        // Source plates are 1000-1200 px and a few hundred KB each. A phone
        // collage never draws one above ~450 px, so re-encode to a 512 px
        // WebP (alpha kept: the cutout *is* the point) and keep the cache
        // around 40 KB a bird.
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val shrunk = shrink(decoded, MAX_EDGE)
        dir.mkdirs()
        val tmp = File(dir, "$slug.webp.tmp")
        val out = fileFor(slug)
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
        private const val MAX_EDGE = 512
        private const val WEBP_QUALITY = 88

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
