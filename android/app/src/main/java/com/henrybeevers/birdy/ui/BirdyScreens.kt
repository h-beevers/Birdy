package com.henrybeevers.birdy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.henrybeevers.birdy.collage.ClassicPlatePack
import com.henrybeevers.birdy.collage.GbIllustrationPack
import com.henrybeevers.birdy.collage.IllustrationPack
import com.henrybeevers.birdy.collage.PackIndex
import com.henrybeevers.birdy.data.BirdySettings
import com.henrybeevers.birdy.data.DetectionFetcher
import com.henrybeevers.birdy.data.PreferencesRepository
import com.henrybeevers.birdy.work.RefreshWorker
import com.henrybeevers.birdy.work.Refresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** Scrolling page that keeps clear of the status bar, nav bar and keyboard. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
fun FirstRunScreen(settings: BirdySettings, prefs: PreferencesRepository) {
    var postcode by remember { mutableStateOf(settings.postcode) }
    var radius by remember { mutableStateOf(settings.radiusKm.toFloat()) }
    var artStyle by remember { mutableStateOf(settings.artStyle) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Page {
        Text("Welcome to Birdy", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
        Text(
            "Birdy turns the birds heard near you into a wallpaper. It looks up " +
                "recent detections around a UK postcode on BirdWeather, draws them " +
                "as a flock on this phone, and keeps your home and lock screen up " +
                "to date. No account, no ads; your settings stay on this phone.",
        )
        OutlinedTextField(
            value = postcode,
            onValueChange = { postcode = it },
            label = { Text("UK postcode") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("Search within ${radius.toInt()} km")
        Slider(value = radius, onValueChange = { radius = it }, valueRange = 5f..80f)
        SectionTitle("Artwork")
        ArtStyleChooser(artStyle) { artStyle = it }
        Button(
            enabled = !busy && postcode.isNotBlank(),
            onClick = {
                busy = true
                scope.launch {
                    val s = settings.copy(
                        postcode = postcode.trim(),
                        radiusKm = radius.toInt(),
                        artStyle = artStyle,
                        firstRunDone = true,
                    )
                    try {
                        withContext(Dispatchers.IO) { Refresher.run(context, prefs, s) }
                    } catch (e: Exception) {
                        // Carry on to the main screen, which shows this, rather
                        // than trapping the user on the welcome page.
                        prefs.setStatus("First refresh failed", e.message ?: "Unknown error")
                    }
                    RefreshWorker.enqueue(context, s.refreshHours)
                    prefs.save(s)
                    busy = false
                }
            },
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Build my wallpaper")
            }
        }
        if (busy) {
            Text(
                "Fetching detections and artwork. The first run can take a minute.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Everything here can be changed later. Some phones (Samsung, Xiaomi " +
                "and others) only let an app change the home screen, not the lock screen.",
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
    var daysText by remember(settings) { mutableStateOf(settings.days.toString()) }
    var hoursText by remember(settings) {
        mutableStateOf(if (settings.hours > 0) settings.hours.toString() else "")
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val previewFile = Refresher.previewFile(context)

    /** The draft with the two free-text number fields folded back in. */
    fun collected(): BirdySettings = draft.copy(
        days = daysText.trim().toIntOrNull()?.coerceIn(1, 30) ?: 1,
        hours = hoursText.trim().toIntOrNull()?.coerceIn(0, 168) ?: 0,
    )

    fun refreshNow() {
        busy = true
        message = "Working…"
        scope.launch {
            try {
                val s = collected()
                prefs.save(s)
                RefreshWorker.enqueue(context, s.refreshHours)
                message = withContext(Dispatchers.IO) { Refresher.run(context, prefs, s) }
            } catch (e: Exception) {
                message = "Couldn't refresh: ${e.message ?: "unknown error"}"
            } finally {
                busy = false
            }
        }
    }

    Page {
        Text("Birdy", style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
        Text(status.first.ifBlank { "Ready" }, style = MaterialTheme.typography.bodySmall)
        status.second?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (previewFile.exists()) {
            // Keyed on the file's timestamp, so a finished refresh (from the
            // button or the background worker) shows up straight away.
            val preview = remember(previewFile.lastModified()) { loadPreview(previewFile.absolutePath) }
            preview?.let {
                Card(Modifier.fillMaxWidth()) {
                    // The whole wallpaper, at its own shape: cropping it to a
                    // strip hid exactly the framing this preview is for.
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Current wallpaper",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .padding(8.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        Button(enabled = !busy, onClick = { refreshNow() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Working…" else "Save & refresh wallpaper now")
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        SectionTitle("Where and when")
        OutlinedTextField(
            value = draft.postcode,
            onValueChange = { draft = draft.copy(postcode = it) },
            label = { Text("UK postcode") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("Search within ${draft.radiusKm} km")
        Slider(
            value = draft.radiusKm.toFloat(),
            onValueChange = { draft = draft.copy(radiusKm = it.toInt()) },
            valueRange = 5f..80f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = daysText,
                onValueChange = { v -> daysText = v.filter { it.isDigit() }.take(2) },
                label = { Text("Days back") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = hoursText,
                onValueChange = { v -> hoursText = v.filter { it.isDigit() }.take(3) },
                label = { Text("Or hours back") },
                placeholder = { Text("blank = use days") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Text("Only birds the detector is at least ${(draft.minConfidence * 100).toInt()}% sure of")
        Slider(
            value = draft.minConfidence,
            onValueChange = { draft = draft.copy(minConfidence = it) },
            valueRange = 0f..1f,
        )

        SectionTitle("Artwork")
        ArtStyleChooser(draft.artStyle) { draft = draft.copy(artStyle = it) }
        PackCard(
            pack = remember { ClassicPlatePack(context) },
            title = "Classic plates",
            blurb = "Hand-coloured 1800s plates (Gould, von Wright, Audubon and others) " +
                "from the Fugleramme project, ${ClassicPlatePack.LICENCE}. Downloaded to " +
                "this phone as birds turn up, not bundled in the app.",
            allNote = "\"Get all\" downloads about 160 MB (kept as roughly 35 MB). Wi-Fi recommended.",
            enabled = draft.downloadClassicPack,
            onEnabledChange = { draft = draft.copy(downloadClassicPack = it) },
            settings = draft,
        )
        PackCard(
            pack = remember { GbIllustrationPack(context) },
            title = "GB illustration pack",
            blurb = "Modern UK illustrations from AvianAssets, used alongside Birdy's own " +
                "art. Downloaded to this phone, not bundled in the app, so the artwork " +
                "stays under its own terms.",
            allNote = "\"Get all\" downloads about 110 MB (kept as roughly 12 MB). Wi-Fi recommended.",
            enabled = draft.downloadGbPack,
            onEnabledChange = { draft = draft.copy(downloadGbPack = it) },
            settings = draft,
        )

        SectionTitle("Look")
        CheckRow("Show a title", draft.showTitle) { draft = draft.copy(showTitle = it) }
        if (draft.showTitle) {
            OutlinedTextField(
                value = draft.titleText,
                onValueChange = { draft = draft.copy(titleText = it) },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        CheckRow("Name each bird", draft.showLabels) { draft = draft.copy(showLabels = it) }
        Text("Background colour")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { draft = draft.copy(bgColor = "#f4ede0") }) { Text("Cream") }
            TextButton(onClick = { draft = draft.copy(bgColor = "#c5d8e8") }) { Text("Blue") }
            TextButton(onClick = { draft = draft.copy(bgColor = "#d6e4d2") }) { Text("Green") }
        }
        OutlinedTextField(
            value = draft.bgColor,
            onValueChange = { draft = draft.copy(bgColor = it) },
            label = { Text("Or any colour (#rrggbb)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        SectionTitle("Wallpaper")
        CheckRow("Set the home screen", draft.setHome) { draft = draft.copy(setHome = it) }
        CheckRow("Set the lock screen", draft.setLock) { draft = draft.copy(setLock = it) }
        Text("Refresh every ${draft.refreshHours} h")
        Slider(
            value = draft.refreshHours.toFloat(),
            onValueChange = { draft = draft.copy(refreshHours = it.toInt().coerceAtLeast(1)) },
            valueRange = 1f..48f,
        )
        Text(
            "Refreshes run in the background when there is a connection. Android " +
                "may delay them to save battery.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("Your own BirdNET-Pi (optional)")
        OutlinedTextField(
            value = draft.birdnetUrl,
            onValueChange = { draft = draft.copy(birdnetUrl = it) },
            label = { Text("BirdNET-Pi address") },
            placeholder = { Text("http://192.168.1.159") },
            supportingText = {
                Text(
                    "Used first when set, so the wallpaper shows your own garden. " +
                        "Birdy falls back to BirdWeather when it can't be reached, " +
                        "for example away from home.",
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        TextButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    val s = collected()
                    prefs.save(s)
                    RefreshWorker.enqueue(context, s.refreshHours)
                    message = "Saved. The wallpaper updates at the next refresh."
                }
            },
        ) { Text("Save without refreshing") }

        Spacer(Modifier.height(16.dp))
        Text(
            "Privacy: settings stay on this phone. Birdy contacts BirdWeather and " +
                "postcodes.io, your BirdNET-Pi if you set one, and GitHub when it " +
                "downloads artwork. No ads, no tracking. Uninstalling removes everything.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Half-size decode: the preview never needs the wallpaper's full resolution. */
private fun loadPreview(path: String): Bitmap? = try {
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
} catch (_: Exception) {
    null
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Serif,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** Which artwork to prefer; the other still fills the gaps. */
@Composable
private fun ArtStyleChooser(selected: String, onSelect: (String) -> Unit) {
    Column {
        RadioRow(
            label = "Birdy illustrations",
            detail = "The modern art bundled with the app, topped up from the GB pack.",
            selected = selected != BirdySettings.ART_CLASSIC,
            onClick = { onSelect(BirdySettings.ART_BIRDY) },
        )
        RadioRow(
            label = "Classic plates",
            detail = "Hand-coloured 1800s natural-history plates, downloaded as needed.",
            selected = selected == BirdySettings.ART_CLASSIC,
            onClick = { onSelect(BirdySettings.ART_CLASSIC) },
        )
    }
}

@Composable
private fun RadioRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A downloadable artwork pack is fetched on the device rather than bundled in
 * the APK (see [IllustrationPack]), so it needs its own bit of UI: what's
 * cached, and a way to fetch or drop it.
 */
@Composable
private fun PackCard(
    pack: IllustrationPack,
    title: String,
    blurb: String,
    allNote: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    settings: BirdySettings,
) {
    val scope = rememberCoroutineScope()
    val total = remember(pack) { pack.species().size }
    var installed by remember(pack) { mutableStateOf(pack.installedCount()) }
    var megabytes by remember(pack) { mutableStateOf(pack.bytesOnDisk() / 1_000_000f) }
    var packBusy by remember { mutableStateOf(false) }
    var packNote by remember { mutableStateOf<String?>(null) }

    fun download(startNote: String, names: () -> List<String>) {
        packBusy = true
        packNote = startNote
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    pack.downloadAll(names()) { p ->
                        packNote = if (p.species.isBlank()) {
                            "Finishing…"
                        } else {
                            "Downloading ${p.done + 1}/${p.total}: ${p.species}"
                        }
                    }
                }
                packNote = result.message
            } catch (e: Exception) {
                packNote = e.message ?: "Download failed"
            } finally {
                installed = pack.installedCount()
                megabytes = pack.bytesOnDisk() / 1_000_000f
                packBusy = false
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(blurb, style = MaterialTheme.typography.bodySmall)
            Text(
                "$installed of $total on this phone (${"%.1f".format(megabytes)} MB).",
                style = MaterialTheme.typography.bodySmall,
            )
            CheckRow("Download plates as new birds turn up", enabled, onEnabledChange)
            packNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !packBusy,
                    onClick = {
                        download("Looking up nearby species…") {
                            DetectionFetcher().fetch(settings).species
                                .map { it.scientific }
                                .filter { it.isNotBlank() }
                        }
                    },
                ) { Text("Get nearby birds") }
                TextButton(
                    enabled = !packBusy,
                    onClick = {
                        download("Downloading the whole pack…") {
                            pack.species().map { PackIndex.displayName(it) }
                        }
                    },
                ) { Text("Get all ($total)") }
                TextButton(
                    enabled = !packBusy && installed > 0,
                    onClick = {
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) { pack.clear() }
                            installed = pack.installedCount()
                            megabytes = pack.bytesOnDisk() / 1_000_000f
                            packNote = "Removed $removed plates"
                        }
                    },
                ) { Text("Clear") }
            }
            Text(allNote, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

/** Kept for callers outside this file; see [Refresher]. */
suspend fun runRefresh(
    context: Context,
    prefs: PreferencesRepository,
    settings: BirdySettings,
): String = Refresher.run(context, prefs, settings)
