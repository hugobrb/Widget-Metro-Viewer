package fr.widgetmetroviewer.departures

import org.json.JSONObject

/** Le catalogue des arrêts ne contient pas leur ordre de passage.
 * Ce complément local décrit l'ordre des plans de ligne, avec les branches et boucles séparées.
 * Aucune station nouvelle n'est masquée si le catalogue est actualisé avant ce complément.
 */
internal object MetroStationOrder {
    const val UNORDERED = "Stations ajoutées · ordre à compléter"

    fun apply(lines: List<MetroLine>, json: String): List<MetroLine> {
        val definitions = JSONObject(json).getJSONObject("lines")
        return lines.map { line ->
            val stationsByName = line.stations.associateBy { MetroCatalog.normalize(it.name) }
            val used = mutableSetOf<String>()
            val ordered = mutableListOf<MetroStation>()
            val sections = definitions.optJSONArray(line.name)
            if (sections != null) {
                for (index in 0 until sections.length()) {
                    val section = sections.getJSONObject(index)
                    val label = section.getString("label")
                    val names = section.getJSONArray("stations")
                    for (stationIndex in 0 until names.length()) {
                        val key = MetroCatalog.normalize(names.getString(stationIndex))
                        val station = stationsByName[key]
                        if (station != null && used.add(key)) {
                            ordered.add(station.copy(section = label))
                        }
                    }
                }
            }
            val additions = line.stations.filter { MetroCatalog.normalize(it.name) !in used }
                .map { it.copy(section = UNORDERED) }
            line.copy(stations = ordered + additions)
        }
    }
}
