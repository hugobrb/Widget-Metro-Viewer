package fr.widgetmetroviewer.departures

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

internal class MetroDeparturesCache(context: Context, folderName: String = "idfm_departures") {
    private val folder = File(context.applicationContext.noBackupFilesDir, folderName)
    private fun file(station: String, lineRef: String): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(
            (lineRef + "|" + MetroCatalog.normalize(station)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(folder, "$hash.json"))
    }

    fun read(station: String, lineRef: String): DeparturesSnapshot? = synchronized(lock) {
        runCatching {
            val data = file(station, lineRef).readFully()
            require(data.size <= 1048576)
            val json = JSONObject(String(data, Charsets.UTF_8))
            require(json.getInt("version") == 2 && json.getString("lineRef") == lineRef)
            require(MetroCatalog.normalize(json.getString("station")) == MetroCatalog.normalize(station))
            val rows = json.getJSONArray("departures")
            val refs = json.getJSONArray("monitoringRefs")
            require(rows.length() <= 400)
            DeparturesSnapshot(station, lineRef, Instant.parse(json.getString("fetchedAt")),
                (0 until rows.length()).map { i ->
                    val row = rows.getJSONObject(i)
                    MetroDeparture(row.getString("journeyId"), row.getString("monitoringRef"),
                        row.getString("destinationName"), row.optString("destinationRef").takeIf { it.isNotEmpty() },
                        row.optString("directionRef").takeIf { it.isNotEmpty() }, Instant.parse(row.getString("at")),
                        DepartureTimeBasis.valueOf(row.getString("timeBasis")),
                        row.optString("recordedAt").takeIf { it.isNotEmpty() }?.let { Instant.parse(it) },
                        row.getBoolean("vehicleAtStop"), when (val direction = row.getString("direction")) {
                            "TOWARDS_ROSNY" -> DepartureDirection.FORWARD
                            "TOWARDS_CHATELET" -> DepartureDirection.BACKWARD
                            else -> DepartureDirection.valueOf(direction)
                        }, row.optString("lastObservedAt").takeIf { it.isNotBlank() }?.let { Instant.parse(it) },
                        row.optBoolean("withdrawn", false))
                }, json.optInt("malformedRecords", 0), (0 until refs.length()).map { refs.getString(it) })
        }.getOrNull()
    }

    fun write(snapshot: DeparturesSnapshot): Unit = synchronized(lock) {
        check(folder.isDirectory || folder.mkdirs())
        val json = JSONObject().put("version", 2).put("station", snapshot.station).put("lineRef", snapshot.lineRef)
            .put("fetchedAt", snapshot.fetchedAt.toString()).put("malformedRecords", snapshot.ignoredMalformedRecords)
            .put("monitoringRefs", JSONArray(snapshot.queriedMonitoringRefs))
        val rows = JSONArray()
        snapshot.departures.forEach { item ->
            rows.put(JSONObject().put("journeyId", item.journeyId).put("monitoringRef", item.monitoringRef)
                .put("destinationName", item.destinationName).put("destinationRef", item.destinationRef.orEmpty())
                .put("directionRef", item.directionRef.orEmpty()).put("at", item.at.toString())
                .put("timeBasis", item.timeBasis.name).put("recordedAt", item.recordedAt?.toString().orEmpty())
                .put("vehicleAtStop", item.vehicleAtStop).put("direction", item.direction.name)
                .put("lastObservedAt", item.lastObservedAt?.toString().orEmpty()).put("withdrawn", item.withdrawn))
        }
        json.put("departures", rows)
        val output = file(snapshot.station, snapshot.lineRef)
        val stream = output.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); output.finishWrite(stream); failureFile(snapshot.station, snapshot.lineRef).delete() }
        catch (error: Exception) { output.failWrite(stream); throw error }
    }
    private fun failureFile(station: String, lineRef: String) = AtomicFile(File(file(station, lineRef).baseFile.path + ".failure"))
    fun writeFailure(station: String, lineRef: String, result: DeparturesResult) = synchronized(lock) {
        check(folder.isDirectory || folder.mkdirs())
        val json = JSONObject().put("state", result.state.name).put("message", result.message)
            .put("fetchedAt", result.snapshot?.fetchedAt?.toString().orEmpty())
            .put("retryAt", result.retryAt?.toString().orEmpty())
        val output = failureFile(station, lineRef)
        val stream = output.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); output.finishWrite(stream) }
        catch (error: Exception) { output.failWrite(stream); throw error }
    }
    fun readFailure(station: String, lineRef: String, snapshot: DeparturesSnapshot?): DeparturesResult? = synchronized(lock) {
        runCatching {
            val bytes = failureFile(station, lineRef).readFully()
            require(bytes.size <= 8192)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.getString("fetchedAt") == snapshot?.fetchedAt?.toString().orEmpty())
            val state = DeparturesState.valueOf(json.getString("state"))
            require(state in listOf(DeparturesState.UNAVAILABLE, DeparturesState.QUOTA))
            DeparturesResult(state, snapshot, snapshot?.let { DeparturesOrigin.CACHE_STALE },
                json.getString("message"), json.getString("retryAt").takeIf { it.isNotEmpty() }?.let { Instant.parse(it) })
        }.getOrNull()
    }
    private companion object { val lock = Any() }
}
