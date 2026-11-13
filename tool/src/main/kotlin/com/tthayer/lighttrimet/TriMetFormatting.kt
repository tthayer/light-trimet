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

/**
 * Turns a stop's traffic direction (TriMet's `location[@dir]`) into a plain "which side of the
 * street" phrase. Rail platforms that serve both directions report an empty value.
 */
fun streetSide(direction: String?): String? =
    when (direction?.trim()?.lowercase()) {
        "northbound" -> "northbound side"
        "southbound" -> "southbound side"
        "eastbound" -> "eastbound side"
        "westbound" -> "westbound side"
        else -> null
    }

/** Compass direction of a walking leg, e.g. "north" -> "head north". */
fun walkHeading(direction: String?): String? =
    direction?.trim()?.takeIf { it.isNotBlank() }?.let { "head $it" }

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

/**
 * Where a leg takes you. Walking legs name the stop you are walking to (and its ID when TriMet
 * provides one), so the list says where to walk rather than just how far.
 */
fun tripLegDetail(leg: TripLeg): String {
    val target = placeLabel(leg.toStopId?.let { "stop $it — " }.orEmpty() + placeLabel(leg.toDescription))
    return if (leg.isTransit) {
        "to $target"
    } else {
        listOfNotNull(
            formatDistance(leg.distanceMiles).ifBlank { null },
            formatDuration(leg.durationMinutes).takeIf { it != "—" },
            target.takeIf { it.isNotBlank() }?.let { "to $it" },
        ).joinToString(" · ")
    }
}

/** Empty for missing or coordinate-only descriptions ("0.0, 0.0"). */
fun placeLabel(description: String): String {
    val trimmed = description.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.matches(Regex("""-?\d[\d.]*\s*,\s*-?\d[\d.]*"""))) return ""
    return trimmed
}

private fun round1(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
