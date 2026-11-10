package fr.widgetmetroviewer.departures

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class MetroTrafficRepository(context: Context,
    private val api: IdfmApiClient = IdfmApiClient(requestAllowed = { MetroInternetState.isConnected(context) }),
    private val keyStore: IdfmKeyStore = IdfmKeyStore(context),
    private val usage: IdfmApiUsage = IdfmApiUsage(context, service = IdfmApiService.TRAFFIC),
    private val clock: Clock = Clock.systemUTC(), folderName: String = "idfm_traffic") {
    private val folder = File(context.applicationContext.noBackupFilesDir, folderName)
    private val mutex = Mutex()
    private val diskLock = Any()
    private fun file(lineRef: String): AtomicFile {
        require(Regex("STIF:Line::C[0-9]+:").matches(lineRef))
        return AtomicFile(File(folder, lineRef.removePrefix("STIF:Line::").removeSuffix(":") + ".json"))
    }
    private fun read(lineRef: String): MetroTrafficResult = synchronized(diskLock) { runCatching {
        val bytes = file(lineRef).readFully()
        require(bytes.size <= 262144)
        val data = JSONObject(String(bytes, Charsets.UTF_8))
        val fetched = data.optString("fetchedAt").takeIf { it.isNotBlank() }?.let { Instant.parse(it) }
        val messages = IdfmSiri.objects(data.opt("messages")).map {
            MetroTrafficMessage(it.getString("id"), it.getString("text"), it.getString("channel"),
                it.optString("validUntil").takeIf { text -> text.isNotBlank() }?.let { text -> Instant.parse(text) },
                it.optString("recordedAt").takeIf { text -> text.isNotBlank() }?.let { text -> Instant.parse(text) })
        }
        MetroTrafficResult(fetched?.let { MetroTrafficSnapshot(lineRef, it, messages) },
            data.optString("error").takeIf { it.isNotBlank() })
    }.getOrElse { MetroTrafficResult(error = "Messages trafic non chargés.") } }

    private fun write(lineRef: String, result: MetroTrafficResult): Unit = synchronized(diskLock) {
        check(folder.isDirectory || folder.mkdirs())
        val rows = JSONArray()
        result.snapshot?.messages?.forEach { item -> rows.put(JSONObject().put("id", item.id).put("text", item.text)
            .put("channel", item.channel).put("validUntil", item.validUntil?.toString().orEmpty())
            .put("recordedAt", item.recordedAt?.toString().orEmpty())) }
        val data = JSONObject().put("fetchedAt", result.snapshot?.fetchedAt?.toString().orEmpty())
            .put("messages", rows).put("error", result.error.orEmpty())
        val output = file(lineRef)
        val stream = output.startWrite()
        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); output.finishWrite(stream) }
        catch (error: Exception) { output.failWrite(stream); throw error }
    }
    suspend fun peek(lineRef: String): MetroTrafficResult = withContext(Dispatchers.IO) {
        read(lineRef)
    }
    suspend fun load(lineRef: String): MetroTrafficResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = read(lineRef)
            val key = (keyStore.read() as? IdfmKeyStore.State.Available)?.value ?: return@withLock MetroTrafficResult()
            val age = previous.snapshot?.let { Duration.between(it.fetchedAt, clock.instant()) }
            if (previous.error == null && age != null && !age.isNegative && age < Duration.ofMinutes(2)) return@withLock previous
            val budget = usage.read()
            if (budget.nextAllowedAt > clock.millis()) return@withLock previous.copy(error = previous.error ?: "Trafic en attente de nouvelle tentative.").also { runCatching { write(lineRef, it) } }
            if (budget.dailyLimit != null && budget.attempts >= budget.dailyLimit) return@withLock previous.copy(error = "Plafond local des messages trafic atteint.").also { runCatching { write(lineRef, it) } }
            val result = try {
                when (val response = api.fetchTraffic(key, lineRef) { usage.recordAttempt() }) {
                    is IdfmResponse.Success -> MetroTrafficResult(IdfmTrafficParser.parse(response.body, lineRef, clock.instant()))
                    is IdfmResponse.Failure -> {
                        if (response.error.status == IdfmTestStatus.PAUSED) return@withLock previous
                        usage.recordFailure(response.error.message)
                        if (response.error.retryAfterSeconds != 0L) usage.postpone(response.error.retryAfterSeconds ?: 120)
                        previous.copy(error = response.error.message)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                usage.postpone(120)
                previous.copy(error = "Les messages trafic ne sont pas disponibles.")
            }
            runCatching { write(lineRef, result) }
            result
        }
    }
}
