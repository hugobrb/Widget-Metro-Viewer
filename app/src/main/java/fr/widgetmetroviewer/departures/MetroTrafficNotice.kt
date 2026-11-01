package fr.widgetmetroviewer.departures

import java.text.Normalizer
import java.time.Instant
import java.util.Locale

internal object MetroInformationRotation {
    const val INTERVAL_MILLIS = 10_000L
    fun index(now: Instant, count: Int): Int = if (count <= 1) 0 else
        Math.floorMod(now.toEpochMilli() / INTERVAL_MILLIS, count.toLong()).toInt()
}
internal data class MetroTrafficNotice(val text: String, val lines: List<String>, val stations: List<String> = emptyList(),
                                      val stale: Boolean = false, val onlyConnections: Boolean = false) {
    val displayText: String get() {
        val where = stations.joinToString(" / ")
        val prefix = when {
            onlyConnections -> "Correspondance " + lines.joinToString(" / ") { "M" + MetroLineStyles.label(it) } +
                (if (where.isBlank()) "" else " à " + where) + " : "
            where.isNotBlank() && stations.none { canonical(text).contains(canonical(it)) } -> where + " · "
            else -> ""
        }
        return (if (stale) "Information non actualisée. " else "") + prefix + text
    }
    companion object {
        internal fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
            .replace('’', '\'').replace('‘', '\'').replace('–', '-').replace('—', '-')
            .replace(Regex("\\s+"), " ").trim()

        fun group(notices: List<MetroTrafficNotice>): List<MetroTrafficNotice> {
            val cleaned = notices.map { notice ->
                var text = IdfmNetworkTrafficParser.plainText(notice.text)
                if (notice.lines.size == 1) {
                    val label = Regex.escape(MetroLineStyles.label(notice.lines.single()))
                    text = text.replace(Regex("^(?:(?:métro\\s+)?(?:ligne\\s+|M)?" + label + "\\s*[:–—-]\\s*)", RegexOption.IGNORE_CASE), "")
                }
                notice.copy(text = text, stations = notice.stations.map { it.substringBefore(" (") }.distinctBy(::stationKey))
            }.filter { it.text.isNotBlank() }
            return cleaned.groupBy { notice ->
                val textKey = canonical(notice.text)
                // Un texte générique sur deux stations différentes ne décrit pas forcément le même incident.
                val missingStationContext = notice.stations.filterNot { textKey.contains(canonical(it)) }
                    .map(::stationKey).sorted().joinToString("|")
                textKey + "|stations=" + missingStationContext
            }.values.map { group ->
                MetroTrafficNotice(group.first().text,
                    group.flatMap { it.lines }.distinctBy { MetroLineStyles.key(it) }
                        .sortedWith(compareBy<String> { MetroLineStyles.number(it).toIntOrNull() ?: 99 }.thenBy { MetroLineStyles.isBis(it) }),
                    group.flatMap { it.stations }.distinctBy(::stationKey), group.any { it.stale }, group.all { it.onlyConnections })
            }.take(60)
        }
    }
}
