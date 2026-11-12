package com.henrybeevers.birdy.collage

import android.content.Context
import okhttp3.OkHttpClient
import java.io.File

/**
 * The GB illustration pack from
 * [AvianAssets](https://github.com/jonnywright/AvianAssets): ~300 pre-cutout
 * UK species plates, the same pack `import_avianassets_illustrations.py` pulls
 * on the desktop.
 *
 * Birdy does **not** bundle that artwork in the APK: it is not covered by this
 * repo's GPL-3.0 and the pack ships no licence of its own, so shipping it
 * inside a release would be a redistribution this repo can't make. The plates
 * are instead fetched on the user's own device, straight from the source repo,
 * and cached under `filesDir/gb_illustrations` (see [IllustrationPack]).
 *
 * Regenerate the species index with `android/tools/sync_gb_pack_manifest.py`.
 */
class GbIllustrationPack(
    context: Context,
    http: OkHttpClient = IllustrationPack.defaultHttp(),
    dir: File = File(context.filesDir, DIR_NAME),
) : IllustrationPack(
    context = context,
    label = "GB pack",
    dirName = DIR_NAME,
    manifestAsset = MANIFEST_ASSET,
    http = http,
    dir = dir,
    urlFor = { _, slug -> GbIllustrationPack.urlFor(slug) },
) {
    companion object {
        const val DIR_NAME = "gb_illustrations"
        const val MANIFEST_ASSET = "gb_pack_manifest.txt"
        const val SOURCE_URL = "https://github.com/jonnywright/AvianAssets"
        const val RAW_BASE =
            "https://raw.githubusercontent.com/jonnywright/AvianAssets/main/illustrations/"

        /** Raw download URL for one species slug. */
        fun urlFor(slug: String): String = "$RAW_BASE$slug.png"

        fun slugFor(scientific: String?): String? = PackIndex.slugFor(scientific)

        fun displayName(slug: String): String = PackIndex.displayName(slug)

        /** Species slugs from the bundled index, ignoring blanks and `#` notes. */
        fun parseManifest(lines: List<String>): Set<String> = PackIndex.parse(lines).species
    }
}
