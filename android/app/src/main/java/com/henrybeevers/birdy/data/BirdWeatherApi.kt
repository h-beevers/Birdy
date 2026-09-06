package com.henrybeevers.birdy.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.max

/**
 * Kotlin port of docs/android-shared-contract.md (from birdweather_local.py).
 * BirdWeather public GraphQL + postcodes.io — no API key.
 */
class BirdWeatherApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        const val GRAPHQL = "https://app.birdweather.com/graphql"
        const val POSTCODES = "https://api.postcodes.io/postcodes/%s"
        const val USER_AGENT = "avianvisitors-local-preview/1.0"
        const val SPECIES_CAP_COLLAGE = 60
        const val SPECIES_CAP_LIST = 40
        const val FIRST = 300
        const val FALLBACK_LAT = 53.93
        const val FALLBACK_LON = -1.45

        private val DETECTIONS_QUERY = """
            query recentNearby(${'$'}ne: InputLocation, ${'$'}sw: InputLocation, ${'$'}period: InputDuration, ${'$'}first: Int) {
              detections(ne: ${'$'}ne, sw: ${'$'}sw, period: ${'$'}period, first: ${'$'}first) {
                totalCount
                speciesCount
                nodes {
                  timestamp
                  score
                  species {
                    commonName
                    scientificName
                    thumbnailUrl
                    imageCredit
                    imageLicense
                  }
                  station {
                    name
                    location
                  }
                }
              }
              stations(ne: ${'$'}ne, sw: ${'$'}sw, first: 50) {
                totalCount
                nodes {
                  name
                  type
                  latestDetectionAt
                }
              }
            }
        """.trimIndent()
    }

    data class LatLonPlace(val lat: Double, val lon: Double, val place: String)

    fun lookupPostcode(postcode: String): LatLonPlace {
        val trimmed = postcode.trim()
        if (trimmed.isEmpty()) {
            return LatLonPlace(FALLBACK_LAT, FALLBACK_LON, "fallback")
        }
        return try {
            val url = POSTCODES.format(URLEncoder.encode(trimmed, "UTF-8"))
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("postcodes.io HTTP ${resp.code}")
                val body = resp.body?.string() ?: throw RuntimeException("Empty postcodes.io body")
                val result = JSONObject(body).getJSONObject("result")
                val place = sequenceOf("admin_ward", "parish", "admin_district")
                    .mapNotNull { key -> result.optString(key).takeIf { it.isNotBlank() } }
                    .firstOrNull() ?: trimmed
                LatLonPlace(result.getDouble("latitude"), result.getDouble("longitude"), place)
            }
        } catch (_: Exception) {
            LatLonPlace(FALLBACK_LAT, FALLBACK_LON, trimmed)
        }
    }

    fun boundingBox(lat: Double, lon: Double, radiusKm: Int): Pair<JSONObject, JSONObject> {
        val latDelta = radiusKm / 111.0
        val lonDelta = radiusKm / (111.0 * max(0.1, cos(Math.toRadians(lat))))
        val ne = JSONObject().put("lat", lat + latDelta).put("lon", lon + lonDelta)
        val sw = JSONObject().put("lat", lat - latDelta).put("lon", lon - lonDelta)
        return ne to sw
    }

    fun fetchNearby(
        lat: Double,
        lon: Double,
        radiusKm: Int,
        periodCount: Int,
        periodUnit: String,
        first: Int = FIRST,
    ): JSONObject {
        val (ne, sw) = boundingBox(lat, lon, radiusKm)
        val variables = JSONObject()
            .put("ne", ne)
            .put("sw", sw)
            .put("period", JSONObject().put("count", periodCount).put("unit", periodUnit))
            .put("first", first)
        val payload = JSONObject()
            .put("query", DETECTIONS_QUERY)
            .put("variables", variables)
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(GRAPHQL)
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("BirdWeather HTTP ${resp.code}")
            val text = resp.body?.string() ?: throw RuntimeException("Empty BirdWeather body")
            val json = JSONObject(text)
            if (json.has("errors")) {
                throw RuntimeException("BirdWeather errors: ${json.getJSONArray("errors")}")
            }
            return json.getJSONObject("data")
        }
    }

    fun filterByConfidence(nodes: JSONArray, minConfidence: Float): List<JSONObject> {
        if (minConfidence <= 0f) {
            return (0 until nodes.length()).map { nodes.getJSONObject(it) }
        }
        val kept = mutableListOf<JSONObject>()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (!node.has("score") || node.isNull("score")) {
                kept.add(node)
                continue
            }
            try {
                if (node.getDouble("score") >= minConfidence) kept.add(node)
            } catch (_: Exception) {
                kept.add(node)
            }
        }
        return kept
    }

    /** Count occurrences of each commonName on post-confidence nodes. */
    fun countSpecies(nodes: List<JSONObject>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (node in nodes) {
            val name = node.optJSONObject("species")?.optString("commonName")
                ?.takeIf { it.isNotBlank() } ?: continue
            counts[name] = (counts[name] ?: 0) + 1
        }
        return counts
    }

    /** Keep most recent detection per commonName; sort newest first. */
    fun dedupeSpecies(nodes: List<JSONObject>): List<SpeciesDetection> {
        data class Acc(
            var name: String,
            var scientific: String,
            var thumb: String,
            var credit: String,
            var license: String,
            var station: String,
            var ts: String?,
            var score: Double?,
        )
        val best = linkedMapOf<String, Acc>()
        for (node in nodes) {
            val species = node.optJSONObject("species") ?: continue
            val name = species.optString("commonName").takeIf { it.isNotBlank() } ?: continue
            val ts = node.optString("timestamp").takeIf { it.isNotBlank() }
            val station = node.optJSONObject("station")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: "a nearby station"
            val score = if (node.has("score") && !node.isNull("score")) node.optDouble("score") else null
            val existing = best[name]
            if (existing == null || (ts != null && (existing.ts == null || ts > existing.ts!!))) {
                best[name] = Acc(
                    name = name,
                    scientific = species.optString("scientificName"),
                    thumb = species.optString("thumbnailUrl"),
                    credit = species.optString("imageCredit"),
                    license = species.optString("imageLicense"),
                    station = station,
                    ts = ts,
                    score = score,
                )
            }
        }
        return best.values
            .sortedByDescending { it.ts ?: "" }
            .map {
                SpeciesDetection(
                    name = it.name,
                    scientific = it.scientific,
                    thumb = it.thumb,
                    credit = it.credit,
                    license = it.license,
                    station = it.station,
                    timestampIso = it.ts,
                    score = it.score,
                )
            }
    }

    fun fetchForSettings(settings: BirdySettings): NearbyResult {
        val loc = lookupPostcode(settings.postcode)
        val useHours = settings.hours > 0
        val periodCount = if (useHours) settings.hours else settings.days.coerceAtLeast(1)
        val periodUnit = if (useHours) "hour" else "day"
        val data = fetchNearby(loc.lat, loc.lon, settings.radiusKm, periodCount, periodUnit)
        val detections = data.getJSONObject("detections")
        val nodesArr = detections.getJSONArray("nodes")
        val filtered = filterByConfidence(nodesArr, settings.minConfidence)
        val counts = countSpecies(filtered)
        val species = dedupeSpecies(filtered)
            .take(SPECIES_CAP_COLLAGE)
            .map { s -> s.copy(count = counts[s.name] ?: 1) }
        val stations = data.optJSONObject("stations")
        return NearbyResult(
            species = species,
            detectionCount = detections.optInt("totalCount", filtered.size),
            speciesCount = detections.optInt("speciesCount", species.size),
            stationCount = stations?.optInt("totalCount", 0) ?: 0,
            placeName = loc.place,
            lat = loc.lat,
            lon = loc.lon,
        )
    }
}
