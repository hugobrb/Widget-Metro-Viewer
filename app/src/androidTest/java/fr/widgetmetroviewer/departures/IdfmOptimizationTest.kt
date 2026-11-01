package fr.widgetmetroviewer.departures

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
class IdfmOptimizationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class TestClock(var now: Instant = Instant.parse("2026-10-31T12:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private class Fake(url: URL, val code: Int, val body: String) : HttpsURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
    private val emptyLine = """{"Siri":{"ServiceDelivery":{"EstimatedTimetableDelivery":[{"Status":true,"EstimatedJourneyVersionFrame":[]}]}}}"""
    private val emptyUnit = """{"Siri":{"ServiceDelivery":{"StopMonitoringDelivery":[{"Status":true,"MonitoredStopVisit":[]}]}}}"""
    private val emptyTraffic = """{"Siri":{"ServiceDelivery":{"GeneralMessageDelivery":[{"Status":true,"GeneralMessage":[]}]}}}"""

    @Test fun oneLineCallServesDifferentStationsAndForbiddenAccessFallsBackToTwoUnitCalls() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val key = IdfmKeyStore(context, "optimization_$suffix", "optimization_$suffix.enc")
        val clock = TestClock()
        val unitName = "optimization_unit_$suffix.json"
        val lineName = "optimization_line_$suffix.json"
        val folder = "optimization_cache_$suffix"
        val unitUsage = IdfmApiUsage(context, unitName, clock)
        val lineUsage = IdfmApiUsage(context, lineName, clock, IdfmApiService.LINE)
        var lineCalls = 0
        var unitCalls = 0
        var forbidden = false
        val api = IdfmApiClient { url ->
            if (url.path.endsWith("requete-ligne")) {
                lineCalls++
                Fake(url, if (forbidden) 403 else 200, emptyLine)
            } else {
                assertTrue(url.path.endsWith("stop-monitoring"))
                unitCalls++
                Fake(url, 200, emptyUnit)
            }
        }
        try {
            assertTrue(key.save("test_only_not_a_real_key"))
            val repo = MetroDeparturesRepository(context, api, key, unitUsage, MetroDeparturesCache(context, folder), clock,
                enableTraffic = false, lineUsage = lineUsage)
            assertEquals(DeparturesState.NO_DATA, repo.load("Montreuil - Hôpital", "11").state)
            assertEquals(DeparturesState.NO_DATA, repo.load("Mairie des Lilas", "11").state)
            assertEquals(1, lineCalls)
            assertEquals(0, unitCalls)
            assertEquals(1, lineUsage.read().attempts)
            assertEquals(0, unitUsage.read().attempts)
            clock.now = clock.now.plusSeconds(61)
            forbidden = true
            assertEquals(DeparturesState.NO_DATA, repo.load("Montreuil - Hôpital", "11").state)
            assertEquals(2, lineCalls)
            assertEquals(2, unitCalls)
            assertTrue(lineUsage.read().nextAllowedAt > clock.millis())
            assertEquals(0L, unitUsage.read().nextAllowedAt)
            clock.now = clock.now.plusSeconds(61)
            repo.load("Montreuil - Hôpital", "11")
            assertEquals(2, lineCalls)
            assertEquals(4, unitCalls)
        } finally {
            key.delete()
            File(context.noBackupFilesDir, unitName).delete()
            File(context.noBackupFilesDir, lineName).delete()
            File(context.noBackupFilesDir, folder).listFiles()?.forEach { it.delete() }
            File(context.noBackupFilesDir, folder).delete()
        }
    }

    @Test fun manualRefreshBypassesMinuteCacheButKeepsThirtySecondProtection() = runBlocking {
        val clock = TestClock()
        val name = "manual_refresh_" + UUID.randomUUID() + ".json"
        val usage = IdfmApiUsage(context, name, clock, IdfmApiService.LINE)
        var calls = 0
        val source = IdfmLineSource(IdfmApiClient { url -> calls++; Fake(url, 200, emptyLine) }, usage, clock)
        try {
            val line = "STIF:Line::C01381:"
            source.fetch("test_only", line)
            clock.now = clock.now.plusSeconds(20)
            assertTrue(source.fetch("test_only", line, forceRefresh = true).fromCache)
            assertEquals(1, calls)
            clock.now = clock.now.plusSeconds(11)
            assertTrue(source.fetch("test_only", line).fromCache)
            assertFalse(source.fetch("test_only", line, forceRefresh = true).fromCache)
            assertEquals(2, calls)
            assertTrue(source.fetch("test_only", line, forceRefresh = true).fromCache)
            assertEquals(2, calls)
            assertEquals(2, usage.read().attempts)
        } finally { File(context.noBackupFilesDir, name).delete() }
    }

    @Test fun serviceBudgetsAreIndependentPersistedAndResetAtMidnight() {
        val suffix = UUID.randomUUID().toString()
        val clock = TestClock()
        val unitName = "budget_unit_$suffix.json"
        val lineName = "budget_line_$suffix.json"
        val trafficName = "budget_traffic_$suffix.json"
        try {
            val unit = IdfmApiUsage(context, unitName, clock)
            val line = IdfmApiUsage(context, lineName, clock, IdfmApiService.LINE)
            val traffic = IdfmApiUsage(context, trafficName, clock, IdfmApiService.TRAFFIC)
            line.setLimit(1)
            line.recordAttempt()
            line.postpone(300)
            assertTrue(runCatching { line.recordAttempt() }.exceptionOrNull() is IdfmLocalQuotaException)
            unit.recordAttempt()
            traffic.recordAttempt()
            assertEquals(0L, unit.read().nextAllowedAt)
            assertEquals(0L, traffic.read().nextAllowedAt)
            assertNull(unit.read().dailyLimit)
            assertEquals(1, IdfmApiUsage(context, lineName, clock).read().attempts)
            clock.now = clock.now.plusSeconds(86400)
            assertEquals(0, line.read().attempts)
            assertEquals(1, line.read().dailyLimit)
            line.clearLimit()
            assertNull(line.read().dailyLimit)
        } finally {
            listOf(unitName, lineName, trafficName).forEach { File(context.noBackupFilesDir, it).delete() }
        }
    }

    @Test fun trafficCacheClearsWithdrawnMessagesAndPreservesFailureAcrossRecreation() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val key = IdfmKeyStore(context, "traffic_test_$suffix", "traffic_test_$suffix.enc")
        val clock = TestClock()
        val usageName = "traffic_usage_test_$suffix.json"
        val folder = "traffic_cache_test_$suffix"
        val usage = IdfmApiUsage(context, usageName, clock, IdfmApiService.TRAFFIC)
        val lineRef = "STIF:Line::C01381:"
        val message = """{"Siri":{"ServiceDelivery":{"GeneralMessageDelivery":[{"Status":true,"GeneralMessage":[{"InfoMessageIdentifier":"id","InfoChannelRef":"Perturbation","Content":{"Message":[{"MessageText":{"value":"Incident de test"}}]}}]}]}}}"""
        var calls = 0
        var body = message
        var code = 200
        val api = IdfmApiClient { url -> calls++; Fake(url, code, body) }
        try {
            assertTrue(key.save("test_only_not_a_real_key"))
            val repo = MetroTrafficRepository(context, api, key, usage, clock, folder)
            assertEquals(1, repo.load(lineRef).active(clock.now).size)
            repo.load(lineRef)
            assertEquals(1, calls)
            clock.now = clock.now.plusSeconds(121)
            body = emptyTraffic
            assertTrue(repo.load(lineRef).active(clock.now).isEmpty())
            assertEquals(2, calls)
            clock.now = clock.now.plusSeconds(121)
            code = 503
            assertNotNull(repo.load(lineRef).error)
            assertEquals(3, calls)
            val restored = MetroTrafficRepository(context, api, key, usage, clock, folder)
            assertNotNull(restored.peek(lineRef).error)
            restored.load(lineRef)
            assertEquals(3, calls)
        } finally {
            key.delete()
            File(context.noBackupFilesDir, usageName).delete()
            File(context.noBackupFilesDir, folder).listFiles()?.forEach { it.delete() }
            File(context.noBackupFilesDir, folder).delete()
        }
    }
}
