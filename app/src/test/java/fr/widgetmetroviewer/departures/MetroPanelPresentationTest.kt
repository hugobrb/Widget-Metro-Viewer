package fr.widgetmetroviewer.departures

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class MetroPanelPresentationTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private val nominal = MetroDirection(listOf("Rosny-Bois-Perrier"), true) to MetroDirection(listOf("Châtelet"), true)
    private fun departure(destination: String = "Rosny-Bois-Perrier", minute: Int = 3,
                          direction: DepartureDirection = DepartureDirection.FORWARD,
                          basis: DepartureTimeBasis = DepartureTimeBasis.ESTIMATED_DEPARTURE,
                          atStop: Boolean = false) = MetroDeparture("trip-$minute", "STIF:StopPoint:Q:490766:",
        destination, null, null, now.plusSeconds(minute * 60L), basis, now, atStop, direction)
    private fun snapshot(items: List<MetroDeparture> = listOf(departure()), fetched: Instant = now,
                         station: String = "Montreuil - Hôpital") = DeparturesSnapshot(station, "STIF:Line::C01381:", fetched, items)
    private fun panel(result: DeparturesResult?, loading: Boolean = false) = MetroPanelPresentation.build(
        "Montreuil - Hôpital", "11", nominal, result, now, loading, ZoneOffset.UTC)

    @Test fun demoIsOnlyEnabledByExplicitMissingKeyState() {
        val demo = panel(DeparturesResult(DeparturesState.DEMO, message = "Aucune clé"))
        assertTrue(demo.demo)
        assertEquals("03", demo.first.value)
        assertEquals("min", demo.first.unit)
        assertTrue(demo.tag.contains("DÉMO"))
    }
    @Test fun initialLoadingNeverShowsInventedDepartures() {
        val loading = panel(null, true)
        assertFalse(loading.demo)
        assertNull(loading.first.value)
        assertNull(loading.second.value)
        assertEquals("ACTUALISATION", loading.tag)
    }
    @Test fun realPassagesUseMinutesAndActualDestinations() {
        val first = departure("Romainville - Carnot", 3, DepartureDirection.BACKWARD)
        val next = departure("Châtelet", 6, DepartureDirection.BACKWARD)
        val real = panel(DeparturesResult(DeparturesState.DATA, snapshot(listOf(first, next)), DeparturesOrigin.NETWORK, "Reçus"))
        assertFalse(real.demo)
        assertEquals("03", real.second.value)
        assertEquals(listOf("Romainville - Carnot"), real.second.destinations)
        assertEquals("Suivant : 06 min", real.second.following)
        assertEquals("min", real.second.unit)
        assertEquals("", real.second.detail)
        assertTrue(real.second.followingDestination.contains("Châtelet"))
        assertEquals("TRAFIC NON RENSEIGNÉ", real.footer)
        assertFalse(real.tag.contains("DÉMO"))
    }
    @Test fun theoreticalTimesStayIdentified() {
        val result = panel(DeparturesResult(DeparturesState.DATA,
            snapshot(listOf(departure(basis = DepartureTimeBasis.THEORETICAL_DEPARTURE))), DeparturesOrigin.NETWORK, "Reçus"))
        assertEquals("THÉORIQUE", result.first.detail)
        assertEquals("03", result.first.value)
    }
    @Test fun errorsAndUnsupportedStationsDoNotFallBackToDemo() {
        for (state in listOf(DeparturesState.UNSUPPORTED, DeparturesState.UNAVAILABLE, DeparturesState.QUOTA)) {
            val result = panel(DeparturesResult(state, message = "Indisponible"))
            assertFalse(result.demo)
            assertNull(result.first.value)
            assertNull(result.second.value)
            assertFalse(result.footer.contains("SERVICE NORMAL"))
        }
    }
    @Test fun staleCacheKeepsAnExplicitWarningWithoutTechnicalDetails() {
        val result = panel(DeparturesResult(DeparturesState.UNAVAILABLE,
            snapshot(fetched = now.minusSeconds(600)), DeparturesOrigin.CACHE_STALE, "Hors connexion"))
        assertEquals("03", result.first.value)
        assertTrue(result.first.detail.contains("NON ACTUALISÉ"))
        assertEquals("DONNÉES ANCIENNES", result.tag)
        assertTrue(result.information.contains("anciens"))
    }
    @Test fun noDepartureIsDifferentFromUnavailable() {
        val result = panel(DeparturesResult(DeparturesState.NO_DATA, snapshot(emptyList()), DeparturesOrigin.NETWORK, "Aucun passage"))
        assertNull(result.first.value)
        assertEquals("Aucun passage annoncé", result.first.detail)
    }
    @Test fun terminalDoesNotShowPhantomDepartures() {
        val terminal = nominal.first to MetroDirection(listOf("Châtelet"), false, "Terminus")
        val result = MetroPanelPresentation.build("Châtelet", "11", terminal,
            DeparturesResult(DeparturesState.DATA, snapshot(station = "Châtelet"), DeparturesOrigin.NETWORK, "Reçus"), now, zone = ZoneOffset.UTC)
        assertNull(result.second.value)
        assertEquals("Terminus", result.second.detail)
    }
    @Test fun snapshotOfAnotherStationIsNeverDisplayed() {
        val result = panel(DeparturesResult(DeparturesState.DATA, snapshot(station = "Mairie des Lilas"), DeparturesOrigin.NETWORK, "Reçus"))
        assertNull(result.first.value)
    }
    @Test fun oldCacheCannotClaimVehicleIsCurrentlyAtStop() {
        val result = panel(DeparturesResult(DeparturesState.UNAVAILABLE,
            snapshot(listOf(departure(atStop = true)), now.minusSeconds(600)), DeparturesOrigin.CACHE_STALE, "Ancien cache"))
        assertEquals("03", result.first.value)
    }

    @Test fun loadingPreservesAvailableCacheWithoutSwitchingToDemo() {
        val result = panel(DeparturesResult(DeparturesState.DATA, snapshot(), DeparturesOrigin.CACHE_FRESH, "Cache"), loading = true)
        assertEquals("03", result.first.value)
        assertFalse(result.demo)
        assertEquals("ACTUALISATION", result.tag)
    }

    @Test fun trafficMessagesReplaceThePlaceholderAndNeverChangeDepartureTimes() {
        val traffic = MetroTrafficResult(MetroTrafficSnapshot("STIF:Line::C01381:", now,
            listOf(MetroTrafficMessage("incident", "Un train stationne à Mairie des Lilas.", "Perturbation", now.plusSeconds(600), now))))
        val actual = panel(DeparturesResult(DeparturesState.DATA, snapshot(), DeparturesOrigin.NETWORK, "Reçus", traffic = traffic))
        assertEquals("03", actual.first.value)
        assertTrue(actual.information.contains("Un train stationne"))
        assertEquals("TRAFIC NON RENSEIGNÉ", actual.footer)
        assertNull(actual.trafficAlert)
    }
    @Test fun emptyFreshMessagesDoNotAssertNormalService() {
        val traffic = MetroTrafficResult(MetroTrafficSnapshot("STIF:Line::C01381:", now, emptyList()))
        val actual = panel(DeparturesResult(DeparturesState.DATA, snapshot(), message = "Reçus", traffic = traffic))
        assertEquals("TRAFIC NON RENSEIGNÉ", actual.footer)
        assertFalse(actual.footer.contains("NORMAL"))
    }
    @Test fun staleAndExpiredTrafficMessagesCannotBecomeNormalService() {
        val message = MetroTrafficMessage("expired", "Incident terminé", "Perturbation", now.minusSeconds(1), now)
        val expired = MetroTrafficResult(MetroTrafficSnapshot("STIF:Line::C01381:", now, listOf(message)))
        val actual = panel(DeparturesResult(DeparturesState.DATA, snapshot(), message = "Reçus", traffic = expired))
        assertEquals("TRAFIC NON ACTUALISÉ", actual.footer)
        assertFalse(actual.information.contains("Incident terminé"))
        assertNull(actual.trafficAlert)
        val stillActive = message.copy(validUntil = now.plusSeconds(60))
        val stale = expired.copy(snapshot = expired.snapshot!!.copy(messages = listOf(stillActive)), error = "Hors ligne")
        val cached = panel(DeparturesResult(DeparturesState.DATA, snapshot(), message = "Reçus", traffic = stale))
        assertTrue(cached.information.contains("non actualisé"))
        assertEquals("TRAFIC NON ACTUALISÉ", cached.footer)
    }
}
