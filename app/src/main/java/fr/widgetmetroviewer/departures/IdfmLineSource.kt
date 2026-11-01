package fr.widgetmetroviewer.departures

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class IdfmLineResult(val response: IdfmResponse, val fetchedAt: Instant? = null, val fromCache: Boolean = false)

/** Un échange par ligne, réutilisable lorsque la sélection de station change pendant la minute. */
internal class IdfmLineSource(private val api: IdfmApiClient, private val usage: IdfmApiUsage,
                              private val clock: Clock) {
    private data class Cached(val lineRef: String, val at: Instant, val response: IdfmResponse.Success)
    private val mutex = Mutex()
    @Volatile private var cached: Cached? = null
    private val revision = java.util.concurrent.atomic.AtomicLong()
    fun invalidate() { revision.incrementAndGet(); cached = null }
    suspend fun fetch(key: String, lineRef: String, forceRefresh: Boolean = false): IdfmLineResult = mutex.withLock {
        cached?.let {
            if (it.lineRef == lineRef && Duration.between(it.at, clock.instant()).let { age -> !age.isNegative && age < (if (forceRefresh) Duration.ofSeconds(30) else Duration.ofMinutes(1)) })
                return@withLock IdfmLineResult(it.response, it.at, fromCache = true)
        }
        val budget = usage.read()
        if (budget.nextAllowedAt > clock.millis()) return@withLock IdfmLineResult(IdfmResponse.Failure(
            IdfmTestResult(IdfmTestStatus.SERVICE, "API par ligne temporairement en attente.", 0)))
        if (budget.dailyLimit != null && budget.attempts >= budget.dailyLimit) return@withLock IdfmLineResult(IdfmResponse.Failure(
            IdfmTestResult(IdfmTestStatus.QUOTA, "Plafond local de l’API par ligne atteint.", 0)))
        val startedRevision = revision.get()
        val result = api.fetchLine(key, lineRef) { usage.recordAttempt() }
        val fetchedAt = clock.instant()
        when (result) {
            is IdfmResponse.Success -> if (startedRevision == revision.get()) cached = Cached(lineRef, fetchedAt, result)
            is IdfmResponse.Failure -> {
                if (result.error.status != IdfmTestStatus.PAUSED) {
                    usage.recordFailure(result.error.message)
                    if (result.error.retryAfterSeconds != 0L) usage.postpone(result.error.retryAfterSeconds ?: when (result.error.status) {
                        IdfmTestStatus.FORBIDDEN -> 3600
                        IdfmTestStatus.FORMAT -> 900
                        else -> 60
                    })
                }
            }
        }
        IdfmLineResult(result, fetchedAt)
    }
}
