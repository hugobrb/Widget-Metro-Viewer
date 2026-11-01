package fr.widgetmetroviewer.departures

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal enum class DeparturesState { DEMO, UNSUPPORTED, DATA, NO_DATA, PARTIAL, UNAVAILABLE, QUOTA }
internal enum class DeparturesOrigin { NETWORK, CACHE_FRESH, CACHE_STALE }
internal data class DeparturesResult(
    val state: DeparturesState,
    val snapshot: DeparturesSnapshot? = null,
    val origin: DeparturesOrigin? = null,
    val message: String,
    val retryAt: Instant? = null,
    val traffic: MetroTrafficResult? = null
)

/** Instance partagée application/widget ; aucun abonnement réseau ou travail périodique lancé ici. */
internal class MetroDeparturesRepository internal constructor(
    context: Context,
    private val api: IdfmApiClient = IdfmApiClient(requestAllowed = { MetroPhoneState.canRefresh(context) }),
    private val keyStore: IdfmKeyStore = IdfmKeyStore(context),
    private val usage: IdfmApiUsage = IdfmApiUsage(context),
    private val cache: MetroDeparturesCache = MetroDeparturesCache(context),
    private val clock: Clock = Clock.systemUTC(),
    private val preferLine: Boolean = true,
    private val enableTraffic: Boolean = true,
    private val lineUsage: IdfmApiUsage = IdfmApiUsage(context, service = IdfmApiService.LINE)
) {
    private val lineSource = IdfmLineSource(api, lineUsage, clock)
    private val trafficRepository by lazy { MetroTrafficRepository(context, api, keyStore, clock = clock) }
    private val networkTrafficRepository by lazy { MetroNetworkTrafficRepository(context, api, keyStore, clock = clock) }
    private val appContext = context.applicationContext
    private val refs by lazy { IdfmStationReference.load(appContext) }
    private val lines by lazy { MetroCatalog.load(appContext).lines }
    private fun target(station: String, line: String) = refs.firstOrNull {
        it.lineRef == MetroLineReferences.forLine(line) && MetroCatalog.normalize(it.name) == MetroCatalog.normalize(station)
    }
    private fun trafficContext(line: String): MetroTrafficContext =
        MetroTrafficContext.build(lines, lines.single { MetroLineStyles.key(it.name) == MetroLineStyles.key(line) })
    private val requests = ConcurrentHashMap<String, Mutex>()
    private val latestResults = ConcurrentHashMap<String, DeparturesResult>()
    private val resultChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> = resultChanges
    private fun notifyChanged() { resultChanges.value = resultChanges.value + 1 }
    private val connectionRevision = java.util.concurrent.atomic.AtomicLong()

    fun invalidateConnectionState() { connectionRevision.incrementAndGet(); latestResults.clear(); lineSource.invalidate(); notifyChanged() }

    suspend fun load(station: String, line: String, forceRefresh: Boolean = false,
                     freshFor: Duration = Duration.ofMillis(MetroRefreshPolicy.INTERVAL_MILLIS)): DeparturesResult = withContext(Dispatchers.IO) {
        require(!freshFor.isNegative)
        val startedAt = clock.instant()
        val revision = connectionRevision.get()
        val requestKey = MetroLineStyles.key(line) + "|" + MetroCatalog.normalize(station)
        requests.getOrPut(requestKey) { Mutex() }.withLock {
            var outcome = run result@ {
                var previous: DeparturesSnapshot? = null
                try {
                    val secret = when (val state = keyStore.read()) {
                        IdfmKeyStore.State.Missing -> return@result DeparturesResult(DeparturesState.DEMO,
                            message = "Aucune clé PRIM enregistrée · mode démonstration")
                        IdfmKeyStore.State.Unreadable -> return@result DeparturesResult(DeparturesState.UNAVAILABLE,
                            message = "La clé enregistrée est illisible. Enregistre-la à nouveau.")
                        is IdfmKeyStore.State.Available -> state.value
                    }
                    val target = target(station, line)
                        ?: return@result DeparturesResult(DeparturesState.UNSUPPORTED,
                            message = "Cette station n’est pas encore reliée au temps réel. Aucun autre arrêt n’a été interrogé.")
                    previous = cache.read(target.name, target.lineRef)?.takeIf {
                        it.queriedMonitoringRefs.toSet() == target.monitoringRefs.toSet()
                    }
                    val lastFailure = (latestResults[requestKey] ?: cache.readFailure(target.name, target.lineRef, previous))?.takeIf {
                        it.state in listOf(DeparturesState.QUOTA, DeparturesState.UNAVAILABLE) && it.snapshot?.fetchedAt == previous?.fetchedAt
                    }
                    val now = clock.instant()
                    val age = previous?.let { Duration.between(it.fetchedAt, now) }
                    val notExpired = previous?.let { it.departures.isEmpty() || it.departures.any { item -> item.isUpcoming(now) } } == true
                    if (lastFailure == null && previous != null && age != null && !age.isNegative && notExpired &&
                        ((!forceRefresh && age <= freshFor) || previous.fetchedAt >= startedAt ||
                            (forceRefresh && age < Duration.ofSeconds(30)))) {
                        return@result describe(previous, DeparturesOrigin.CACHE_FRESH)
                    }
                    if (preferLine) {
                        val lineResult = lineSource.fetch(secret, target.lineRef, forceRefresh = forceRefresh)
                        when (val response = lineResult.response) {
                            is IdfmResponse.Success -> {
                                val batch = runCatching { IdfmLineDeparturesParser.parse(response.body, target.lineRef, target.monitoringRefs, clock.instant()) }.getOrNull()
                                if (batch != null && (batch.departures.isNotEmpty() || batch.malformedRecords == 0)) return@result saveBatch(target, batch, revision,
                                    lineResult.fetchedAt ?: clock.instant(),
                                    if (lineResult.fromCache) "Réponse par ligne partagée depuis le cache." else "Passages reçus par ligne · un seul appel pour les deux sens.",
                                    origin = if (lineResult.fromCache) DeparturesOrigin.CACHE_FRESH else DeparturesOrigin.NETWORK)
                                lineUsage.recordFailure("Les passages par ligne ne sont pas lisibles. Repli sur les quais.")
                                lineUsage.postpone(900)
                                lineSource.invalidate()
                            }
                            is IdfmResponse.Failure -> {
                                if (response.error.status == IdfmTestStatus.PAUSED) return@result peek(station, line)
                                if (response.error.status == IdfmTestStatus.INVALID_KEY) return@result unavailable(previous, response.error.message)
                            }
                        }
                    }
                    val budget = usage.read()
                    if (budget.nextAllowedAt > now.toEpochMilli()) return@result lastFailure?.copy(
                        retryAt = Instant.ofEpochMilli(budget.nextAllowedAt)) ?: unavailable(previous,
                        "Une nouvelle tentative sera possible après le délai d’attente.",
                        retryAt = Instant.ofEpochMilli(budget.nextAllowedAt))
                    if (budget.dailyLimit != null && budget.dailyLimit.toLong() - budget.attempts < target.monitoringRefs.size)
                        return@result unavailable(previous, "Plafond local insuffisant pour interroger les deux quais.", quota = true)

                    val parsed = mutableListOf<MetroDeparture>()
                    var malformed = 0
                    val cancelledJourneys = mutableSetOf<String>()
                    var failure: IdfmTestResult? = null
                    val completed = withTimeoutOrNull(50000) {
                        for (ref in target.monitoringRefs) {
                            when (val response = api.fetch(secret, target, ref, onAttempt = { usage.recordAttempt() })) {
                                is IdfmResponse.Failure -> { failure = response.error; break }
                                is IdfmResponse.Success -> {
                                    val batch = IdfmDeparturesParser.parse(response.body, target.lineRef, ref, clock.instant())
                                    parsed.addAll(batch.departures)
                                    malformed += batch.malformedRecords
                                    cancelledJourneys.addAll(batch.cancelledJourneyIds)
                                }
                            }
                        }
                        true
                    } ?: false
                    if (!completed) failure = IdfmTestResult(IdfmTestStatus.TIMEOUT, "Le chargement a dépassé le délai prévu.")
                    if (failure != null) {
                        val error = failure!!
                        if (error.status == IdfmTestStatus.PAUSED) return@result peek(station, line)
                        usage.recordFailure(error.message)
                        if (error.retryAfterSeconds != 0L) usage.postpone(error.retryAfterSeconds ?: 60)
                        val retryAt = usage.read().nextAllowedAt.takeIf { it > clock.millis() }?.let { Instant.ofEpochMilli(it) }
                        return@result unavailable(previous, error.message, error.status == IdfmTestStatus.QUOTA, retryAt)
                    }
                    saveBatch(target, ParsedDepartures(parsed, malformed, cancelledJourneys), revision,
                        clock.instant(), if (preferLine) "Passages reçus · repli sur l’API unitaire." else null)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    runCatching { usage.postpone(60) }
                    unavailable(previous, "Les passages n’ont pas pu être récupérés ou analysés. Réessaie plus tard.")
                }
            }
            if (enableTraffic && target(station, line) != null && outcome.state !in listOf(DeparturesState.DEMO, DeparturesState.UNSUPPORTED)) {
                outcome = outcome.copy(traffic = trafficRepository.load(MetroLineReferences.forLine(line)!!).copy(
                    network = networkTrafficRepository.load(), context = trafficContext(line)))
            }
            if (revision != connectionRevision.get()) return@withLock DeparturesResult(DeparturesState.UNAVAILABLE,
                message = "La configuration de connexion a changé. Recharge les passages.")
            if (revision == connectionRevision.get()) {
                latestResults[requestKey] = outcome
                if (outcome.state in listOf(DeparturesState.UNAVAILABLE, DeparturesState.QUOTA)) runCatching {
                    MetroLineReferences.forLine(line)?.let { cache.writeFailure(station, it, outcome) }
                }
                notifyChanged()
            }
            outcome
        }
    }

    /** Lecture locale uniquement : utilisable dans un BroadcastReceiver, sans requête PRIM. */
    suspend fun peek(station: String, line: String): DeparturesResult = withContext(Dispatchers.IO) {
        val result = peekDepartures(station, line)
        if (enableTraffic && target(station, line) != null && result.state !in listOf(DeparturesState.DEMO, DeparturesState.UNSUPPORTED) &&
            keyStore.read() is IdfmKeyStore.State.Available)
            result.copy(traffic = trafficRepository.peek(MetroLineReferences.forLine(line)!!).copy(
                network = networkTrafficRepository.peek(), context = trafficContext(line))) else result
    }
    private suspend fun peekDepartures(station: String, line: String): DeparturesResult = withContext(Dispatchers.IO) {
        try {
            when (keyStore.read()) {
                IdfmKeyStore.State.Missing -> return@withContext DeparturesResult(DeparturesState.DEMO,
                    message = "Aucune clé PRIM enregistrée · mode démonstration")
                IdfmKeyStore.State.Unreadable -> return@withContext DeparturesResult(DeparturesState.UNAVAILABLE,
                    message = "Clé illisible. Enregistre-la à nouveau.")
                is IdfmKeyStore.State.Available -> Unit
            }
            val target = target(station, line)
                ?: return@withContext DeparturesResult(DeparturesState.UNSUPPORTED,
                    message = "Cette station n’est pas encore reliée au temps réel.")
            var last = latestResults[MetroLineStyles.key(line) + "|" + MetroCatalog.normalize(station)]
            val snapshot = cache.read(target.name, target.lineRef)?.takeIf {
                it.queriedMonitoringRefs.toSet() == target.monitoringRefs.toSet()
            }
            if (last == null) last = cache.readFailure(target.name, target.lineRef, snapshot)
            if (last?.state in listOf(DeparturesState.QUOTA, DeparturesState.UNAVAILABLE) &&
                last?.snapshot?.fetchedAt == snapshot?.fetchedAt) return@withContext last!!
            if (snapshot == null) return@withContext DeparturesResult(DeparturesState.UNAVAILABLE,
                message = "Ouvre l’application pour charger les prochains passages.")
            val age = Duration.between(snapshot.fetchedAt, clock.instant())
            if (age.isNegative || age > Duration.ofMinutes(3)) unavailable(snapshot, "Données à actualiser.")
            else describe(snapshot, DeparturesOrigin.CACHE_FRESH)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { DeparturesResult(DeparturesState.UNAVAILABLE, message = "Données locales indisponibles.") }
    }

    private fun saveBatch(target: IdfmStationReference, batch: ParsedDepartures, revision: Long,
                          fetchedAt: Instant, message: String?, origin: DeparturesOrigin = DeparturesOrigin.NETWORK): DeparturesResult {
        val departures = MetroDirections.get(appContext).classify(batch.departures.filter { it.journeyId !in batch.cancelledJourneyIds }, target.name,
            lines.single { MetroLineReferences.forLine(it.name) == target.lineRef })
            .groupBy { it.direction }.values.flatMap { it.sortedBy { item -> item.at }.take(100) }.sortedBy { it.at }
        val snapshot = DeparturesSnapshot(target.name, target.lineRef, fetchedAt, departures, batch.malformedRecords, target.monitoringRefs)
        if (revision != connectionRevision.get()) return DeparturesResult(DeparturesState.UNAVAILABLE,
            message = "La configuration de connexion a changé. Recharge les passages.")
        val saved = runCatching { cache.write(snapshot) }.isSuccess
        val result = describe(snapshot, origin)
        return result.copy(message = result.message + (message?.let { " " + it }.orEmpty()) +
            if (saved) "" else " · cache local non enregistré")
    }

    private fun describe(snapshot: DeparturesSnapshot, origin: DeparturesOrigin): DeparturesResult {
        val upcoming = snapshot.departures.filter { it.isUpcoming(clock.instant()) }
        val oldSource = upcoming.any { it.recordedAt?.isBefore(clock.instant().minus(Duration.ofMinutes(5))) == true }
        val partial = oldSource || snapshot.ignoredMalformedRecords > 0 || upcoming.any { it.direction == DepartureDirection.UNRESOLVED }
        return DeparturesResult(when {
            partial -> DeparturesState.PARTIAL
            upcoming.isEmpty() -> DeparturesState.NO_DATA
            else -> DeparturesState.DATA
        }, snapshot, origin, when {
            oldSource -> "Passages reçus · certaines données ont été produites il y a plus de cinq minutes."
            partial -> "Passages reçus · certaines données ou certains sens n’ont pas pu être établis."
            upcoming.isEmpty() -> "Aucun prochain passage annoncé."
            origin == DeparturesOrigin.NETWORK -> "Passages récupérés auprès de PRIM."
            else -> "Passages réutilisés depuis le cache récent."
        })
    }

    private fun unavailable(snapshot: DeparturesSnapshot?, message: String, quota: Boolean = false,
                            retryAt: Instant? = null) = DeparturesResult(
        if (quota) DeparturesState.QUOTA else DeparturesState.UNAVAILABLE,
        snapshot, if (snapshot != null) DeparturesOrigin.CACHE_STALE else null,
        message + if (snapshot != null) " · dernières données conservées, non actualisées" else "", retryAt)

    companion object {
        @Volatile private var shared: MetroDeparturesRepository? = null
        fun get(context: Context): MetroDeparturesRepository = shared ?: synchronized(this) {
            shared ?: MetroDeparturesRepository(context.applicationContext).also { shared = it }
        }
    }
}
