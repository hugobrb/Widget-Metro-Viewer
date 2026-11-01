package fr.widgetmetroviewer.departures

import android.content.Context
import org.json.JSONObject

internal data class IdfmStationReference(val name: String, val lineRef: String, val monitoringRefs: List<String>) {
    companion object {
        fun load(context: Context): List<IdfmStationReference> = context.assets.open("idfm_metro_stops.json")
            .bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }

        internal fun parse(json: String): List<IdfmStationReference> {
            val rows = JSONObject(json).getJSONArray("stations")
            return (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                val refs = row.getJSONArray("monitoringRefs")
                val monitoring = (0 until refs.length()).map { refs.getString(it) }
                require(monitoring.isNotEmpty() && monitoring.all { it.matches(Regex("STIF:StopPoint:Q:[0-9]+:")) })
                val line = row.getString("lineRef")
                require(line.matches(Regex("STIF:Line::C[0-9]+:")))
                IdfmStationReference(row.getString("name"), line, monitoring)
            }
        }
    }
}
