package com.tthayer.lighttrimet

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://developer.trimet.org"
private const val ARRIVALS_PATH = "/ws/v2/arrivals"
private const val TRIP_PLANNER_PATH = "/ws/V1/trips/tripplanner"

/**
 * TriMet web services client.
 *
 * The AppID is a public application identifier issued at registration; TriMet terms require it
 * to accompany every request. It is not a secret. See https://developer.trimet.org/ws_docs/.
 */
object TriMetApi {

    const val APP_ID = "7E56AE94D5215B00222D9ABD5"

    private const val ARRIVAL_WINDOW_MINUTES = "60"
    private const val ARRIVALS_PER_ROUTE = "3"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

    /** Fetches upcoming arrivals for up to 128 stop ids in a single request. */
    suspend fun arrivals(locIds: List<Long>): Result<ArrivalsResult> = runCatching {
        require(locIds.isNotEmpty()) { "No stops requested." }
        val response = client.get("$BASE_URL$ARRIVALS_PATH") {
            parameter("locIDs", locIds.joinToString(","))
            parameter("appID", APP_ID)
            parameter("json", "true")
            parameter("minutes", ARRIVAL_WINDOW_MINUTES)
            parameter("arrivals", ARRIVALS_PER_ROUTE)
        }
        if (!response.status.isSuccess()) {
            error("TriMet arrivals request failed (HTTP ${response.status.value}).")
        }
        val dto: ArrivalsDto = response.body()
        dto.toResult()
    }

    /** Resolves a single stop id to its description, or null when TriMet does not know it. */
    suspend fun lookupStop(locId: Long): Result<StopInfo?> = arrivals(listOf(locId)).map { result ->
        result.error?.let { error(it) }
        result.stops[locId]
    }

    /**
     * Plans a trip departing now. When [arriveBy] is true the trip arrives by the current time
     * instead (used for the "leave now / arrive by" toggle).
     */
    suspend fun planTrip(from: TripEndpoint, to: TripEndpoint): Result<TripPlan> = runCatching {
        val response = client.get("$BASE_URL$TRIP_PLANNER_PATH") {
            parameter("appID", APP_ID)
            parameter("format", "XML")
            parameter("maxIntineraries", "3")
            parameter("Arr", "D")
            appendEndpoint("from", from)
            appendEndpoint("to", to)
        }
        if (!response.status.isSuccess()) {
            error("TriMet trip planner request failed (HTTP ${response.status.value}).")
        }
        parseTripPlan(response.bodyAsText())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.appendEndpoint(prefix: String, endpoint: TripEndpoint) {
        when (endpoint) {
            is TripEndpoint.Place -> parameter("${prefix}Place", endpoint.text)
            is TripEndpoint.StopId -> parameter("${prefix}Place", endpoint.id)
            is TripEndpoint.Coordinate -> {
                parameter("${prefix}Coord", "${endpoint.longitude},${endpoint.latitude}")
                if (endpoint.label.isNotBlank()) parameter("${prefix}Place", endpoint.label)
            }
        }
    }
}
