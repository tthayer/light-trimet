package com.tthayer.lighttrimet

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val zone: ZoneId = ZoneId.of("America/Los_Angeles")

fun minutesUntil(timeMillis: Long, nowMillis: Long): Int =
    ((timeMillis - nowMillis) / 60_000.0).roundToInt()

/** Short "when" label for an arrival row: "Due", "4 min", a clock time, or a status word. */
fun arrivalWhen(arrival: ArrivalDto, nowMillis: Long): String {
    if (arrival.isCanceled) return "Canceled"
    val time = arrival.timeMillis()
    if (!arrival.isRealTime || arrival.showMilesAway || time == null) {
        return time?.let { formatClock(it) } ?: "—"
    }
    val minutes = minutesUntil(time, nowMillis)
    return when {
        minutes <= 0 -> "Due"
        minutes == 1 -> "1 min"
        else -> "$minutes min"
    }
}

/** Wall-clock label in TriMet's local timezone. */
fun formatClock(timeMillis: Long): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), zone).format(clockFormatter)

fun formatDistance(miles: Double): String {
    if (miles <= 0.0) return ""
    return if (miles < 0.1) "${(miles * 5280).roundToInt()} ft" else "${round1(miles)} mi"
}

fun formatDuration(minutes: Int): String = when {
    minutes <= 0 -> "—"
    minutes < 60 -> "$minutes min"
    else -> "${minutes / 60} hr ${minutes % 60} min"
}

fun tripLegHeadline(leg: TripLeg): String = when {
    leg.isTransit -> leg.routeNumber?.let { "$it · ${leg.routeName.orEmpty()}" } ?: leg.mode
    else -> "Walk"
}

fun tripLegDetail(leg: TripLeg): String = when {
    leg.isTransit -> "to ${leg.toDescription}"
    else -> listOfNotNull(
        formatDistance(leg.distanceMiles).ifBlank { null },
        formatDuration(leg.durationMinutes).takeIf { it != "—" },
    ).joinToString(" · ").ifBlank { "Walk" }
}

private fun round1(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
