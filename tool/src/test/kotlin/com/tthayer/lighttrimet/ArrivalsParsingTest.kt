package com.tthayer.lighttrimet

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrivalsParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun mapsArrivalsAndStops() {
        val raw = """
            {"resultSet":{"queryTime":1,
            "location":[{"id":6849,"desc":"SE 17th & Center","dir":"Northbound",
              "lat":45.49,"lng":-122.64,"passengerCode":"E"}],
            "arrival":[{"route":17,"dir":1,"locid":6849,"status":"estimated","estimated":1000,
              "scheduled":900,"shortSign":"17 To Portland","fullSign":"17  Holgate to Portland",
              "routeSubType":"Bus","detoured":false,"departed":true,"unexpectedField":42}]}}
        """.trimIndent()

        val result = json.decodeFromString<ArrivalsDto>(raw).toResult()

        assertNull(result.error)
        assertEquals("SE 17th & Center", result.stops.getValue(6849L).desc)
        assertEquals("Northbound", result.stops.getValue(6849L).direction)

        val arrivals = result.arrivals.getValue(6849L)
        assertEquals(1, arrivals.size)
        assertEquals(17, arrivals.single().route)
        assertEquals(1000L, arrivals.single().estimated)
        assertFalse(arrivals.single().isCanceled)
        assertTrue(arrivals.single().isRealTime)
    }

    @Test
    fun surfacesTriMetError() {
        val raw = """{"resultSet":{"error":{"content":"Location id not found 99999999"}}}"""

        val result = json.decodeFromString<ArrivalsDto>(raw).toResult()

        assertEquals("Location id not found 99999999", result.error)
        assertTrue(result.stops.isEmpty())
        assertTrue(result.arrivals.isEmpty())
    }
}
