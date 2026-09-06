package com.henrybeevers.birdy.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * BirdNET-Pi detection client per docs/birdnet-pi-detections-contract.md.
 *
 * Stock Nachtzuster main ships HTML AJAX only (PR #575 JSON is unmerged).
 * Probe order (Jill / Jack guidance — stock-first):
 *  1. GET /todays_detections.php?ajax_detections=true&hard_limit=N — HTML rows
 *  2. Optional GET /api/v1/detections/recent?limit=N if present (200 JSON)
 *  3. Caller falls back to BirdWeather when empty / 403 / off-LAN
 */
class BirdNetPiApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    companion object {
        const val USER_AGENT = "Birdy-Android/1.0"
        const val DEFAULT_HARD_LIMIT = 300
        private val ROW_RE = Pattern.compile("<tr[^>]*>([\\s\\S]*?)</tr>", Pattern.CASE_INSENSITIVE)
        private val TD_RE = Pattern.compile("<td[^>]*>([\\s\\S]*?)</td>", Pattern.CASE_INSENSITIVE)
        private val TIME_RE = Pattern.compile("\\b(\\d{2}:\\d{2}:\\d{2})\\b")
        private val A2_RE = Pattern.compile(
            "<a[^>]*class=[\"']a2[\"'][^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE,
        )
        private val ITALIC_RE = Pattern.compile("<i>([^<]+)</i>", Pattern.CASE_INSENSITIVE)
        private val PCT_RE = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*%")
        private val AUDIO_RE = Pattern.compile(
            "data-audio-src=['\"]([^'\"]+)['\"]|<audio[^>]*\\bsrc=['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE,
        )
        private val ISO_DATE_RE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})")
        private val BUTTON_SPECIES_RE = Pattern.compile(
            "<button[^>]*name=[\"']species[\"'][^>]*value=[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE,
        )
    }

    fun normalizeBase(url: String): String = url.trim().trimEnd('/')

    /**
     * Fetch today's detections from a BirdNET-Pi base URL.
     * @return null if unreachable / empty / unparseable (caller should fall back)
     */
    fun fetchRecent(baseUrl: String, limit: Int = DEFAULT_HARD_LIMIT): NearbyResult? {
        val base = normalizeBase(baseUrl)
        if (base.isBlank()) return null
        val htmlLimit = limit.coerceAtLeast(1)
        // PR #575 caps limit at 100
        val jsonLimit = htmlLimit.coerceAtMost(100)

        // 1) Stock main: HTML AJAX (hard_limit = LIMIT N, today localtime)
        try {
            val html = getText(
                "$base/todays_detections.php?ajax_detections=true&hard_limit=$htmlLimit",
            )
            if (html != null && !looksDenied(html)) {
                val detections = parseAjaxHtml(html, base)
                if (detections.isNotEmpty()) {
                    return toNearby(detections, base)
                }
            }
        } catch (_: Exception) {
            // try optional JSON
        }

        // 2) Optional JSON if present (Henry fork / unmerged PR #575)
        try {
            val jsonText = getText("$base/api/v1/detections/recent?limit=$jsonLimit")
            val startsJson = jsonText != null && (
                jsonText.trimStart().startsWith("{") || jsonText.trimStart().startsWith("[")
            )
            if (startsJson && !looksDenied(jsonText!!)) {
                val detections = parseRecentJson(jsonText)
                if (detections.isNotEmpty()) {
                    return toNearby(detections, base)
                }
            }
        } catch (_: Exception) {
            // caller falls back to BirdWeather
        }

        return null
    }

    private fun looksDenied(body: String): Boolean {
        val t = body.trim()
        return t.equals("Access Denied", ignoreCase = true) ||
            t.contains("403 Forbidden", ignoreCase = true)
    }

    private fun getText(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/json,*/*")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    internal fun parseAjaxHtml(html: String, base: String): List<SpeciesDetection> {
        if (html.contains("No Detections For Today", ignoreCase = true)) return emptyList()
        val out = mutableListOf<SpeciesDetection>()
        val rowMatcher = ROW_RE.matcher(html)
        while (rowMatcher.find()) {
            val row = rowMatcher.group(1) ?: continue
            val audio = AUDIO_RE.matcher(row).let { m ->
                if (m.find()) (m.group(1) ?: m.group(2) ?: "") else ""
            }
            // Skip summary / non-detection rows (no audio clip)
            if (audio.isBlank()) continue

            val cells = mutableListOf<String>()
            val tdMatcher = TD_RE.matcher(row)
            while (tdMatcher.find()) {
                cells.add(stripTags(tdMatcher.group(1) ?: "").trim())
            }

            var time = ""
            var comName = ""
            var sciName = ""
            var confRaw = ""

            if (cells.size >= 4) {
                time = cells[0]
                comName = cells[1]
                sciName = cells[2]
                confRaw = cells[3]
            } else {
                time = TIME_RE.matcher(row).let { if (it.find()) it.group(1).orEmpty() else "" }
                comName = A2_RE.matcher(row).let { if (it.find()) it.group(1).orEmpty().trim() else "" }
                if (comName.isBlank()) {
                    comName = BUTTON_SPECIES_RE.matcher(row).let {
                        if (it.find()) it.group(1).orEmpty().trim() else ""
                    }
                }
                sciName = ITALIC_RE.matcher(row).let { if (it.find()) it.group(1).orEmpty().trim() else "" }
                confRaw = PCT_RE.matcher(row).let { if (it.find()) it.group(1).orEmpty() + "%" else "0" }
            }

            if (!Regex("^\\d{2}:\\d{2}:\\d{2}$").matches(time)) continue
            if (comName.isBlank() || comName == "..." || comName == "—") continue

            val confNum = confRaw.replace("%", "").trim().toDoubleOrNull() ?: 0.0
            val confidence = if (confNum > 1.0) confNum / 100.0 else confNum

            val date = ISO_DATE_RE.matcher(audio).let {
                if (it.find()) it.group(1) else java.time.LocalDate.now().toString()
            }
            val ts = "${date}T$time"

            out.add(
                SpeciesDetection(
                    name = comName,
                    scientific = sciName,
                    station = "BirdNET-Pi",
                    timestampIso = ts,
                    score = confidence,
                    count = 1,
                ),
            )
        }
        return out
    }

    internal fun parseRecentJson(text: String): List<SpeciesDetection> {
        val trimmed = text.trim()
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val obj = JSONObject(trimmed)
                when {
                    obj.has("detections") -> {
                        val d = obj.get("detections")
                        when (d) {
                            is JSONArray -> d
                            is JSONObject -> d.optJSONArray("nodes") ?: JSONArray()
                            else -> JSONArray()
                        }
                    }
                    obj.has("data") -> {
                        val data = obj.get("data")
                        when (data) {
                            is JSONArray -> data
                            is JSONObject -> data.optJSONArray("detections")
                                ?: data.optJSONArray("nodes")
                                ?: JSONArray()
                            else -> JSONArray()
                        }
                    }
                    obj.has("nodes") -> obj.getJSONArray("nodes")
                    else -> JSONArray()
                }
            }
        }
        val out = mutableListOf<SpeciesDetection>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val species = n.optJSONObject("species")
            // Contract PR JSON: species / sci_name / confidence / date / time
            val com = firstNonBlank(
                n.optString("species"),
                n.optString("Com_Name"),
                n.optString("com_name"),
                n.optString("commonName"),
                n.optString("common_name"),
                species?.optString("commonName"),
                species?.optString("Com_Name"),
            ) ?: continue
            val sci = firstNonBlank(
                n.optString("sci_name"),
                n.optString("Sci_Name"),
                n.optString("scientificName"),
                n.optString("scientific_name"),
                species?.optString("scientificName"),
                species?.optString("Sci_Name"),
            ) ?: ""
            val conf = when {
                n.has("Confidence") && !n.isNull("Confidence") -> n.optDouble("Confidence")
                n.has("confidence") && !n.isNull("confidence") -> n.optDouble("confidence")
                n.has("score") && !n.isNull("score") -> n.optDouble("score")
                else -> null
            }?.let { if (it > 1.0) it / 100.0 else it }
            val date = firstNonBlank(n.optString("Date"), n.optString("date"))
            val time = firstNonBlank(n.optString("Time"), n.optString("time"))
            val ts = firstNonBlank(
                n.optString("timestamp"),
                n.optString("detected_at"),
                if (date != null && time != null) "${date}T$time" else null,
            )
            out.add(
                SpeciesDetection(
                    name = com,
                    scientific = sci,
                    station = "BirdNET-Pi",
                    timestampIso = ts,
                    score = conf,
                    count = 1,
                ),
            )
        }
        return out
    }

    private fun toNearby(raw: List<SpeciesDetection>, base: String): NearbyResult {
        val counts = mutableMapOf<String, Int>()
        for (d in raw) counts[d.name] = (counts[d.name] ?: 0) + 1
        // Most recent per species
        val best = linkedMapOf<String, SpeciesDetection>()
        for (d in raw.sortedByDescending { it.timestampIso ?: "" }) {
            if (!best.containsKey(d.name)) best[d.name] = d
        }
        val species = best.values
            .map { it.copy(count = counts[it.name] ?: 1) }
            .sortedByDescending { it.timestampIso ?: "" }
            .take(BirdWeatherApi.SPECIES_CAP_COLLAGE)
        val hostLabel = try {
            java.net.URI(base).host ?: base
        } catch (_: Exception) {
            base
        }
        return NearbyResult(
            species = species,
            detectionCount = raw.size,
            speciesCount = species.size,
            stationCount = 1,
            placeName = hostLabel,
            lat = BirdWeatherApi.FALLBACK_LAT,
            lon = BirdWeatherApi.FALLBACK_LON,
            source = DetectionSource.BIRDNET_PI,
            sourceStatus = "Using BirdNET-Pi",
        )
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
