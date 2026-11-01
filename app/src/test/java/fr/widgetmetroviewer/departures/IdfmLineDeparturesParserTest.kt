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
    private fun journey(destination: String, vararg calls: JSONObject, otherLine: Boolean = false) = JSONObject()
        .put("LineRef", JSONObject().put("value", if (otherLine) "STIF:Line::C99999:" else line))
        .put("DestinationName", JSONArray().put(JSONObject().put("value", destination)))
        .put("FramedVehicleJourneyRef", JSONObject().put("DatedVehicleJourneyRef", "RATP-counter-reused"))
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
    @Test fun ratpTechnicalCounterDoesNotCollapseDistinctPassageTimes() {
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
    @Test fun validEmptyResponseDoesNotInventTrains() { assertTrue(parse().departures.isEmpty()) }
    @Test(expected = IllegalArgumentException::class) fun unsuccessfulDeliveryIsNotAnEmptySuccessfulResponse() {
        val invalid = body()
        IdfmSiri.service(invalid).getJSONArray("EstimatedTimetableDelivery").getJSONObject(0).put("Status", false)
        IdfmLineDeparturesParser.parse(invalid, line, listOf(first), now)
    }
}
