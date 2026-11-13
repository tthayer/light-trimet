package com.tthayer.lighttrimet

import kotlinx.serialization.Serializable

/**
 * Arrivals V2 response models (https://developer.trimet.org/ws_docs/arrivals2_ws.shtml).
 *
 * Only the fields this tool renders are modeled; the API sends more and the JSON parser
 * is configured to ignore unknown keys.
 */
@Serializable
data class ArrivalsDto(
    val resultSet: ArrivalsResultSet? = null,
)

@Serializable
data class ArrivalsResultSet(
    val queryTime: Long = 0,
    val location: List<LocationDto> = emptyList(),
    val arrival: List<ArrivalDto> = emptyList(),
    val error: ApiErrorDto? = null,
)

@Serializable
data class ApiErrorDto(
    val content: String = "",
)

@Serializable
data class LocationDto(
    val id: Long = 0,
    val desc: String = "",
    val dir: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val passengerCode: String = "E",
)

@Serializable
data class ArrivalDto(
    val route: Int = 0,
    val dir: Int = 0,
    val locid: Long = 0,
    val status: String = "scheduled",
    val estimated: Long? = null,
    val scheduled: Long? = null,
    val shortSign: String = "",
    val fullSign: String = "",
    val routeSubType: String = "",
    val detoured: Boolean = false,
    val dropOffOnly: Boolean = false,
    val showMilesAway: Boolean = false,
    val reason: String? = null,
    val departed: Boolean = false,
)

/** A stop as reported by the arrivals service. */
data class StopInfo(
    val id: Long,
    val desc: String,
    val direction: String,
    val latitude: Double,
    val longitude: Double,
)

/** Arrivals grouped by stop, ready for the UI. */
data class ArrivalsResult(
    val stops: Map<Long, StopInfo>,
    val arrivals: Map<Long, List<ArrivalDto>>,
    val error: String? = null,
)

fun ArrivalDto.timeMillis(): Long? = estimated ?: scheduled

/**
 * Whether this arrival reports a real-time estimate. Once a vehicle is further than an hour
 * out, or its radio is down, TriMet only reports the schedule.
 */
val ArrivalDto.isRealTime: Boolean
    get() = status == "estimated" || status == "delayed"

val ArrivalDto.isCanceled: Boolean
    get() = status == "canceled"

fun ArrivalsDto.toResult(): ArrivalsResult {
    val set = resultSet ?: return ArrivalsResult(emptyMap(), emptyMap(), "Empty response from TriMet.")
    set.error?.let { return ArrivalsResult(emptyMap(), emptyMap(), it.content.ifBlank { "TriMet returned an error." }) }

    val stops = set.location.associate { dto ->
        dto.id to StopInfo(
            id = dto.id,
            desc = dto.desc,
            direction = dto.dir,
            latitude = dto.lat,
            longitude = dto.lng,
        )
    }
    val arrivals = set.arrival.groupBy { it.locid }
    return ArrivalsResult(stops, arrivals)
}

// ---------------------------------------------------------------------------------------------
// Trip planner
// ---------------------------------------------------------------------------------------------

/** One end of a planned trip. */
sealed interface TripEndpoint {
    /** Free text sent as `fromPlace`/`toPlace` for TriMet to geocode. */
    data class Place(val text: String) : TripEndpoint

    /** A stop id, sent as `fromPlace`/`toPlace` (TriMet resolves stop ids directly). */
    data class StopId(val id: String) : TripEndpoint

    /** An explicit coordinate, sent as `fromCoord`/`toCoord`. */
    data class Coordinate(val longitude: Double, val latitude: Double, val label: String) : TripEndpoint
}

/** A place name TriMet suggests when it could not resolve the requested endpoint. */
data class PlaceCandidate(
    val id: String?,
    val description: String,
    val latitude: Double,
    val longitude: Double,
)

/** A single leg of an itinerary. */
data class TripLeg(
    val mode: String,
    val order: String?,
    val startTime: String?,
    val endTime: String?,
    val durationMinutes: Int,
    val distanceMiles: Double,
    val fromDescription: String,
    val toDescription: String,
    val fromStopId: String?,
    val fromStopSequence: String?,
    val toStopId: String?,
    val toStopSequence: String?,
    val routeNumber: String?,
    val routeName: String?,
    val direction: String?,
) {
    val isTransit: Boolean get() = routeNumber != null || mode != "Walk"
}

/** A complete trip option. */
data class Itinerary(
    val id: String,
    val viaRoute: String?,
    val startTime: String,
    val endTime: String,
    val durationMinutes: Int,
    val distanceMiles: Double,
    val transfers: Int,
    val fare: String?,
    val legs: List<TripLeg>,
) {
    val transitLegs: List<TripLeg> get() = legs.filter { it.isTransit }
    val walkLegs: List<TripLeg> get() = legs.filter { !it.isTransit }
}

/**
 * Result of a trip plan. When [success] is false and [fromCandidates]/[toCandidates] are
 * populated, the user can pick one of the ambiguous places and retry.
 */
data class TripPlan(
    val success: Boolean,
    val errorMessage: String?,
    val fromDescription: String,
    val toDescription: String,
    val fromResolved: Boolean,
    val toResolved: Boolean,
    val fromCandidates: List<PlaceCandidate>,
    val toCandidates: List<PlaceCandidate>,
    val itineraries: List<Itinerary>,
)
