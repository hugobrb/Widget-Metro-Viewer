package fr.widgetmetroviewer.departures

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class MetroNetworkTrafficRepositoryTest {
    @Test fun completeTrafficIsCachedAndAnIncompleteRefreshCannotEraseIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val key = IdfmKeyStore(context, "network_traffic_test_" + suffix, "network_traffic_test_" + suffix + ".enc")
        val clock = TestClock()
        val usageName = "network_traffic_usage_test_" + suffix + ".json"
        val screenName = "network_screen_usage_test_" + suffix + ".json"
        val cacheName = "network_traffic_cache_test_" + suffix + ".json"
        val usage = IdfmApiUsage(context, usageName, clock, IdfmApiService.TRAFFIC_REPORTS)
        val screens = IdfmApiUsage(context, screenName, clock, IdfmApiService.TRAFFIC)
        var calls = 0
        var partial = false
        val api = IdfmApiClient { url ->
            calls++
            assertEquals("prim.iledefrance-mobilites.fr", url.host)
            assertTrue(url.path.contains("line_reports"))
            val page = url.query.substringAfter("start_page=").toInt()
            val payload = """{"line_reports":[{"line":{"id":"line:IDFM:C01381","links":[{"type":"disruption","id":"delay"}]},"pt_objects":[]}],"disruptions":[{"id":"delay","status":"active","severity":{"effect":"SIGNIFICANT_DELAYS"},"messages":[{"text":"Le trafic est ralenti."}],"application_periods":[{"begin":"20261031T120000","end":"20261031T180000"}]}],"pagination":{"start_page":0,"items_per_page":100,"total_result":1}}"""
            val body = JSONObject(payload).apply { if (partial) getJSONObject("pagination").put("total_result", 101) }.toString()
            Fake(url, if (page == 1) 503 else 200, body)
        }
        try {
            assertTrue(key.save("test_only_not_a_real_key"))
            val repo = MetroNetworkTrafficRepository(context, api, key, usage, clock, cacheName)
            val original = repo.load()
            assertNull(original.error)
            assertEquals("SIGNIFICANT_DELAYS", original.snapshot!!.disruptions.single().effect)
            repo.load()
            assertEquals(1, calls)
            assertEquals(1, usage.read().attempts)
            assertEquals(0, screens.read().attempts)
            clock.now = clock.now.plusSeconds(121)
            partial = true
            val failed = repo.load()
            assertNotNull(failed.error)
            assertEquals(original.snapshot.fetchedAt, failed.snapshot!!.fetchedAt)
            assertEquals(3, calls)
            val restored = MetroNetworkTrafficRepository(context, api, key, usage, clock, cacheName)
            assertNotNull(restored.peek().error)
            restored.load()
            assertEquals(3, calls)
            clock.now = clock.now.plusSeconds(121)
            usage.setLimit(3)
            assertTrue(restored.load().error!!.contains("Plafond"))
            assertEquals(3, calls)
            key.delete()
            assertNull(restored.load().snapshot)
            assertEquals(3, calls)
        } finally {
            key.delete()
            listOf(usageName, screenName, cacheName).forEach { File(context.noBackupFilesDir, it).delete() }
        }
    }
    private class TestClock(var now: Instant = Instant.parse("2026-10-31T12:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private class Fake(url: URL, private val code: Int, private val body: String) : HttpsURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
