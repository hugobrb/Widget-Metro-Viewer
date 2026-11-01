package fr.widgetmetroviewer.departures

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

internal data class ParsedDepartures(val departures: List<MetroDeparture>, val malformedRecords: Int,
                                     val cancelledJourneyIds: Set<String> = emptySet())

/** Champs SIRI Lite documentés par PRIM. Les libellés de destination réels sont conservés. */
internal object IdfmDeparturesParser {
    fun parse(body: JSONObject, lineRef: String, monitoringRef: String, now: Instant): ParsedDepartures {
        val service = body.getJSONObject("Siri").getJSONObject("ServiceDelivery")
        require(service.optBoolean("Status", true))
        val deliveries = service.getJSONArray("StopMonitoringDelivery")
        val result = mutableListOf<MetroDeparture>()
        var malformed = 0
        val cancelledJourneys = mutableSetOf<String>()
        for (i in 0 until deliveries.length()) {
            val delivery = deliveries.getJSONObject(i)
            if (!delivery.optBoolean("Status", true)) {
                require(delivery.optJSONObject("ErrorCondition")?.has("NoInfoForTopicError") == true)
                continue
            }
            val visits = delivery.optJSONArray("MonitoredStopVisit") ?: continue
            for (j in 0 until visits.length()) {
                try {
                    val visit = visits.getJSONObject(j)
                    val journey = visit.getJSONObject("MonitoredVehicleJourney")
                    val returnedLine = text(journey.opt("LineRef"))
                    if (returnedLine == null) { malformed++; continue }
                    if (returnedLine != lineRef) continue
                    val call = journey.getJSONObject("MonitoredCall")
                    val returnedRef = text(call.opt("StopPointRef")) ?: text(visit.opt("MonitoringRef"))
                    if (returnedRef != null && stopId(returnedRef) != stopId(monitoringRef)) continue
                    val framed = journey.optJSONObject("FramedVehicleJourneyRef")
                    val dated = text(framed?.opt("DatedVehicleJourneyRef")) ?: text(journey.opt("DatedVehicleJourneyRef"))
                    val frame = text(framed?.opt("DataFrameRef")).orEmpty()
                    val journeyId = dated?.let { "$frame|$it" }.orEmpty()
                    val departureStatus = text(call.opt("DepartureStatus"))
                    val arrivalStatus = text(call.opt("ArrivalStatus"))
                    if (journey.optBoolean("Cancellation", false) || call.optBoolean("Cancellation", false) ||
                        departureStatus.equals("cancelled", true) || arrivalStatus.equals("cancelled", true)) {
                        if (journeyId.isNotBlank()) cancelledJourneys += journeyId
                        continue
                    }
                    val candidates = listOf(
                        "ExpectedDepartureTime" to DepartureTimeBasis.ESTIMATED_DEPARTURE,
                        "ExpectedArrivalTime" to DepartureTimeBasis.ESTIMATED_ARRIVAL,
                        "AimedDepartureTime" to DepartureTimeBasis.THEORETICAL_DEPARTURE,
                        "AimedArrivalTime" to DepartureTimeBasis.THEORETICAL_ARRIVAL)
                    val time = candidates.firstNotNullOfOrNull { (field, basis) ->
                        instant(text(call.opt(field)))?.let { it to basis }
                    }
                    if (time == null) { malformed++; continue }
                    val destination = text(call.opt("DestinationDisplay")) ?: text(journey.opt("DestinationName"))
                        ?: "Destination non renseignée"
                    val departure = MetroDeparture(
                        journeyId = journeyId, monitoringRef = monitoringRef,
                        destinationName = destination.take(200), destinationRef = text(journey.opt("DestinationRef")),
                        directionRef = text(journey.opt("DirectionRef")), at = time.first, timeBasis = time.second,
                        recordedAt = instant(text(visit.opt("RecordedAtTime"))), vehicleAtStop = call.optBoolean("VehicleAtStop", false))
                    if (departure.isUpcoming(now)) result += departure
                } catch (_: Exception) { malformed++ }
            }
        }
        return ParsedDepartures(deduplicate(result.filter { it.journeyId !in cancelledJourneys }), malformed, cancelledJourneys)
    }

    internal fun deduplicate(items: List<MetroDeparture>): List<MetroDeparture> = items.groupBy { it.identity }
        .values.map { duplicates -> duplicates.maxWith(compareBy<MetroDeparture> { it.recordedAt ?: Instant.MIN }
            .thenBy { it.timeBasis.estimated }) }.sortedBy { it.at }

    internal fun instant(value: String?): Instant? = value?.let {
        runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }
    private fun stopId(value: String): String = Regex("^(?:IDFM:|STIF:StopPoint:Q:)([0-9]+):?$")
        .matchEntire(value)?.groupValues?.get(1) ?: value
    private fun text(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() && it != "null" }
        is JSONObject -> text(value.opt("value"))
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { text(value.opt(it)) }
        else -> null
    }
}
