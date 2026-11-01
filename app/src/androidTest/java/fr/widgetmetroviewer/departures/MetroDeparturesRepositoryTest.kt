package fr.widgetmetroviewer.departures

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class MetroDeparturesRepositoryTest {
    @Test fun simultaneousLoadsShareCacheAndNetworkFailureKeepsLastCompleteSnapshot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val key = IdfmKeyStore(context, "idfm_repo_test_$suffix", "idfm_repo_test_$suffix.enc")
        val usageName = "idfm_repo_usage_test_$suffix.json"
        val clock = MutableClock(Instant.now())
        val usage = IdfmApiUsage(context, usageName, clock)
        val folderName = "idfm_repo_cache_test_$suffix"
        val cache = MetroDeparturesCache(context, folderName)
        val calls = AtomicInteger(0)
        var unavailable = false
        val api = IdfmApiClient { url ->
            calls.incrementAndGet()
            val ref = if (url.query.contains("490766")) "STIF:StopPoint:Q:490766:" else "STIF:StopPoint:Q:490789:"
            val destination = if (ref.contains("490766")) "Rosny-Bois-Perrier" else "Châtelet"
            val visit = JSONObject().put("RecordedAtTime", clock.now.toString()).put("MonitoredVehicleJourney", JSONObject()
                .put("LineRef", JSONObject().put("value", "STIF:Line::C01381:"))
                .put("DestinationName", JSONArray().put(JSONObject().put("value", destination)))
                .put("MonitoredCall", JSONObject().put("StopPointRef", JSONObject().put("value", ref))
                    .put("ExpectedDepartureTime", clock.now.plusSeconds(600).toString())))
            val body = JSONObject().put("Siri", JSONObject().put("ServiceDelivery", JSONObject()
                .put("StopMonitoringDelivery", JSONArray().put(JSONObject().put("Status", true)
                    .put("MonitoredStopVisit", JSONArray().put(visit)))))).toString()
            FakeConnection(url, if (unavailable) 503 else 200, body)
        }
        try {
            assertTrue(key.save("test_only_not_a_real_key"))
            val repository = MetroDeparturesRepository(context, api, key, usage, cache, clock, preferLine = false, enableTraffic = false)
            val results = listOf(async { repository.load("Montreuil–Hôpital", "11") },
                async { repository.load("Montreuil - Hôpital", "11") }).awaitAll()
            assertEquals(2, calls.get())
            assertTrue(results.all { it.state == DeparturesState.DATA })
            assertEquals(DeparturesOrigin.CACHE_FRESH, repository.peek("Montreuil - Hôpital", "11").origin)
            assertEquals(2, calls.get())
            var first = results.first().snapshot!!
            assertEquals(1, first.next(DepartureDirection.FORWARD, clock.now).size)
            assertEquals(1, first.next(DepartureDirection.BACKWARD, clock.now).size)
            val recreated = MetroDeparturesRepository(context, api, key, usage, MetroDeparturesCache(context, folderName), clock, preferLine = false, enableTraffic = false)
            assertEquals(DeparturesOrigin.CACHE_FRESH, recreated.load("Montreuil - Hôpital", "11").origin)
            assertEquals(2, calls.get())
            clock.now = clock.now.plusSeconds(59)
            assertEquals(DeparturesOrigin.CACHE_FRESH, recreated.load("Montreuil - Hôpital", "11").origin)
            assertEquals(2, calls.get())
            clock.now = clock.now.plusSeconds(2)
            val refreshed = recreated.load("Montreuil - Hôpital", "11")
            assertEquals(DeparturesOrigin.NETWORK, refreshed.origin)
            assertEquals(4, calls.get())
            first = refreshed.snapshot!!
            assertEquals(DeparturesState.UNSUPPORTED, repository.load("Station inexistante", "1").state)
            assertEquals(4, calls.get())
            clock.now = clock.now.plus(Duration.ofMinutes(4))
            unavailable = true
            val stale = repository.load("Montreuil - Hôpital", "11")
            assertEquals(DeparturesOrigin.CACHE_STALE, stale.origin)
            assertEquals(first.fetchedAt, stale.snapshot!!.fetchedAt)
            assertEquals(DeparturesOrigin.CACHE_STALE, repository.peek("Montreuil - Hôpital", "11").origin)
            val afterFailure = calls.get()
            val restarted = MetroDeparturesRepository(context, api, key, usage, MetroDeparturesCache(context, folderName), clock, preferLine = false, enableTraffic = false)
            assertEquals(stale.message, restarted.peek("Montreuil - Hôpital", "11").message)
            assertEquals(DeparturesOrigin.CACHE_STALE, restarted.load("Montreuil - Hôpital", "11").origin)
            assertEquals(afterFailure, calls.get())
            repository.load("Montreuil - Hôpital", "11")
            assertEquals(afterFailure, calls.get())
            key.delete()
            assertEquals(DeparturesState.DEMO, repository.peek("Montreuil - Hôpital", "11").state)
            assertEquals(DeparturesState.DEMO, repository.load("Montreuil - Hôpital", "11").state)
        } finally {
            key.delete()
            File(context.noBackupFilesDir, usageName).delete()
            File(context.noBackupFilesDir, folderName).listFiles()?.forEach { it.delete() }
            File(context.noBackupFilesDir, folderName).delete()
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private class FakeConnection(url: URL, private val code: Int, private val body: String) : HttpsURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int = code
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
