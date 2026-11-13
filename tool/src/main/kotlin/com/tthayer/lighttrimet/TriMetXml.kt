package com.tthayer.lighttrimet

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Trip Planner XML parsing.
 *
 * The Trip Planner only returns XML (it has no JSON mode), and the response nests `<from>`/`<to>`
 * both at the top level and inside every leg, so a DOM is easier to walk than a flat stream.
 * Parsing uses `javax.xml`, which is available on Android and on the JVM, so this logic is
 * covered by unit tests.
 */
private val documentBuilderFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = false
    // No external entities are needed; disabling them avoids XXE on the odd parser that allows it.
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
}

/** Parses a Trip Planner XML document into a [TripPlan]. */
fun parseTripPlan(xml: String): TripPlan {
    val document = documentBuilderFactory.newDocumentBuilder()
        .parse(InputSource(StringReader(xml)))
    val root = document.documentElement

    val success = root.attr("success") == "true"
    val error = root.child("error")?.child("message")?.textOrNull()

    val topFrom = root.child("from")
    val topTo = root.child("to")

    return TripPlan(
        success = success && root.child("itineraries") != null,
        errorMessage = error,
        fromDescription = topFrom?.child("description")?.text().orEmpty(),
        toDescription = topTo?.child("description")?.text().orEmpty(),
        fromResolved = topFrom.isResolved(),
        toResolved = topTo.isResolved(),
        fromCandidates = root.child("fromList").toCandidates(),
        toCandidates = root.child("toList").toCandidates(),
        itineraries = root.child("itineraries")
            ?.childrenNamed("itinerary")
            ?.map { parseItinerary(it) }
            .orEmpty(),
    )
}

/** TriMet leaves the position at 0,0 when it could not geocode an endpoint. */
private fun Element?.isResolved(): Boolean {
    val pos = this?.child("pos") ?: return false
    val lat = pos.child("lat")?.text()?.toDoubleOrNull() ?: 0.0
    val lon = pos.child("lon")?.text()?.toDoubleOrNull() ?: 0.0
    return lat != 0.0 || lon != 0.0
}

private fun Element?.toCandidates(): List<PlaceCandidate> =
    this?.childrenNamed("location")?.mapNotNull { location ->
        val pos = location.child("pos") ?: return@mapNotNull null
        val lat = pos.child("lat")?.text()?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = pos.child("lon")?.text()?.toDoubleOrNull() ?: return@mapNotNull null
        PlaceCandidate(
            id = location.attr("id").ifBlank { null },
            description = location.child("description")?.text().orEmpty(),
            latitude = lat,
            longitude = lon,
        )
    }.orEmpty()

private fun parseItinerary(node: Element): Itinerary {
    val timeDistance = node.child("time-distance")
    return Itinerary(
        id = node.attr("id"),
        viaRoute = node.attr("viaRoute").ifBlank { null },
        startTime = timeDistance?.child("startTime")?.text().orEmpty(),
        endTime = timeDistance?.child("endTime")?.text().orEmpty(),
        durationMinutes = timeDistance?.child("duration")?.text()?.toIntOrNull() ?: 0,
        distanceMiles = timeDistance?.child("distance")?.text()?.toDoubleOrNull() ?: 0.0,
        transfers = timeDistance?.child("numberOfTransfers")?.text()?.toIntOrNull() ?: 0,
        fare = node.child("fare")?.child("regular")?.textOrNull(),
        legs = node.childrenNamed("leg").map { parseLeg(it) },
    )
}

private fun parseLeg(node: Element): TripLeg {
    val timeDistance = node.child("time-distance")
    val route = node.child("route")
    return TripLeg(
        mode = node.attr("mode").ifBlank { "Walk" },
        order = node.attr("order").ifBlank { null },
        startTime = timeDistance?.child("startTime")?.textOrNull(),
        endTime = timeDistance?.child("endTime")?.textOrNull(),
        durationMinutes = timeDistance?.child("duration")?.text()?.toIntOrNull() ?: 0,
        distanceMiles = timeDistance?.child("distance")?.text()?.toDoubleOrNull() ?: 0.0,
        fromDescription = node.child("from")?.child("description")?.text().orEmpty(),
        toDescription = node.child("to")?.child("description")?.text().orEmpty(),
        fromStopId = node.child("from")?.child("stopId")?.textOrNull(),
        fromStopSequence = node.child("from")?.child("stopSequence")?.textOrNull(),
        toStopId = node.child("to")?.child("stopId")?.textOrNull(),
        toStopSequence = node.child("to")?.child("stopSequence")?.textOrNull(),
        routeNumber = route?.child("number")?.textOrNull(),
        routeName = route?.child("name")?.textOrNull(),
        direction = node.child("direction")?.textOrNull(),
    )
}

private fun Element.attr(name: String): String =
    if (hasAttribute(name)) getAttribute(name) else ""

private fun Element.child(name: String): Element? =
    childrenNamed(name).firstOrNull()

private fun Element.childrenNamed(name: String): List<Element> {
    val nodes = childNodes
    val result = ArrayList<Element>()
    for (i in 0 until nodes.length) {
        val child = nodes.item(i) as? Element ?: continue
        if (child.tagName == name) result.add(child)
    }
    return result
}

private fun Element.text(): String = textContent?.trim().orEmpty()

private fun Element.textOrNull(): String? = text().ifBlank { null }
