package fr.widgetmetroviewer.departures

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MetroDepartureTrackerTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private fun train(id: String = "trip-1", seconds: Long = 60, stamp: Instant = now, quay: String = "quay-1") = MetroDeparture(
        id, quay, "Rosny-Bois-Perrier", null, null, now.plusSeconds(seconds),
        DepartureTimeBasis.ESTIMATED_ARRIVAL, stamp, false, DepartureDirection.FORWARD, now)
    private fun snapshot(items: List<MetroDeparture>, fetched: Instant = now) = DeparturesSnapshot("Montreuil - Hôpital", "STIF:Line::C01381:", fetched, items)
    private fun merge(old: DeparturesSnapshot?, incoming: List<MetroDeparture>, at: Instant = now, removed: List<MetroDeparture> = emptyList()) =
        MetroDepartureTracker.merge(old, incoming, removed, at, at)

    @Test fun newerTimestampRevisesTheSameTrainWithoutAddingADuplicate() {
        val old = train()
        val result = merge(snapshot(listOf(old)), listOf(train(seconds = 180, stamp = now.plusSeconds(20))), now.plusSeconds(20))
        assertEquals(1, result.size)
        assertEquals(old.identity, result.single().identity)
        assertEquals(now.plusSeconds(180), result.single().at)
    }
    @Test fun olderAndConflictingEqualTimestampsCannotRevertAForecast() {
        val old = train(seconds = 120)
        for (stamp in listOf(now.minusSeconds(1), now)) {
            val result = merge(snapshot(listOf(old)), listOf(train(seconds = 240, stamp = stamp)), now.plusSeconds(20))
            assertEquals(old.at, result.single().at)
            assertEquals(now, result.single().lastObservedAt)
        }
    }
    @Test fun missingTrainIsKeptBrieflyThenExpires() {
        val first = merge(null, listOf(train(seconds = 300)))
        val held = merge(snapshot(first), emptyList(), now.plusSeconds(30))
        assertTrue(held.single().isUpcoming(now.plusSeconds(30)))
        assertFalse(held.single().isUpcoming(now.plusSeconds(91)))
        val expired = merge(snapshot(held, now.plusSeconds(30)), emptyList(), now.plusSeconds(91))
        assertTrue(expired.none { it.isUpcoming(now.plusSeconds(91)) })
    }
    @Test fun explicitWithdrawalRemovesImmediatelyAndOlderDataCannotResurrectIt() {
        val old = train()
        val removal = old.copy(recordedAt = now.plusSeconds(10), withdrawn = true)
        val cancelled = merge(snapshot(listOf(old)), emptyList(), now.plusSeconds(10), listOf(removal))
        assertTrue(cancelled.single().withdrawn)
        val after = merge(snapshot(cancelled, now.plusSeconds(10)), listOf(old), now.plusSeconds(20))
        assertTrue(after.single().withdrawn)
    }
    @Test fun pastIncomingForecastDoesNotReplaceFuturePredictionOrCreateAQuayPresence() {
        val old = train(seconds = 120)
        val past = train(seconds = -5, stamp = now.plusSeconds(5))
        assertTrue(merge(null, listOf(past), now.plusSeconds(10)).isEmpty())
        val after = merge(snapshot(listOf(old)), listOf(past), now.plusSeconds(10))
        assertEquals(old.at, after.single().at)
    }
    @Test fun estimatedPresenceLastsExactlyTenSecondsAndTheoreticalTimeDoesNotClaimPresence() {
        val item = train(seconds = 20)
        assertFalse(item.estimatedAtStop(now.plusSeconds(19)))
        assertTrue(item.estimatedAtStop(now.plusSeconds(20)))
        assertTrue(item.estimatedAtStop(now.plusSeconds(29)))
        assertFalse(item.isUpcoming(now.plusSeconds(30)))
        assertFalse(item.copy(timeBasis = DepartureTimeBasis.THEORETICAL_ARRIVAL).estimatedAtStop(now.plusSeconds(20)))
    }
    @Test fun presentationShowsQuayThenFollowingTrainWithoutExtraForecastMessages() {
        val first = train(seconds = 20)
        val next = train(id = "trip-2", seconds = 180)
        val state = DeparturesResult(DeparturesState.DATA, snapshot(listOf(first, next)), DeparturesOrigin.NETWORK, "Test")
        val nominal = MetroDirection(listOf("Rosny-Bois-Perrier"), true) to MetroDirection(listOf("Châtelet"), true)
        val arrived = MetroPanelPresentation.build("Montreuil - Hôpital", "11", nominal, state, now.plusSeconds(20))
        assertEquals("À QUAI", arrived.first.value)
        assertEquals("", arrived.first.detail)
        val after = MetroPanelPresentation.build("Montreuil - Hôpital", "11", nominal, state, now.plusSeconds(30))
        assertEquals("03", after.first.value)
        assertEquals("", after.first.detail)
    }
    @Test fun nextLocalTransitionIsArrivalThenEndOfEstimatedPresence() {
        val state = snapshot(listOf(train(seconds = 20)))
        assertEquals(now.plusSeconds(20), state.nextVisualChange(now))
        assertEquals(now.plusSeconds(30), state.nextVisualChange(now.plusSeconds(20)))
    }
    @Test fun sameJourneyAtDifferentQuaysNeverSharesItsState() {
        val a = train(quay = "a")
        val b = train(quay = "b")
        assertNotEquals(a.identity, b.identity)
        val result = merge(snapshot(listOf(a, b)), emptyList(), now.plusSeconds(10), listOf(a.copy(withdrawn = true, recordedAt = now.plusSeconds(10))))
        assertTrue(result.single { it.monitoringRef == "a" }.withdrawn)
        assertFalse(result.single { it.monitoringRef == "b" }.withdrawn)
    }
}
