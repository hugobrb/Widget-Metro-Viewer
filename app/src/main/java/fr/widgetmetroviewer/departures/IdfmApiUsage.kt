package fr.widgetmetroviewer.departures

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.Clock

internal class IdfmLocalQuotaException : Exception()

internal data class IdfmUsage(val attempts: Int, val dailyLimit: Int?, val nextAllowedAt: Long, val lastError: String? = null)

/** Compteur local des tentatives : il ne représente pas la consommation globale du compte PRIM. */
internal class IdfmApiUsage(context: Context, fileName: String? = null,
                            private val clock: Clock = Clock.systemDefaultZone(),
                            service: IdfmApiService = IdfmApiService.UNIT) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName ?: service.fileName))

    fun read(): IdfmUsage = synchronized(lock) { snapshot(load()) }
    fun recordAttempt(): Unit = synchronized(lock) {
        val json = load()
        val current = snapshot(json)
        if (current.dailyLimit != null && current.attempts >= current.dailyLimit) throw IdfmLocalQuotaException()
        json.remove("lastError")
        json.put("date", LocalDate.now(clock).toString()).put("attempts", (current.attempts.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()))
        save(json)
    }
    fun recordFailure(message: String): Unit = synchronized(lock) { save(load().put("lastError", message.take(500))) }
    fun clearLimit(): Unit = synchronized(lock) { val json = load(); json.remove("limit"); save(json) }
    fun setLimit(limit: Int): Unit = synchronized(lock) { require(limit > 0); save(load().put("limit", limit)) }
    fun postpone(seconds: Long): Unit = synchronized(lock) {
        val json = load()
        val next = clock.millis() + seconds.coerceIn(0, 604800) * 1000
        save(json.put("nextAllowedAt", maxOf(json.optLong("nextAllowedAt", 0), next)))
    }
    private fun load(): JSONObject = runCatching {
        file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }.getOrElse { JSONObject() }
    private fun snapshot(json: JSONObject) = IdfmUsage(
        if (json.optString("date") == LocalDate.now(clock).toString()) json.optInt("attempts", 0).coerceAtLeast(0) else 0,
        json.optInt("limit", 0).takeIf { it > 0 }, json.optLong("nextAllowedAt", 0), json.optString("lastError").takeIf { it.isNotBlank() })
    private fun save(json: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            updates.value = updates.value + 1
        }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    companion object {
        private val lock = Any()
        private val updates = MutableStateFlow(0L)
        val changes: StateFlow<Long> = updates.asStateFlow()
    }
}
