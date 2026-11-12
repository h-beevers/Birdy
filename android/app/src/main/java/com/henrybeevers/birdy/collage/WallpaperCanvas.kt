package com.henrybeevers.birdy.collage

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What size to draw the wallpaper at, and which part of it stays visible.
 *
 * The collage used to be a fixed 1080x1920 (9:16) bitmap. Almost no current
 * phone is 9:16 — a Pixel 6 is 1080x2400 (9:20) — so Android scaled the image
 * up to the screen's height, which left it wider than the screen, and then
 * showed only part of that width: the flock landed off-centre with the birds
 * on one side cut off. Drawing at the screen's own pixel size removes the
 * crop altogether, and the [Frame] keeps the flock clear of the status bar,
 * the launcher's widgets and the navigation bar, and of the slight zoom some
 * launchers apply to the wallpaper.
 *
 * No Android types, so it is unit tested on the JVM.
 */
object WallpaperCanvas {

    data class Size(val width: Int, val height: Int)

    /** Where things go on a canvas, in pixels. */
    data class Frame(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** Baseline of the title, when one is drawn. */
        val titleBaseline: Float,
        /** Baseline of the small credit line. */
        val footerBaseline: Float,
        /** Short side of the canvas: what text sizes scale from. */
        val unit: Float,
    )

    /** Used when the display can't be measured at all. */
    val FALLBACK = Size(1080, 2400)

    /** Longest edge worth rendering: beyond this it is only memory. */
    const val MAX_EDGE = 3200

    /**
     * The bitmap size for a display of [displayWidth] x [displayHeight]
     * physical pixels.
     *
     * A phone's launcher is portrait whichever way the device happens to be
     * held when a background refresh runs, so anything narrower than a tablet
     * ([smallestWidthDp] under 600) is always rendered portrait. Tablets and
     * foldables keep the orientation they report.
     */
    fun targetSize(displayWidth: Int, displayHeight: Int, smallestWidthDp: Int): Size {
        if (displayWidth < 240 || displayHeight < 240) return FALLBACK
        var w = displayWidth
        var h = displayHeight
        if (smallestWidthDp in 1 until 600 && w > h) {
            w = displayHeight
            h = displayWidth
        }
        val longest = max(w, h)
        if (longest > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longest
            w = max(1, (w * scale).roundToInt())
            h = max(1, (h * scale).roundToInt())
        }
        return Size(w, h)
    }

    /**
     * The area the flock may occupy on a [width] x [height] canvas.
     *
     * Portrait: the top band is left for the status bar and the launcher's
     * date/weather line (or the lock screen clock's first line), with the
     * title sitting just under it; the bottom band for the navigation bar.
     * The side margins absorb a launcher's wallpaper zoom, so a bird at the
     * edge of the flock is never clipped by it.
     */
    fun frame(width: Int, height: Int, showTitle: Boolean): Frame {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        return if (height >= width) {
            Frame(
                left = w * 0.06f,
                top = h * (if (showTitle) 0.205f else 0.14f),
                right = w * 0.94f,
                bottom = h * 0.90f,
                titleBaseline = h * 0.18f,
                footerBaseline = h * 0.925f,
                unit = unit,
            )
        } else {
            Frame(
                left = w * 0.04f,
                top = h * (if (showTitle) 0.17f else 0.07f),
                right = w * 0.96f,
                bottom = h * 0.91f,
                titleBaseline = h * 0.125f,
                footerBaseline = h * 0.955f,
                unit = unit,
            )
        }
    }
}
