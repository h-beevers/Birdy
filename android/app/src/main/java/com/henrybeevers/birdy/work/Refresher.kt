package com.henrybeevers.birdy.work

import android.content.Context
import android.graphics.Bitmap
import com.henrybeevers.birdy.collage.CollageRenderer
import com.henrybeevers.birdy.data.BirdySettings
import com.henrybeevers.birdy.data.DetectionFetcher
import com.henrybeevers.birdy.data.PreferencesRepository
import com.henrybeevers.birdy.wallpaper.WallpaperApplier
import java.io.File

/**
 * One wallpaper refresh, start to finish: fetch detections, draw the collage
 * at the screen's own size, keep a copy for the in-app preview, set the
 * wallpaper, record a status line.
 *
 * Shared by the "refresh now" button and the periodic [RefreshWorker], which
 * used to each carry their own copy of these steps.
 */
object Refresher {
    const val PREVIEW_FILE = "last_collage.jpg"

    fun previewFile(context: Context) = File(context.filesDir, PREVIEW_FILE)

    /** Blocking network and bitmap work: call off the main thread. */
    suspend fun run(
        context: Context,
        prefs: PreferencesRepository,
        settings: BirdySettings,
    ): String {
        val nearby = DetectionFetcher().fetch(settings)
        val applier = WallpaperApplier(context)
        val size = applier.targetSize()
        val rendered = CollageRenderer(context)
            .renderDetailed(nearby.species, settings, size.width, size.height)
        val bmp = rendered.bitmap
        try {
            val preview = previewFile(context)
            val tmp = File(preview.parentFile, "$PREVIEW_FILE.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(preview)) {
                tmp.copyTo(preview, overwrite = true)
                tmp.delete()
            }
            val wall = applier.apply(bmp, settings.setHome, settings.setLock)
            val art = buildString {
                append("${rendered.illustrated} illustrated")
                if (rendered.classic > 0) append(" (${rendered.classic} classic)")
                if (rendered.photos > 0) append(", ${rendered.photos} photo")
            }
            val msg = "${nearby.sourceStatus} · ${nearby.species.size} species near " +
                "${nearby.placeName} · $art · ${wall.message}"
            prefs.setStatus(msg, if (wall.homeOk || wall.lockOk) null else wall.message)
            return msg
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }
}
