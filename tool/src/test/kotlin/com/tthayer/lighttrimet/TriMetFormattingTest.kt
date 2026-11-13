package com.tthayer.lighttrimet

import kotlin.test.Test
import kotlin.test.assertEquals

class TriMetFormattingTest {

    private val now = 1_000_000L

    private fun arrival(
        status: String = "estimated",
        estimated: Long? = null,
        scheduled: Long? = null,
        showMilesAway: Boolean = false,
    ) = ArrivalDto(
        status = status,
        estimated = estimated,
        scheduled = scheduled,
        showMilesAway = showMilesAway,
    )

    @Test
    fun estimatedArrivalCountsDown() {
        assertEquals("4 min", arrivalWhen(arrival(estimated = now + 4 * 60_000), now))
        assertEquals("1 min", arrivalWhen(arrival(estimated = now + 60_000), now))
    }

    @Test
    fun pastArrivalIsDue() {
        assertEquals("Due", arrivalWhen(arrival(estimated = now - 1_000), now))
    }

    @Test
    fun scheduledArrivalShowsClockTime() {
        // 2026-01-01T00:00:00Z is 4:00 PM in Portland (PST).
        val millis = 1_767_225_600_000L
        val arrival = arrival(status = "scheduled", scheduled = millis)
        assertEquals("4:00 PM", arrivalWhen(arrival, now))
    }

    @Test
    fun canceledArrival() {
        assertEquals("Canceled", arrivalWhen(arrival(status = "canceled", estimated = now), now))
    }

    @Test
    fun milesAwayPrefersClockTime() {
        val millis = 1_767_225_600_000L
        val arrival = arrival(estimated = millis, showMilesAway = true)
        assertEquals("4:00 PM", arrivalWhen(arrival, now))
    }

    @Test
    fun clockUsesPortlandTimezone() {
        assertEquals("4:00 PM", formatClock(1_767_225_600_000L))
    }

    @Test
    fun formatsDurations() {
        assertEquals("—", formatDuration(0))
        assertEquals("29 min", formatDuration(29))
        assertEquals("1 hr 30 min", formatDuration(90))
    }

    @Test
    fun formatsDistances() {
        assertEquals("", formatDistance(0.0))
        assertEquals("158 ft", formatDistance(0.03))
        assertEquals("6.5 mi", formatDistance(6.54))
    }

    @Test
    fun minutesUntilRounds() {
        assertEquals(4, minutesUntil(now + 4 * 60_000, now))
        assertEquals(0, minutesUntil(now - 1_000, now))
    }

    @Test
    fun mapsTrafficDirectionToStreetSide() {
        assertEquals("northbound side", streetSide("Northbound"))
        assertEquals("southbound side", streetSide("southbound"))
        assertEquals("eastbound side", streetSide("Eastbound"))
        assertEquals("westbound side", streetSide("Westbound"))
        assertEquals(null, streetSide(""))
        assertEquals(null, streetSide(null))
    }

    @Test
    fun walkHeadingFormatsCompassDirection() {
        assertEquals("head north", walkHeading("north"))
        assertEquals(null, walkHeading(" "))
        assertEquals(null, walkHeading(null))
    }

    @Test
    fun placeLabelDropsCoordinates() {
        assertEquals("Oregon Zoo", placeLabel("Oregon Zoo"))
        assertEquals("", placeLabel("0.0, 0.0"))
        assertEquals("", placeLabel("-122.72, 45.51"))
        assertEquals("", placeLabel("   "))
    }
}
