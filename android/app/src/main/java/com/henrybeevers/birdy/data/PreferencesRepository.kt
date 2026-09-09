package com.henrybeevers.birdy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("birdy_settings")

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val POSTCODE = stringPreferencesKey("postcode")
        val RADIUS = intPreferencesKey("radius_km")
        val DAYS = intPreferencesKey("days")
        val HOURS = intPreferencesKey("hours")
        val REFRESH = intPreferencesKey("refresh_hours")
        val BG = stringPreferencesKey("bg_color")
        val MIN_CONF = floatPreferencesKey("min_confidence")
        val SHOW_TITLE = booleanPreferencesKey("show_title")
        val TITLE = stringPreferencesKey("title_text")
        val SHOW_LABELS = booleanPreferencesKey("show_labels")
        val SET_HOME = booleanPreferencesKey("set_home")
        val SET_LOCK = booleanPreferencesKey("set_lock")
        val FIRST_RUN = booleanPreferencesKey("first_run_done")
        val BIRDNET = stringPreferencesKey("birdnet_url")
        val GB_PACK = booleanPreferencesKey("download_gb_pack")
        val LAST_STATUS = stringPreferencesKey("last_status")
        val LAST_ERROR = stringPreferencesKey("last_error")
    }

    val settingsFlow: Flow<BirdySettings> = context.dataStore.data.map { p ->
        BirdySettings(
            postcode = p[Keys.POSTCODE] ?: "HG3 1AP",
            radiusKm = p[Keys.RADIUS] ?: 20,
            days = p[Keys.DAYS] ?: 1,
            hours = p[Keys.HOURS] ?: 0,
            refreshHours = p[Keys.REFRESH] ?: 6,
            bgColor = p[Keys.BG] ?: "#f4ede0",
            minConfidence = p[Keys.MIN_CONF] ?: 0f,
            showTitle = p[Keys.SHOW_TITLE] ?: true,
            titleText = p[Keys.TITLE] ?: "Garden Visitors",
            showLabels = p[Keys.SHOW_LABELS] ?: false,
            setHome = p[Keys.SET_HOME] ?: true,
            setLock = p[Keys.SET_LOCK] ?: true,
            firstRunDone = p[Keys.FIRST_RUN] ?: false,
            birdnetUrl = p[Keys.BIRDNET] ?: "",
            downloadGbPack = p[Keys.GB_PACK] ?: true,
        )
    }

    suspend fun current(): BirdySettings = settingsFlow.first()

    suspend fun save(s: BirdySettings) {
        context.dataStore.edit { p ->
            p[Keys.POSTCODE] = s.postcode.trim()
            p[Keys.RADIUS] = s.radiusKm.coerceIn(1, 200)
            p[Keys.DAYS] = s.days.coerceIn(0, 30)
            p[Keys.HOURS] = s.hours.coerceIn(0, 168)
            p[Keys.REFRESH] = s.refreshHours.coerceIn(1, 168)
            p[Keys.BG] = s.bgColor.trim().ifEmpty { "#f4ede0" }
            p[Keys.MIN_CONF] = s.minConfidence.coerceIn(0f, 1f)
            p[Keys.SHOW_TITLE] = s.showTitle
            p[Keys.TITLE] = s.titleText
            p[Keys.SHOW_LABELS] = s.showLabels
            p[Keys.SET_HOME] = s.setHome
            p[Keys.SET_LOCK] = s.setLock
            p[Keys.FIRST_RUN] = s.firstRunDone
            p[Keys.BIRDNET] = s.birdnetUrl.trim()
            p[Keys.GB_PACK] = s.downloadGbPack
        }
    }

    suspend fun setStatus(status: String, error: String? = null) {
        context.dataStore.edit { p ->
            p[Keys.LAST_STATUS] = status
            if (error != null) p[Keys.LAST_ERROR] = error
            else p.remove(Keys.LAST_ERROR)
        }
    }

    val statusFlow: Flow<Pair<String, String?>> = context.dataStore.data.map { p ->
        (p[Keys.LAST_STATUS] ?: "") to p[Keys.LAST_ERROR]
    }
}
