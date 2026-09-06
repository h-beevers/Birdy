package com.henrybeevers.birdy.data

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
    // Optional BirdNET-Pi — unused in primary BirdWeather path
    val birdnetUrl: String = "",
)
