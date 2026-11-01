package fr.widgetmetroviewer.departures

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class MetroNetworkTrafficRepository(context: Context,
    private val api: IdfmApiClient = IdfmApiClient(requestAllowed = { MetroPhoneState.canRefresh(context) }),
    private val keyStore: IdfmKeyStore = IdfmKeyStore(context),
    private val usage: IdfmApiUsage = IdfmApiUsage(context, service = IdfmApiService.TRAFFIC_REPORTS),
    private val clock: Clock = Clock.systemUTC(), fileName: String = "idfm_metro_circulation.json") {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))
    private val requestLock = Mutex()
    private val diskLock = Any()
    private fun read(): MetroNetworkTrafficResult = synchronized(diskLock) { runCatching {
        val bytes = file.readFully()
        require(bytes.size <= 8388608)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val fetched = json.optString("fetchedAt").takeIf { it.isNotBlank() }?.let { Instant.parse(it) }
        val ids = json.getJSONArray("lineIds").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        val events = IdfmSiri.objects(json.opt("disruptions")).map { row ->
            fun strings(field: String) = row.getJSONArray(field).let { array -> (0 until array.length()).map { array.getString(it) } }
            MetroNetworkDisruption(row.getString("id"), row.getString("lineId"), row.getString("text"), row.getString("effect"),
                row.getBoolean("lineWide"), strings("stationNames"), strings("stationIds"),
                IdfmSiri.objects(row.opt("periods")).map { MetroTrafficPeriod(Instant.parse(it.getString("begin")), Instant.parse(it.getString("end"))) },
                row.getString("status"), row.getString("severityName"))
        }
        MetroNetworkTrafficResult(fetched?.let { MetroNetworkTrafficSnapshot(it, ids, events) }, json.optString("error").takeIf { it.isNotBlank() })
    }.getOrElse { MetroNetworkTrafficResult(error = "État de circulation non chargé.") } }
    private fun write(result: MetroNetworkTrafficResult): Unit = synchronized(diskLock) {
        val rows = JSONArray()
        result.snapshot?.disruptions?.forEach { event ->
            val periods = JSONArray()
            event.periods.forEach { periods.put(JSONObject().put("begin", it.begin.toString()).put("end", it.end.toString())) }
            rows.put(JSONObject().put("id", event.id).put("lineId", event.lineId).put("text", event.text).put("effect", event.effect)
                .put("lineWide", event.lineWide).put("stationNames", JSONArray(event.stationNames)).put("stationIds", JSONArray(event.stationIds))
                .put("periods", periods).put("status", event.reportedStatus).put("severityName", event.severityName))
        }
        val json = JSONObject().put("version", 1).put("fetchedAt", result.snapshot?.fetchedAt?.toString().orEmpty())
            .put("lineIds", JSONArray(result.snapshot?.lineIds.orEmpty())).put("disruptions", rows).put("error", result.error.orEmpty())
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    suspend fun peek(): MetroNetworkTrafficResult = withContext(Dispatchers.IO) { read() }
    suspend fun load(): MetroNetworkTrafficResult = withContext(Dispatchers.IO) {
        requestLock.withLock {
            val previous = read()
            val key = (keyStore.read() as? IdfmKeyStore.State.Available)?.value ?: return@withLock MetroNetworkTrafficResult()
            val age = previous.snapshot?.let { Duration.between(it.fetchedAt, clock.instant()) }
            if (previous.error == null && age != null && !age.isNegative && age < Duration.ofMinutes(2)) return@withLock previous
            fun failed(message: String) = previous.copy(error = message).also { runCatching { write(it) } }
            val budget = usage.read()
            if (budget.dailyLimit != null && budget.attempts >= budget.dailyLimit)
                return@withLock failed("Plafond local de l’API état de circulation atteint.")
            if (budget.nextAllowedAt > clock.millis()) return@withLock failed(previous.error ?: "État de circulation en attente.")
            val result = try {
                val completed = withTimeoutOrNull(60000) {
                    val ids = linkedSetOf<String>()
                    val events = mutableListOf<MetroNetworkDisruption>()
                    var finished = false
                    for (page in 0 until 5) {
                        when (val response = api.fetchTrafficReports(key, page) { usage.recordAttempt() }) {
                            is IdfmResponse.Failure -> throw TrafficFailure(response.error)
                            is IdfmResponse.Success -> {
                                val data = IdfmNetworkTrafficParser.parse(response.body, page)
                                ids.addAll(data.lineIds)
                                events.addAll(data.disruptions)
                                if (!data.hasNext) { finished = true; break }
                            }
                        }
                    }
                    require(finished) // Une pagination tronquée ne peut pas prouver l'absence de perturbation.
                    MetroNetworkTrafficSnapshot(clock.instant(), ids, events.distinctBy { it.lineId + "|" + it.id })
                } ?: throw TrafficFailure(IdfmTestResult(IdfmTestStatus.TIMEOUT, "Le chargement de l’état de circulation a expiré."))
                MetroNetworkTrafficResult(completed)
            } catch (error: CancellationException) { throw error }
            catch (error: TrafficFailure) {
                if (error.result.status == IdfmTestStatus.PAUSED) return@withLock previous
                usage.recordFailure(error.result.message)
                if (error.result.retryAfterSeconds != 0L) usage.postpone(error.result.retryAfterSeconds ?:
                    if (error.result.status == IdfmTestStatus.FORBIDDEN) 1800 else 120)
                previous.copy(error = error.result.message)
            } catch (_: IdfmLocalQuotaException) {
                previous.copy(error = "Plafond local de l’API état de circulation atteint.")
            } catch (_: Exception) {
                usage.postpone(120)
                usage.recordFailure("Les informations structurées de circulation ne sont pas disponibles.")
                previous.copy(error = "Les informations structurées de circulation ne sont pas disponibles.")
            }
            runCatching { write(result) }
            result
        }
    }
    private class TrafficFailure(val result: IdfmTestResult) : Exception()
}
