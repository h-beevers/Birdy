package com.henrybeevers.birdy.data

enum class DetectionSource {
    BIRDNET_PI,
    BIRDWEATHER,
}

data class SpeciesDetection(
    val name: String,
    val scientific: String = "",
    val thumb: String = "",
    val credit: String = "",
    val license: String = "",
    val station: String = "a nearby station",
    val timestampIso: String? = null,
    val score: Double? = null,
    val count: Int = 1,
)

data class NearbyResult(
    val species: List<SpeciesDetection>,
    val detectionCount: Int,
    val speciesCount: Int,
    val stationCount: Int,
    val placeName: String,
    val lat: Double,
    val lon: Double,
    val source: DetectionSource = DetectionSource.BIRDWEATHER,
    val sourceStatus: String = "Using BirdWeather (fallback)",
)

data class BirdySettings(
    val postcode: String = "HG3 1AP",
    val radiusKm: Int = 20,
    val days: Int = 1,
    val hours: Int = 0, // 0 = use days
    val refreshHours: Int = 6,
    val bgColor: String = "#f4ede0",
    val minConfidence: Float = 0f,
    val showTitle: Boolean = true,
    val titleText: String = "Garden Visitors",
    val showLabels: Boolean = false,
    val setHome: Boolean = true,
    val setLock: Boolean = true,
    val firstRunDone: Boolean = false,
    /** BirdNET-Pi base URL; when non-blank, preferred over BirdWeather. */
    val birdnetUrl: String = "",
    /**
     * Fetch missing plates from the GB illustration pack (AvianAssets) as
     * species turn up, instead of falling back to a BirdWeather photo. The
     * pack is downloaded on this device, never bundled in the APK.
     */
    val downloadGbPack: Boolean = true,
)
