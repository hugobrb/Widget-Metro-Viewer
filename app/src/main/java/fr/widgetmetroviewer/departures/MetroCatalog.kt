package fr.widgetmetroviewer.departures

import android.content.Context
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

internal data class MetroStation(
    val name: String,
    val stopIds: List<String>,
    val section: String? = null
)
internal data class MetroLine(val id: String, val name: String, val stations: List<MetroStation>)
internal data class CatalogResult(val lines: List<MetroLine>, val message: String)

internal object MetroCatalog {
    const val SOURCE = "https://prim.iledefrance-mobilites.fr/fr/jeux-de-donnees/arrets-lignes"

    fun normalize(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun parse(json: String): List<MetroLine> {
        val rows = JSONArray(json)
        val grouped = linkedMapOf<Pair<String, String>, MutableList<Pair<String, String>>>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (!row.optString("mode").equals("Metro", ignoreCase = true)) continue
            val id = row.getString("id")
            val name = row.getString("shortname")
            val stopId = row.getString("stop_id")
            val stopName = row.getString("stop_name")
            require(id.startsWith("IDFM:") && stopId.startsWith("IDFM:"))
            require(name.isNotBlank() && stopName.isNotBlank())
            grouped.getOrPut(id to name) { mutableListOf() }.add(stopName to stopId)
        }
        val lines = grouped.map { (line, stops) ->
            MetroLine(line.first, line.second,
                stops.groupBy { normalize(it.first) }.values.map { platforms ->
                    MetroStation(platforms.first().first, platforms.map { it.second }.distinct().sorted())
                }.sortedBy { normalize(it.name) })
        }.sortedWith(compareBy<MetroLine> { it.name.takeWhile(Char::isDigit).toIntOrNull() ?: 999 }
            .thenBy { it.name })
        require(lines.isNotEmpty()) { "Catalogue vide" }
        return lines
    }

    // Le fichier du projet fait foi, y compris après une mise à jour de l'application.
    // Les anciens caches privés ne sont plus lus.
    fun load(context: Context): CatalogResult {
        val lines = context.assets.open("metro_catalog.json")
            .bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
        val order = context.assets.open("metro_station_order.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return CatalogResult(MetroStationOrder.apply(lines, order),
            "Catalogue IDFM inclus dans l’application · disponible hors connexion")
    }
}
