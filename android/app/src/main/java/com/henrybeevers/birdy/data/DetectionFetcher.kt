package com.henrybeevers.birdy.data

/**
 * Prefer BirdNET-Pi when [BirdySettings.birdnetUrl] is non-blank
 * (see docs/birdnet-pi-detections-contract.md).
 * On Pi failure / empty / off-LAN (403, timeout, parse miss): BirdWeather.
 */
class DetectionFetcher(
    private val pi: BirdNetPiApi = BirdNetPiApi(),
    private val birdWeather: BirdWeatherApi = BirdWeatherApi(),
) {
    fun fetch(settings: BirdySettings): NearbyResult {
        val piUrl = settings.birdnetUrl.trim()
        if (piUrl.isNotBlank()) {
            try {
                val fromPi = pi.fetchRecent(piUrl)
                if (fromPi != null && fromPi.species.isNotEmpty()) {
                    return applyConfidence(fromPi, settings.minConfidence)
                }
            } catch (_: Exception) {
                // fall through to BirdWeather
            }
        }
        val bw = birdWeather.fetchForSettings(settings)
        return bw.copy(
            source = DetectionSource.BIRDWEATHER,
            sourceStatus = if (piUrl.isNotBlank()) {
                "Using BirdWeather (fallback)"
            } else {
                "Using BirdWeather"
            },
        )
    }

    private fun applyConfidence(result: NearbyResult, minConfidence: Float): NearbyResult {
        if (minConfidence <= 0f) return result
        val kept = result.species.filter { s ->
            val score = s.score
            score == null || score >= minConfidence
        }
        if (kept.isEmpty()) return result // never empty flock solely for threshold
        return result.copy(species = kept, speciesCount = kept.size)
    }
}
