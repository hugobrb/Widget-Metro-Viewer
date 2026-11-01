package fr.widgetmetroviewer.departures

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class IdfmLineDeparturesParserTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private val line = "STIF:Line::C01381:"
    private val first = "STIF:StopPoint:Q:490766:"
    private val second = "STIF:StopPoint:Q:490789:"
    private fun call(ref: String, at: String, cancelled: Boolean = false) = JSONObject()
        .put("StopPointRef", JSONObject().put("value", ref)).put("ExpectedDepartureTime", at)
        .put("DepartureStatus", if (cancelled) "cancelled" else "onTime")
    private fun journey(destination: String, vararg calls: JSONObject, otherLine: Boolean = false, id: String = calls.joinToString { it.optString("ExpectedDepartureTime") }) = JSONObject()
        .put("LineRef", JSONObject().put("value", if (otherLine) "STIF:Line::C99999:" else line))
        .put("DestinationName", JSONArray().put(JSONObject().put("value", destination)))
        .put("FramedVehicleJourneyRef", JSONObject().put("DatedVehicleJourneyRef", id))
        .put("EstimatedCalls", JSONObject().put("EstimatedCall", JSONArray(calls.toList())))
    private fun body(vararg journeys: JSONObject) = JSONObject().put("Siri", JSONObject().put("ServiceDelivery", JSONObject()
        .put("EstimatedTimetableDelivery", JSONArray().put(JSONObject().put("Status", true)
            .put("EstimatedJourneyVersionFrame", JSONArray().put(JSONObject().put("RecordedAtTime", now.toString())
                .put("EstimatedVehicleJourney", JSONArray(journeys.toList()))))))))
    private fun parse(vararg journeys: JSONObject) = IdfmLineDeparturesParser.parse(body(*journeys), line, listOf(first, second), now)

    @Test fun bothQuaysComeFromOneLineResponseWithoutOtherStopsOrLines() {
        val result = parse(journey("Rosny-Bois-Perrier", call(first, "2026-10-31T12:03:00Z"), call("STIF:StopPoint:Q:99999:", "2026-10-31T12:04:00Z")),
            journey("Châtelet", call(second, "2026-10-31T12:05:00Z")),
            journey("Other", call(first, "2026-10-31T12:06:00Z"), otherLine = true))
        assertEquals(setOf(first, second), result.departures.map { it.monitoringRef }.toSet())
        assertEquals(2, result.departures.size)
        assertEquals(0, result.malformedRecords)
    }
    @Test fun distinctJourneyIdsDoNotCollapseDistinctTrains() {
        val result = parse(journey("Châtelet", call(second, "2026-10-31T12:03:00Z")),
            journey("Châtelet", call(second, "2026-10-31T12:08:00Z")))
        assertEquals(2, result.departures.size)
        assertNotEquals(result.departures[0].identity, result.departures[1].identity)
    }
    @Test fun cancelledPassageDoesNotRemoveTheFollowingTrain() {
        val result = parse(journey("Châtelet", call(second, "2026-10-31T12:03:00Z", true)),
            journey("Châtelet", call(second, "2026-10-31T12:08:00Z")))
        assertEquals(Instant.parse("2026-10-31T12:08:00Z"), result.departures.single().at)
    }
    @Test fun theoreticalTimesStayMarkedAndNaiveTimesAreRejected() {
        val theoretical = call(first, "2026-10-31T12:03:00Z").apply {
            remove("ExpectedDepartureTime"); put("AimedDepartureTime", "2026-10-31T13:03:00+01:00")
        }
        val result = parse(journey("Rosny-Bois-Perrier", theoretical, call(second, "2026-10-31T12:05:00")))
        assertEquals(1, result.departures.size)
        assertFalse(result.departures.single().timeBasis.estimated)
        assertTrue(result.malformedRecords > 0)
    }
    @Test fun changingTheTimeOfTheSameJourneyKeepsItsIdentity() {
        val firstBody = body(journey("Rosny-Bois-Perrier", call(first, "2026-10-31T12:03:00Z"), id = "RATP-SIV:VehicleJourney::20261031.91.R.C01381:LOC"))
        val secondBody = body(journey("Rosny-Bois-Perrier", call(first, "2026-10-31T12:04:00Z"), id = "RATP-SIV:VehicleJourney::20261031.91.R.C01381:LOC"))
        val a = IdfmLineDeparturesParser.parse(firstBody, line, listOf(first), now).departures.single()
        val b = IdfmLineDeparturesParser.parse(secondBody, line, listOf(first), now).departures.single()
        assertEquals(a.identity, b.identity)
        assertNotEquals(a.at, b.at)
    }
    @Test fun departedStatusIsPropagatedAsAWithdrawalEvenWhenUpperCase() {
        val departed = call(first, "2026-10-31T12:03:00Z").put("DepartureStatus", "DEPARTED")
        val result = parse(journey("Rosny-Bois-Perrier", departed))
        assertTrue(result.departures.isEmpty())
        assertEquals(1, result.withdrawals.size)
        assertTrue(result.withdrawals.single().withdrawn)
    }

    @Test fun qualifiedJourneyHasTheSameIdentityInBothApisAndArrivalIsPreferred() {
        val id = "RATP-SIV:VehicleJourney::20261031.91.R.C01381:LOC"
        val at = call(first, "2026-10-31T12:04:00Z").put("ExpectedArrivalTime", "2026-10-31T12:03:00Z")
        val byLine = IdfmLineDeparturesParser.parse(body(journey("Rosny-Bois-Perrier", at, id = id)), line, listOf(first), now).departures.single()
        val visit = JSONObject().put("RecordedAtTime", now.toString()).put("MonitoringRef", first)
            .put("MonitoredVehicleJourney", JSONObject().put("LineRef", line).put("DestinationName", "Rosny-Bois-Perrier")
                .put("FramedVehicleJourneyRef", JSONObject().put("DataFrameRef", "any").put("DatedVehicleJourneyRef", id))
                .put("MonitoredCall", at))
        val unitBody = JSONObject().put("Siri", JSONObject().put("ServiceDelivery", JSONObject()
            .put("StopMonitoringDelivery", JSONArray().put(JSONObject().put("MonitoredStopVisit", JSONArray().put(visit))))))
        val unit = IdfmDeparturesParser.parse(unitBody, line, first, now).departures.single()
        assertEquals(unit.identity, byLine.identity)
        assertEquals(Instant.parse("2026-10-31T12:03:00Z"), byLine.at)
        assertEquals(DepartureTimeBasis.ESTIMATED_ARRIVAL, byLine.timeBasis)
    }

    @Test fun validEmptyResponseDoesNotInventTrains() { assertTrue(parse().departures.isEmpty()) }
    @Test(expected = IllegalArgumentException::class) fun unsuccessfulDeliveryIsNotAnEmptySuccessfulResponse() {
        val invalid = body()
        IdfmSiri.service(invalid).getJSONArray("EstimatedTimetableDelivery").getJSONObject(0).put("Status", false)
        IdfmLineDeparturesParser.parse(invalid, line, listOf(first), now)
    }
}
