package fr.widgetmetroviewer.departures

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class IdfmApiClientTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun station() = IdfmStationReference.load(context).first()
    private val valid = """{"Siri":{"ServiceDelivery":{"StopMonitoringDelivery":[{"Status":true,"MonitoredStopVisit":[{}]}]}}}"""

    @Test fun lockedPhoneDoesNotOpenConnectionOrCountAttempt() = runBlocking {
        var connections = 0
        var attempts = 0
        val client = IdfmApiClient(requestAllowed = { false }, connect = { connections++; FakeConnection(200, valid) })
        val result = client.test("test_only", station()) { attempts++ }
        assertEquals(IdfmTestStatus.PAUSED, result.status)
        assertEquals(0, connections)
        assertEquals(0, attempts)
    }

    @Test fun lockingPhoneCancelsAndDisconnectsAnOngoingRequest() = runBlocking {
        val allowed = AtomicBoolean(true)
        val fake = FakeConnection(200, valid, blocked = true)
        val client = IdfmApiClient(requestAllowed = { allowed.get() }, connect = { fake })
        val result = async { client.test("test_only", station()) }
        assertTrue(withContext(Dispatchers.IO) { fake.started.await(5, TimeUnit.SECONDS) })
        allowed.set(false)
        withTimeout(3000) { result.join() }
        assertTrue(result.isCancelled)
        assertTrue(fake.disconnected.get())
    }

    @Test fun verifiedReferencesMatchTheOfficialCatalog() {
        val stations = IdfmStationReference.load(context)
        val catalog = MetroCatalog.load(context).lines
        assertEquals(405, stations.size)
        assertEquals(16, stations.map { it.lineRef }.distinct().size)
        for (line in catalog) for (stop in line.stations) {
            val station = stations.single { it.lineRef == MetroLineReferences.forLine(line.name) &&
                MetroCatalog.normalize(it.name) == MetroCatalog.normalize(stop.name) }
            assertTrue(station.monitoringRefs.isNotEmpty())
            assertTrue(station.monitoringRefs.all { it.matches(Regex("STIF:StopPoint:Q:[0-9]+:")) })
        }
    }

    @Test fun successfulHttpsTestUsesHeaderAndCountsOneAttempt() = runBlocking {
        val fake = FakeConnection(200, valid)
        var attempts = 0
        val client = IdfmApiClient { fake }
        val result = client.test("test_only_not_a_real_api_key", station()) { attempts++ }
        assertEquals(IdfmTestStatus.VALID, result.status)
        assertEquals(1, attempts)
        assertEquals("test_only_not_a_real_api_key", fake.getRequestProperty("apikey"))
        assertFalse(fake.instanceFollowRedirects)
        assertTrue(fake.disconnected.get())
        assertFalse(IdfmApiClient.requestUrl(station()).toString().contains("test_only"))
    }

    @Test fun invalidKeysQuotaAndEmptyResponsesHaveDistinctStates() {
        assertEquals(IdfmTestStatus.INVALID_KEY, IdfmApiClient.httpError(401, null).status)
        assertEquals(IdfmTestStatus.FORBIDDEN, IdfmApiClient.httpError(403, null).status)
        val quota = IdfmApiClient.httpError(429, "120")
        assertEquals(IdfmTestStatus.QUOTA, quota.status)
        assertEquals(120L, quota.retryAfterSeconds)
        assertEquals(IdfmTestStatus.SERVICE, IdfmApiClient.httpError(503, null).status)
        assertEquals(IdfmTestStatus.FORMAT, IdfmApiClient.parseResponse("{unexpected}").status)
        assertEquals(IdfmTestStatus.NO_DATA, IdfmApiClient.parseResponse(valid.replace("[{}]", "[]")).status)
        assertEquals(IdfmTestStatus.SERVICE, IdfmApiClient.parseResponse(valid.replace("true", "false")).status)
    }

    @Test fun cancellationDisconnectsTheRequest() = runBlocking {
        val fake = FakeConnection(200, valid, blocked = true)
        val result = async { IdfmApiClient { fake }.test("test_only", station()) }
        assertTrue(withContext(Dispatchers.IO) { fake.started.await(5, TimeUnit.SECONDS) })
        result.cancelAndJoin()
        assertTrue(fake.disconnected.get())
    }

    @Test fun localUsageSurvivesRecreationAndPreservesRetryDelay() {
        val name = "idfm_usage_instrumentation_test_only.json"
        val file = java.io.File(context.noBackupFilesDir, name)
        file.delete()
        try {
            val usage = IdfmApiUsage(context, name)
            usage.setLimit(1000)
            usage.recordAttempt()
            usage.postpone(120)
            val restored = IdfmApiUsage(context, name).read()
            assertEquals(1, restored.attempts)
            assertEquals(1000, restored.dailyLimit)
            assertTrue(restored.nextAllowedAt > System.currentTimeMillis())
        } finally { file.delete() }
    }

    private class FakeConnection(private val code: Int, private val body: String, private val blocked: Boolean = false)
        : HttpsURLConnection(URL("https://prim.iledefrance-mobilites.fr/marketplace/stop-monitoring")) {
        val disconnected = AtomicBoolean(false)
        val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        override fun connect() {}
        override fun disconnect() { disconnected.set(true); released.countDown() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int {
            started.countDown()
            if (blocked) released.await(5, TimeUnit.SECONDS)
            return code
        }
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
