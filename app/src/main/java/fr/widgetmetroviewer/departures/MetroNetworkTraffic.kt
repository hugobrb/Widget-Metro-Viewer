package fr.widgetmetroviewer.departures

import java.time.Duration
import java.time.Instant

internal enum class MetroCirculation(val label: String, val rank: Int) {
    CLEAR("TRAFIC NORMAL", 0),
    SLOW("TRAFIC RALENTI", 1),
    DISTURBED("TRAFIC PERTURBÉ", 2),
    INTERRUPTED("TRAFIC INTERROMPU", 3),
    UNKNOWN("TRAFIC NON RENSEIGNÉ", -1)
}
internal data class MetroTrafficPeriod(val begin: Instant, val end: Instant) {
    fun contains(now: Instant) = !now.isBefore(begin) && now.isBefore(end)
}
internal data class MetroNetworkDisruption(
    val id: String, val lineId: String, val text: String, val effect: String,
    val lineWide: Boolean, val stationNames: List<String>, val stationIds: List<String>,
    val periods: List<MetroTrafficPeriod>, val reportedStatus: String, val severityName: String
) {
    fun active(now: Instant): Boolean = if (periods.isEmpty()) reportedStatus == "active" else periods.any { it.contains(now) }
    fun circulation(): MetroCirculation? {
        if (!lineWide) return null
        return when (effect) {
            "SIGNIFICANT_DELAYS" -> MetroCirculation.SLOW
            "REDUCED_SERVICE", "MODIFIED_SERVICE", "DETOUR" -> MetroCirculation.DISTURBED
            "NO_SERVICE" -> MetroCirculation.INTERRUPTED
            "ADDITIONAL_SERVICE" -> null
            else -> when {
                MetroCatalog.normalize(severityName).contains("ralenti") -> MetroCirculation.SLOW
                MetroCatalog.normalize(severityName).contains("interrompu") -> MetroCirculation.INTERRUPTED
                MetroCatalog.normalize(severityName).contains("perturbe") -> MetroCirculation.DISTURBED
                else -> MetroCirculation.UNKNOWN
            }
        }
    }
}
internal data class MetroNetworkTrafficSnapshot(val fetchedAt: Instant, val lineIds: Set<String>, val disruptions: List<MetroNetworkDisruption>)
internal data class MetroNetworkTrafficResult(val snapshot: MetroNetworkTrafficSnapshot? = null, val error: String? = null) {
    fun fresh(now: Instant): Boolean = error == null && snapshot != null &&
        Duration.between(snapshot.fetchedAt, now).let { !it.isNegative && it <= Duration.ofMinutes(5) }
}
internal data class MetroConnection(val lineId: String, val lineName: String, val stationName: String, val stopIds: Set<String>)
internal data class MetroTrafficContext(val lineId: String, val lineName: String, val connections: List<MetroConnection>) {
    companion object {
        fun build(lines: List<MetroLine>, selected: MetroLine): MetroTrafficContext {
            val ownNames = selected.stations.map { stationKey(it.name) }.toSet()
            val connections = lines.filter { it.id != selected.id }.flatMap { other ->
                other.stations.filter { stationKey(it.name) in ownNames }.map { station ->
                    MetroConnection("line:" + other.id, other.name, station.name, station.stopIds.map { stopId(it) }.toSet())
                }
            }
            return MetroTrafficContext("line:" + selected.id, selected.name, connections)
        }
    }
}
internal fun stationKey(name: String): String = MetroCatalog.normalize(name.substringBefore(" ("))
internal fun stopId(value: String): String = value.substringAfterLast(':').ifEmpty {
    value.trimEnd(':').substringAfterLast(':')
}
internal data class MetroTrafficBoard(val circulation: MetroCirculation, val stale: Boolean,
                                     val notices: List<MetroTrafficNotice>, val error: String? = null, val publishedLabel: String? = null) {
    val details: List<String> get() = notices.map { it.displayText }
    val footer: String get() = if (stale) "TRAFIC NON ACTUALISÉ" else publishedLabel ?: circulation.label
    val alert: Boolean? get() = if (stale || circulation == MetroCirculation.UNKNOWN) null else circulation != MetroCirculation.CLEAR
}

internal object MetroTrafficPresentation {
    fun build(traffic: MetroTrafficResult?, now: Instant): MetroTrafficBoard {
        val network = traffic?.network
        val context = traffic?.context
        val own = network?.snapshot?.disruptions.orEmpty().filter { it.lineId == context?.lineId && it.active(now) }
        val fresh = network?.fresh(now) == true && context?.lineId in network.snapshot!!.lineIds
        val states = own.mapNotNull { it.circulation() }
        val status = when {
            states.any { it.rank > 0 } -> states.filter { it.rank > 0 }.maxBy { it.rank }
            states.contains(MetroCirculation.UNKNOWN) -> MetroCirculation.UNKNOWN
            fresh -> MetroCirculation.CLEAR
            else -> MetroCirculation.UNKNOWN
        }
        val genericLabels = setOf("information", "bloquante", "bloquant", "perturbee", "unknown", "unknowneffect", "othereffect")
        val publishedLabel = own.asSequence().filter { it.circulation() == status }
            .map { IdfmNetworkTrafficParser.plainText(it.severityName) }
            .firstOrNull { it.isNotBlank() && MetroCatalog.normalize(it) !in genericLabels }
        val expiredScreenMessages = traffic?.snapshot?.messages?.isNotEmpty() == true && traffic.active(now).isEmpty()
        val old = (network?.snapshot != null && !network.fresh(now)) ||
            (network == null && traffic?.snapshot != null && (!traffic.fresh(now) || expiredScreenMessages))
        val notices = mutableListOf<MetroTrafficNotice>()
        own.sortedBy { if (it.lineWide) 1 else 0 }.forEach { event ->
            notices += MetroTrafficNotice(event.text, listOfNotNull(context?.lineName), event.stationNames, old)

        }
        if (context != null) network?.snapshot?.disruptions.orEmpty().filter { it.lineId != context.lineId && it.active(now) }.forEach { event ->
            val affected = context.connections.filter { connection -> connection.lineId == event.lineId &&
                (event.stationNames.any { stationKey(it) == stationKey(connection.stationName) } ||
                    event.stationIds.any { stopId(it) in connection.stopIds }) }
            affected.distinctBy { it.lineId + "|" + stationKey(it.stationName) }.forEach { connection ->
                notices += MetroTrafficNotice(event.text, listOf(connection.lineName), listOf(connection.stationName), old, onlyConnections = true)

            }
        }
        val screenMessages = traffic?.active(now).orEmpty()
        screenMessages.forEach { message ->
            notices += MetroTrafficNotice(message.text, listOfNotNull(context?.lineName), stale = traffic?.fresh(now) == false)
        }
        // Un message d'écran ne permet pas de déduire l'état de circulation à partir du seul canal Perturbation.
        return MetroTrafficBoard(status, old, MetroTrafficNotice.group(notices), network?.error, publishedLabel)
    }
}
