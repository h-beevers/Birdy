package com.henrybeevers.birdy.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.henrybeevers.birdy.BirdyApp
import com.henrybeevers.birdy.collage.CollageRenderer
import com.henrybeevers.birdy.data.BirdWeatherApi
import com.henrybeevers.birdy.data.BirdySettings
import com.henrybeevers.birdy.data.PreferencesRepository
import com.henrybeevers.birdy.wallpaper.WallpaperApplier
import com.henrybeevers.birdy.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BirdyRoot() {
    val context = LocalContext.current
    val prefs = (context.applicationContext as BirdyApp).prefs
    val settings by prefs.settingsFlow.collectAsState(initial = BirdySettings())
    val status by prefs.statusFlow.collectAsState(initial = "" to null)

    if (!settings.firstRunDone) {
        FirstRunScreen(settings = settings, prefs = prefs)
    } else {
        SettingsScreen(settings = settings, prefs = prefs, status = status)
    }
}

@Composable
fun FirstRunScreen(settings: BirdySettings, prefs: PreferencesRepository) {
    var postcode by remember { mutableStateOf(settings.postcode) }
    var radius by remember { mutableStateOf(settings.radiusKm.toFloat()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Welcome to Birdy", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
        Text(
            "Pulls recent bird detections near a UK postcode from BirdWeather " +
                "(public GraphQL, no key), builds a flock collage on-device, and sets " +
                "your home/lock wallpaper. No ads; prefs stay on this phone.",
        )
        OutlinedTextField(
            value = postcode,
            onValueChange = { postcode = it },
            label = { Text("UK postcode") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("Radius: ${radius.toInt()} km")
        Slider(value = radius, onValueChange = { radius = it }, valueRange = 5f..80f)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !busy && postcode.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val s = settings.copy(
                            postcode = postcode.trim(),
                            radiusKm = radius.toInt(),
                            firstRunDone = true,
                        )
                        prefs.save(s)
                        withContext(Dispatchers.IO) {
                            runRefresh(context, prefs, s)
                        }
                        RefreshWorker.enqueue(context, s.refreshHours)
                    } catch (e: Exception) {
                        error = e.message
                        prefs.save(settings.copy(postcode = postcode.trim(), radiusKm = radius.toInt(), firstRunDone = true))
                        RefreshWorker.enqueue(context, settings.refreshHours)
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(20.dp))
            else Text("Build collage & set wallpaper")
        }
        Text(
            "Lock screen: Android 7+ FLAG_LOCK. Samsung/Xiaomi/etc. may ignore the lock flag — home still works.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun SettingsScreen(
    settings: BirdySettings,
    prefs: PreferencesRepository,
    status: Pair<String, String?>,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val previewFile = File(context.filesDir, "last_collage.jpg")
    var previewTick by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Birdy", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
        Text(status.first.ifBlank { "Ready" }, style = MaterialTheme.typography.bodySmall)
        status.second?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (previewFile.exists()) {
            val bmp = remember(previewTick, previewFile.lastModified()) {
                BitmapFactory.decodeFile(previewFile.absolutePath)
            }
            bmp?.let {
                Card(Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Last collage",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        OutlinedTextField(
            value = draft.postcode,
            onValueChange = { draft = draft.copy(postcode = it) },
            label = { Text("UK postcode") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("Radius: ${draft.radiusKm} km")
        Slider(
            value = draft.radiusKm.toFloat(),
            onValueChange = { draft = draft.copy(radiusKm = it.toInt()) },
            valueRange = 5f..80f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.days.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { draft = draft.copy(days = it) } },
                label = { Text("Days") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.hours.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { draft = draft.copy(hours = it) } },
                label = { Text("Hours (0=use days)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Text("Refresh every ${draft.refreshHours}h (WorkManager min 15m; Doze may delay)")
        Slider(
            value = draft.refreshHours.toFloat(),
            onValueChange = { draft = draft.copy(refreshHours = it.toInt().coerceAtLeast(1)) },
            valueRange = 1f..48f,
        )
        OutlinedTextField(
            value = draft.bgColor,
            onValueChange = { draft = draft.copy(bgColor = it) },
            label = { Text("bg_color (cream / #hex)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("min_confidence: ${"%.2f".format(draft.minConfidence)}")
        Slider(
            value = draft.minConfidence,
            onValueChange = { draft = draft.copy(minConfidence = it) },
            valueRange = 0f..1f,
        )
        OutlinedTextField(
            value = draft.titleText,
            onValueChange = { draft = draft.copy(titleText = it) },
            label = { Text("Title text") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        CheckRow("Show title", draft.showTitle) { draft = draft.copy(showTitle = it) }
        CheckRow("Show labels", draft.showLabels) { draft = draft.copy(showLabels = it) }
        CheckRow("Set home wallpaper", draft.setHome) { draft = draft.copy(setHome = it) }
        CheckRow("Set lock wallpaper (API 24+; OEM caveats)", draft.setLock) { draft = draft.copy(setLock = it) }
        OutlinedTextField(
            value = draft.birdnetUrl,
            onValueChange = { draft = draft.copy(birdnetUrl = it) },
            label = { Text("BirdNET-Pi URL (optional, unused in v1)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        message?.let { Text(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            prefs.save(draft)
                            RefreshWorker.enqueue(context, draft.refreshHours)
                            val msg = withContext(Dispatchers.IO) {
                                runRefresh(context, prefs, draft)
                            }
                            message = msg
                            previewTick++
                        } catch (e: Exception) {
                            message = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Working…" else "Save & refresh now") }
            TextButton(onClick = {
                scope.launch { prefs.save(draft); RefreshWorker.enqueue(context, draft.refreshHours); message = "Saved" }
            }) { Text("Save only") }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Privacy: on-device prefs only. Network calls to BirdWeather + postcodes.io. " +
                "No ads. Uninstall deletes local data. Sideload v1 — not Play-listed yet.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

suspend fun runRefresh(
    context: android.content.Context,
    prefs: PreferencesRepository,
    settings: BirdySettings,
): String {
    val api = BirdWeatherApi()
    val nearby = api.fetchForSettings(settings)
    val bmp = CollageRenderer(context).render(nearby.species, settings)
    File(context.filesDir, "last_collage.jpg").outputStream().use {
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
    }
    val wall = WallpaperApplier(context).apply(bmp, settings.setHome, settings.setLock)
    val msg = "OK ${nearby.species.size} species · ${nearby.placeName} · ${wall.message}"
    prefs.setStatus(msg)
    return msg
}
