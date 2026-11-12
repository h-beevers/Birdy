package com.henrybeevers.birdy.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.henrybeevers.birdy.collage.WallpaperCanvas

/**
 * Sets home and/or lock wallpapers via WallpaperManager.
 *
 * The collage is rendered at [targetSize] (the screen's own pixel size) and
 * handed over with a crop hint covering the whole bitmap, so Android has
 * nothing to crop or pan: what was drawn centred stays centred.
 *
 * Lock screen: many OEMs (Samsung One UI, Xiaomi MIUI, some ColorOS/OxygenOS
 * builds) ignore or only partially honour FLAG_LOCK; home wallpaper is
 * generally reliable. Documented for users in README.
 */
class WallpaperApplier(private val context: Context) {

    data class Result(
        val homeOk: Boolean,
        val lockOk: Boolean,
        val message: String,
    )

    /**
     * The size to render the wallpaper at: the default display's physical
     * resolution. Works from a background worker, which has no window.
     */
    fun targetSize(): WallpaperCanvas.Size {
        val metrics = DisplayMetrics()
        try {
            val displays = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            @Suppress("DEPRECATION")
            displays?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        } catch (_: Exception) {
            // fall through to the resources' metrics below
        }
        val resources = context.resources
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else resources.displayMetrics.widthPixels
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else resources.displayMetrics.heightPixels
        return WallpaperCanvas.targetSize(w, h, resources.configuration.smallestScreenWidthDp)
    }

    fun apply(bitmap: Bitmap, setHome: Boolean, setLock: Boolean): Result {
        if (!setHome && !setLock) {
            return Result(false, false, "Wallpaper not changed (home and lock are both off)")
        }
        val wm = WallpaperManager.getInstance(context)
        if (!wm.isWallpaperSupported) {
            return Result(false, false, "This device does not support wallpapers")
        }
        if (!wm.isSetWallpaperAllowed) {
            return Result(false, false, "Changing the wallpaper is blocked on this device (work profile or policy)")
        }
        // The whole bitmap is the visible area: no parallax, no off-centre crop.
        val whole = Rect(0, 0, bitmap.width, bitmap.height)
        var homeOk = false
        var lockOk = false
        val notes = mutableListOf<String>()

        if (setHome) {
            try {
                wm.setBitmap(bitmap, whole, true, WallpaperManager.FLAG_SYSTEM)
                homeOk = true
                notes += "home screen set"
            } catch (e: Exception) {
                notes += "home screen failed: ${e.message}"
            }
        }
        if (setLock) {
            try {
                wm.setBitmap(bitmap, whole, true, WallpaperManager.FLAG_LOCK)
                lockOk = true
                notes += "lock screen set"
            } catch (e: Exception) {
                notes += "lock screen failed: ${e.message}"
            }
        }
        return Result(homeOk, lockOk, notes.joinToString(", "))
    }
}
