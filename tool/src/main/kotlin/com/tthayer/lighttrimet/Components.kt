package com.tthayer.lighttrimet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

private val ROUTE_WIDTH = 4f
private val TIME_WIDTH = 7f

/** One arrival: route badge, when, and the destination sign. */
@Composable
fun ArrivalRow(
    arrival: ArrivalDto,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 0.4f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = arrival.route.toString(),
            variant = LightTextVariant.Copy,
            modifier = Modifier.width(ROUTE_WIDTH.gridUnitsAsDp()),
        )
        LightText(
            text = arrivalWhen(arrival, nowMillis),
            variant = LightTextVariant.Copy,
            modifier = Modifier.width(TIME_WIDTH.gridUnitsAsDp()),
            strikethrough = arrival.isCanceled,
        )
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = arrival.shortSign.ifBlank { arrival.fullSign },
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            arrivalNote(arrival)?.let { note ->
                LightText(
                    text = note,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun arrivalNote(arrival: ArrivalDto): String? {
    val parts = buildList {
        if (arrival.dropOffOnly) add("Drop-off only")
        if (arrival.detoured) add("Detour")
        arrival.reason?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return parts.joinToString(" · ").ifBlank { null }
}

/** Icon for a trip leg. */
@Composable
fun LegModeIcon(
    leg: TripLeg,
    modifier: Modifier = Modifier,
) {
    val icon = if (leg.isTransit) LightIcons.DIRECTIONS_BUS else LightIcons.DIRECTIONS_PEDESTRIAN
    LightIcon(icon = icon, modifier = modifier, contentDescription = leg.mode)
}

/** A centered, lightened placeholder message. */
@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        align = TextAlign.Center,
        modifier = modifier.padding(horizontal = 1f.gridUnitsAsDp()),
    )
}
