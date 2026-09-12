package com.henrybeevers.birdy.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Path encoding for postcodes.io. A miss here silently falls back to
 * Spofforth's lat/lon while still displaying the typed postcode.
 */
class BirdWeatherApiTest {

    @Test
    fun `postcode path encoding uses percent-twenty not plus`() {
        assertEquals("HG3%201AP", BirdWeatherApi.encodePostcodePath("HG3 1AP"))
        assertEquals("OX49%205NU", BirdWeatherApi.encodePostcodePath("OX49 5NU"))
        assertEquals("M1%201AE", BirdWeatherApi.encodePostcodePath("M1 1AE"))
        assertEquals("SW1A%201AA", BirdWeatherApi.encodePostcodePath(" SW1A 1AA "))
    }
}
