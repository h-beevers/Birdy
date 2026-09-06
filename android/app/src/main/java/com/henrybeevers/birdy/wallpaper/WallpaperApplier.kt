package com.henrybeevers.birdy.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Sets home and/or lock wallpapers via WallpaperManager.
 *
 * Lock screen: API 24+ FLAG_LOCK. Many OEMs (Samsung One UI, Xiaomi MIUI,
 * some ColorOS/OxygenOS builds) ignore or only partially honour FLAG_LOCK;
 * home wallpaper is generally reliable. Documented for users in README.
 */
class WallpaperApplier(private val context: Context) {

    data class Result(
        val homeOk: Boolean,
        val lockOk: Boolean,
        val message: String,
    )

    fun apply(bitmap: Bitmap, setHome: Boolean, setLock: Boolean): Result {
        if (!setHome && !setLock) {
            return Result(false, false, "Neither home nor lock selected")
        }
        val wm = WallpaperManager.getInstance(context)
        var homeOk = false
        var lockOk = false
        val notes = mutableListOf<String>()

        val jpeg = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()

        if (setHome) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wm.setStream(
                        ByteArrayInputStream(jpeg),
                        null,
                        true,
                        WallpaperManager.FLAG_SYSTEM,
                    )
                } else {
                    wm.setStream(ByteArrayInputStream(jpeg))
                }
                homeOk = true
                notes += "home set"
            } catch (e: Exception) {
                notes += "home failed: ${e.message}"
            }
        }

        if (setLock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    wm.setStream(
                        ByteArrayInputStream(jpeg),
                        null,
                        true,
                        WallpaperManager.FLAG_LOCK,
                    )
                    lockOk = true
                    notes += "lock requested (OEM may ignore FLAG_LOCK)"
                } catch (e: Exception) {
                    notes += "lock failed: ${e.message}"
                }
            } else {
                notes += "lock requires API 24+"
            }
        }

        return Result(homeOk, lockOk, notes.joinToString("; "))
    }
}
