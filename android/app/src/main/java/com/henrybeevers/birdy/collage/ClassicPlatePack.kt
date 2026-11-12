package com.henrybeevers.birdy.collage

import android.content.Context
import okhttp3.OkHttpClient
import java.io.File

/**
 * Classic 1800s bird plates from
 * [Fugleramme](https://github.com/arnegiacomo/fugleramme): about 800 species of
 * hand-coloured plates by Gould, the von Wright brothers, Audubon and others,
 * digitally restored and cut out for that project.
 *
 * The artwork is CC BY-SA 4.0 (see CLASSIC_PLATES.md at the repo root). At
 * ~160 MB it is far too big to bundle, so the plates a collage needs are
 * fetched on the device and cached under `filesDir/classic_plates`, like the
 * GB pack (see [IllustrationPack]).
 *
 * Every URL is pinned to the commit recorded in the index: that pack is still
 * being curated and renumbers its files, so an index built from one commit
 * must not be read against another. The index also maps the older scientific
 * names BirdNET and BirdWeather still report onto the names the plates are
 * filed under. Regenerate it with
 * `android/tools/sync_classic_pack_manifest.py`.
 */
class ClassicPlatePack(
    context: Context,
    http: OkHttpClient = IllustrationPack.defaultHttp(),
    dir: File = File(context.filesDir, DIR_NAME),
) : IllustrationPack(
    context = context,
    label = "Classic plates",
    dirName = DIR_NAME,
    manifestAsset = MANIFEST_ASSET,
    http = http,
    dir = dir,
    urlFor = { index, slug -> ClassicPlatePack.urlFor(index.commit, slug) },
) {
    companion object {
        const val DIR_NAME = "classic_plates"
        const val MANIFEST_ASSET = "classic_pack_manifest.txt"
        const val SOURCE_URL = "https://github.com/arnegiacomo/fugleramme"
        const val LICENCE = "CC BY-SA 4.0"
        const val RAW_BASE = "https://raw.githubusercontent.com/arnegiacomo/fugleramme/"
        const val BIRDS_PATH = "/assets/artwork/classic/birds/"

        /**
         * Raw download URL for one species slug at the pinned [commit], or
         * null when the index carries no usable pin.
         */
        fun urlFor(commit: String, slug: String): String? {
            if (!Regex("[0-9a-f]{40}").matches(commit)) return null
            if (!PackIndex.SLUG.matches(slug)) return null
            return "$RAW_BASE$commit$BIRDS_PATH$slug.webp"
        }
    }
}
