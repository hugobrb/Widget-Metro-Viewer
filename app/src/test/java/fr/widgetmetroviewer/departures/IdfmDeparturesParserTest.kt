package fr.widgetmetroviewer.departures

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class IdfmDeparturesParserTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private val line = "STIF:Line::C01381:"
    private val quay = "STIF:StopPoint:Q:490766:"
    private fun asset(name: String): String = listOf(File("app/src/main/assets/$name"), File("src/main/assets/$name"))
        .first { it.isFile }.readText()
    private val network = MetroDirections.fromJson(asset("metro_station_order.json"))
    private fun line11(): MetroLine {
        return MetroStationOrder.apply(MetroCatalog.parse(asset("metro_catalog.json")), asset("metro_station_order.json"))
            .single { it.name == "11" }
    }
    private fun visit(destination: String = "Rosny-Bois-Perrier", id: String = "trip-1",
                      expected: String? = "2026-10-31T12:03:00Z", aimed: String? = null,
                      cancelled: Boolean = false, lineRef: String = line, recorded: String = "2026-10-31T12:00:00Z"): JSONObject {
        val call = JSONObject().put("StopPointRef", JSONObject().put("value", quay))
        expected?.let { call.put("ExpectedDepartureTime", it) }
        aimed?.let { call.put("AimedDepartureTime", it) }
        if (cancelled) call.put("DepartureStatus", "cancelled")
        return JSONObject().put("RecordedAtTime", recorded).put("MonitoredVehicleJourney", JSONObject()
            .put("LineRef", JSONObject().put("value", lineRef))
            .put("DestinationName", JSONArray().put(JSONObject().put("value", destination)))
            .put("DirectionRef", JSONObject().put("value", "not-a-reliable-direction-code"))
            .put("FramedVehicleJourneyRef", JSONObject().put("DataFrameRef", JSONObject().put("value", "2026-10-31"))
                .put("DatedVehicleJourneyRef", id)).put("MonitoredCall", call))
    }
    private fun body(vararg visits: JSONObject): JSONObject = JSONObject().put("Siri", JSONObject()
        .put("ServiceDelivery", JSONObject().put("StopMonitoringDelivery", JSONArray().put(JSONObject()
            .put("Status", true).put("MonitoredStopVisit", JSONArray(visits.toList()))))))
    private fun parse(vararg visits: JSONObject) = IdfmDeparturesParser.parse(body(*visits), line, quay, now)

    @Test fun estimatedTimeWinsAndOffsetsAreRespected() {
        val result = parse(visit(expected = "2026-10-31T13:01:01+01:00", aimed = "2026-10-31T12:10:00Z")).departures.single()
        assertEquals(Instant.parse("2026-10-31T12:01:01Z"), result.at)
        assertEquals(DepartureTimeBasis.ESTIMATED_DEPARTURE, result.timeBasis)
        assertEquals(2L, result.waitingMinutes(now))
    }
    @Test fun theoreticalTimesAreExplicitAndInvalidDatesAreNotGuessed() {
        val theoretical = parse(visit(expected = null, aimed = "2026-10-31T12:04:00Z")).departures.single()
        assertFalse(theoretical.timeBasis.estimated)
        assertEquals(1, parse(visit(expected = "2026-10-31T12:03:00")).malformedRecords)
        assertTrue(parse(visit(expected = "2026-10-31T11:59:00Z")).departures.isEmpty())
    }
    @Test fun cancelledOtherLinesAndDuplicateTripsAreExcluded() {
        val first = visit(id = "same", expected = "2026-10-31T12:02:00Z")
        val updated = visit(id = "same", expected = "2026-10-31T12:05:00Z", recorded = "2026-10-31T12:00:10Z")
        val parsed = parse(first, updated, visit(id = "cancelled", cancelled = true), visit(id = "other", lineRef = "STIF:Line::C01374:"))
        assertEquals(1, parsed.departures.size)
        assertEquals(5L, parsed.departures.single().waitingMinutes(now))
        assertTrue(parse(first, visit(id = "same", cancelled = true)).departures.isEmpty())
    }
    @Test fun partialDestinationsStayRealAndUnknownDirectionsAreNotInvented() {
        val parsed = parse(visit("Romainville - Carnot", "partial"), visit("Châtelet", "terminus"))
        val result = network.classify(parsed.departures, "Montreuil–Hôpital", line11())
        assertTrue(result.all { it.direction == DepartureDirection.BACKWARD })
        assertTrue(result.any { it.destinationName == "Romainville - Carnot" })
        val unknown = network.classify(parse(visit("Destination inconnue")).departures, "Montreuil–Hôpital", line11()).single()
        assertEquals(DepartureDirection.UNRESOLVED, unknown.direction)
    }
    @Test fun destinationEvidenceCanClassifyAnUnknownServiceOnTheSameQuay() {
        val result = network.classify(parse(visit(), visit("Service spécial", "special")).departures,
            "Montreuil–Hôpital", line11())
        assertEquals(DepartureDirection.FORWARD, result.single { it.destinationName == "Service spécial" }.direction)
    }
    @Test fun terminalDoesNotCreateDepartureTowardsItself() {
        val parsed = parse(visit("Châtelet", "self"), visit("Rosny-Bois-Perrier", "away"))
        val result = network.classify(parsed.departures, "Châtelet", line11())
        assertEquals(1, result.size)
        assertEquals(DepartureDirection.FORWARD, result.single().direction)
    }
    @Test fun snapshotReturnsOnlyTheNextTwoAndExpiresPastPassages() {
        val parsed = parse(visit(id = "third", expected = "2026-10-31T12:09:00Z"),
            visit(id = "first", expected = "2026-10-31T12:01:00Z"), visit(id = "second", expected = "2026-10-31T12:04:00Z"))
        val snapshot = DeparturesSnapshot("Montreuil - Hôpital", line, now,
            network.classify(parsed.departures, "Montreuil - Hôpital", line11()))
        assertEquals(listOf(1L, 4L), snapshot.next(DepartureDirection.FORWARD, now).map { it.waitingMinutes(now) })
        assertTrue(snapshot.next(DepartureDirection.FORWARD, now.plusSeconds(600)).isEmpty())
    }
    @Test fun invalidRecordDoesNotHideValidOnesAndForeignQuayIsIgnored() {
        val foreign = visit(id = "foreign").also { it.getJSONObject("MonitoredVehicleJourney").getJSONObject("MonitoredCall")
            .put("StopPointRef", JSONObject().put("value", "STIF:StopPoint:Q:490789:")) }
        val result = parse(JSONObject(), visit(), foreign)
        assertEquals(1, result.departures.size)
        assertEquals(1, result.malformedRecords)
    }
}
