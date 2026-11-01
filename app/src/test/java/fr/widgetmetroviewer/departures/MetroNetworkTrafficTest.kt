package fr.widgetmetroviewer.departures

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class MetroNetworkTrafficTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private val m11 = "line:IDFM:C01381"
    private val m8 = "line:IDFM:C01378"
    private fun links(id: String) = JSONArray().put(JSONObject().put("type", "disruption").put("id", id))
    private fun event(id: String, effect: String, stationOnly: Boolean = false,
                      begin: String = "20261031T120000", end: String = "20261031T180000") = JSONObject()
        .put("id", id).put("status", "active")
        .put("severity", JSONObject().put("effect", effect).put("name", "Information"))
        .put("messages", JSONArray().put(JSONObject().put("text", "L’arrêt République n’est pas desservi en raison de travaux.")))
        .put("application_periods", JSONArray().put(JSONObject().put("begin", begin).put("end", end)))
        .put("impacted_objects", JSONArray().apply {
            if (stationOnly) put(JSONObject().put("pt_object", JSONObject().put("embedded_type", "stop_point")))
        })
    private fun report(line: String, id: String? = null, station: String? = null): JSONObject {
        val root = JSONObject().put("line", JSONObject().put("id", line)).put("pt_objects", JSONArray())
        if (id != null) {
            if (station == null) root.getJSONObject("line").put("links", links(id))
            else root.getJSONArray("pt_objects").put(JSONObject().put("embedded_type", "stop_point")
                .put("stop_point", JSONObject().put("id", "stop_point:IDFM:22199").put("name", station).put("links", links(id))))
        }
        return root
    }
    private fun body(reports: List<JSONObject>, events: List<JSONObject>) = JSONObject()
        .put("line_reports", JSONArray(reports)).put("disruptions", JSONArray(events))
        .put("pagination", JSONObject().put("start_page", 0).put("items_per_page", 100).put("total_result", reports.size))
    private fun context(): MetroTrafficContext {
        val file = listOf(File("src/main/assets/metro_catalog.json"), File("app/src/main/assets/metro_catalog.json")).first { it.isFile }
        val lines = MetroCatalog.parse(file.readText())
        return MetroTrafficContext.build(lines, lines.single { it.name == "11" })
    }
    private fun board(body: JSONObject, at: Instant = now, error: String? = null): MetroTrafficBoard {
        val page = IdfmNetworkTrafficParser.parse(body, 0)
        val snapshot = MetroNetworkTrafficSnapshot(now, page.lineIds, page.disruptions)
        return MetroTrafficPresentation.build(MetroTrafficResult(network = MetroNetworkTrafficResult(snapshot, error), context = context()), at)
    }
    @Test fun republicLine8ClosureIsAConnectionNoticeAndDoesNotInterruptLine11() {
        val result = board(body(listOf(report(m11), report(m8, "republique", "République (Paris)")), listOf(event("republique", "NO_SERVICE", true))))
        assertEquals(MetroCirculation.CLEAR, result.circulation)
        assertEquals(false, result.alert)
        assertTrue(result.details.single().contains("Correspondance M8 à République"))
        assertTrue(result.details.single().contains("pas desservi"))
    }
    @Test fun selectedLineStateUsesEffectsAndChoosesTheMostSevereCurrentEffect() {
        for ((effect, expected) in listOf("SIGNIFICANT_DELAYS" to MetroCirculation.SLOW,
            "REDUCED_SERVICE" to MetroCirculation.DISTURBED, "NO_SERVICE" to MetroCirculation.INTERRUPTED)) {
            val result = board(body(listOf(report(m11, effect)), listOf(event(effect, effect))))
            assertEquals(expected, result.circulation)
            assertEquals(true, result.alert)
        }
        val report = report(m11).apply { getJSONObject("line").put("links", JSONArray()
            .put(JSONObject().put("type", "disruption").put("id", "delay"))
            .put(JSONObject().put("type", "disruption").put("id", "interruption"))) }
        val result = board(body(listOf(report), listOf(event("delay", "SIGNIFICANT_DELAYS"), event("interruption", "NO_SERVICE"))))
        assertEquals(MetroCirculation.INTERRUPTED, result.circulation)
    }
    @Test fun stationClosureOnSelectedLineDoesNotClaimTrainsHaveStoppedRunning() {
        val result = board(body(listOf(report(m11, "closure", "République")), listOf(event("closure", "NO_SERVICE", true))))
        assertEquals(MetroCirculation.CLEAR, result.circulation)
        assertTrue(result.details.single().contains("République"))
    }
    @Test fun futureAndExpiredEventsDoNotChangeCurrentCirculation() {
        for ((begin, end) in listOf("20261101T120000" to "20261101T180000", "20261030T120000" to "20261030T180000")) {
            val result = board(body(listOf(report(m11, "closure")), listOf(event("closure", "NO_SERVICE", begin = begin, end = end))))
            assertEquals(MetroCirculation.CLEAR, result.circulation)
            assertTrue(result.details.isEmpty())
        }
    }
    @Test fun unrelatedLine8StationDoesNotBecomeACorrespondenceNotice() {
        val report = report(m8, "closure", "Balard").apply {
            getJSONArray("pt_objects").getJSONObject(0).getJSONObject("stop_point").put("id", "stop_point:IDFM:99999")
        }
        assertTrue(board(body(listOf(report(m11), report), listOf(event("closure", "NO_SERVICE", true)))).details.isEmpty())
    }
    @Test fun staleDataCannotClaimCurrentSlowOrClearTraffic() {
        val json = body(listOf(report(m11, "delay")), listOf(event("delay", "SIGNIFICANT_DELAYS")))
        val stale = board(json, error = "Hors ligne")
        assertEquals("TRAFIC NON ACTUALISÉ", stale.footer)
        assertNull(stale.alert)
        assertTrue(stale.details.single().contains("non actualisée"))
        val old = board(json, now.plusSeconds(301))
        assertEquals("TRAFIC NON ACTUALISÉ", old.footer)
    }
    @Test fun unknownEffectAndMissingLineCoverageDoNotAssertClearTraffic() {
        assertEquals(MetroCirculation.UNKNOWN, board(body(listOf(report(m11, "unknown")), listOf(event("unknown", "NEW_EFFECT")))).circulation)
        assertEquals(MetroCirculation.UNKNOWN, board(body(listOf(report(m8)), emptyList())).circulation)
    }
    @Test fun localDateTimesUseParisAndEntitiesAreDecoded() {
        assertEquals(now, IdfmNetworkTrafficParser.instant("20261031T130000"))
        assertEquals("République & travaux", IdfmNetworkTrafficParser.plainText("<b>R&#233;publique</b>&nbsp;&amp; travaux"))
    }
    @Test fun paginationAndMissingDisruptionLinksCannotHideAnAlert() {
        val json = body(listOf(report(m11)), emptyList()).apply { getJSONObject("pagination").put("total_result", 101) }
        assertTrue(IdfmNetworkTrafficParser.parse(json, 0).hasNext)
        assertTrue(runCatching { IdfmNetworkTrafficParser.parse(body(listOf(report(m11, "missing")), emptyList()), 0) }.isFailure)
    }
    @Test fun screenPerturbationChannelAloneCannotDefineCirculationState() {
        val screen = MetroTrafficSnapshot("STIF:Line::C01381:", now,
            listOf(MetroTrafficMessage("works", "Travaux dans une station", "Perturbation", now.plusSeconds(600), now)))
        assertEquals(MetroCirculation.UNKNOWN, MetroTrafficPresentation.build(MetroTrafficResult(screen), now).circulation)
    }

    @Test fun footerPreservesThePublishedApiWordingForTheSelectedLine() {
        val disruption = event("delay", "REDUCED_SERVICE").apply {
            getJSONObject("severity").put("name", "Trafic fortement perturbé")
        }
        val payload = body(listOf(report(m11, "delay")), listOf(disruption))
        assertEquals("Trafic fortement perturbé", board(payload).footer)
        assertEquals("TRAFIC NON ACTUALISÉ", board(payload, error = "Hors ligne").footer)
        val otherLine = board(body(listOf(report(m11), report(m8, "delay")), listOf(disruption)))
        assertEquals("TRAFIC NORMAL", otherLine.footer)
    }
}
