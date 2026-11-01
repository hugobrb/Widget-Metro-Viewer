package fr.widgetmetroviewer.departures

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class MetroTrackerCacheTest {
    @Test fun trackingAndWithdrawalSurviveCacheReloadAndRejectAnOlderRevision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = "tracker_cache_" + UUID.randomUUID()
        val cache = MetroDeparturesCache(context, folder)
        val now = Instant.parse("2026-11-01T12:00:00Z")
        val item = MetroDeparture("trip-1", "STIF:StopPoint:Q:490789:", "Rosny-Bois-Perrier", null, null,
            now.plusSeconds(120), DepartureTimeBasis.ESTIMATED_ARRIVAL, now, false, DepartureDirection.FORWARD, now, true)
        val original = DeparturesSnapshot("Montreuil - Hôpital", "STIF:Line::C01381:", now, listOf(item), queriedMonitoringRefs = listOf(item.monitoringRef))
        try {
            cache.write(original)
            val read = cache.read(original.station, original.lineRef)!!
            assertEquals(original, read)
            val old = item.copy(recordedAt = now.minusSeconds(10), withdrawn = false)
            val merged = MetroDepartureTracker.merge(read, listOf(old), emptyList(), now.plusSeconds(20), now.plusSeconds(20))
            assertTrue(merged.single().withdrawn)
        } finally {
            File(context.noBackupFilesDir, folder).listFiles()?.forEach { it.delete() }
            File(context.noBackupFilesDir, folder).delete()
        }
    }
}
