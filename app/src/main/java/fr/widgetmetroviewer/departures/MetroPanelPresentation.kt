package fr.widgetmetroviewer.departures

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal data class MetroPanelColumn(
    val destinations: List<String>,
    val value: String?,
    val unit: String = "",
    val detail: String,
    val following: String = "",
    val followingDestination: String = ""
) {
    val label: String get() = destinations.joinToString(" / ")
}
internal data class MetroPanelData(
    val first: MetroPanelColumn,
    val second: MetroPanelColumn,
    val demo: Boolean,
    val footer: String,
    val tag: String,
    val information: String,
    val loading: Boolean = false,
    val trafficAlert: Boolean? = null,
    val informationLines: List<String> = emptyList(),
    val informationNotices: List<MetroTrafficNotice> = emptyList()
)

/** Présentation commune des horaires pour l’aperçu et le widget. */
internal object MetroPanelPresentation {
    fun build(station: String, line: String, nominal: Pair<MetroDirection, MetroDirection>,
              result: DeparturesResult?, now: Instant, loading: Boolean = false,
              zone: ZoneId = ZoneId.systemDefault()): MetroPanelData {
        val demo = result?.state == DeparturesState.DEMO
        if (demo) {
            fun column(direction: MetroDirection, minutes: String, next: String) = MetroPanelColumn(
                direction.destinations, if (direction.available) minutes else null, "min",
                if (direction.available) "DÉMONSTRATION" else direction.unavailableReason,
                if (direction.available) "Suivant : $next min" else "")
            return MetroPanelData(column(nominal.first, "03", "08"), column(nominal.second, "05", "11"),
                true, "SERVICE NORMAL · DÉMO", "DONNÉES DE DÉMO",
                "Exemple de panneau. Les temps d’attente et le statut sont fictifs. Enregistre une clé PRIM pour consulter les passages disponibles.")
        }
        // Ne jamais projeter le cache d'une autre station/ligne sur une sélection nouvelle.
        val snapshot = result?.snapshot?.takeIf { MetroCatalog.normalize(it.station) == MetroCatalog.normalize(station) &&
            it.lineRef == MetroLineReferences.forLine(line) }
        val stale = result?.origin == DeparturesOrigin.CACHE_STALE || snapshot?.let {
            val age = Duration.between(it.fetchedAt, now)
            age.isNegative || age > Duration.ofMinutes(3)
        } == true
        fun column(direction: DepartureDirection, nominalDirection: MetroDirection): MetroPanelColumn {
            val next = snapshot?.next(direction, now).orEmpty()
            if (next.isEmpty()) {
                val message = when {
                    !nominalDirection.available -> nominalDirection.unavailableReason
                    loading -> "Chargement…"
                    result?.state == DeparturesState.UNSUPPORTED -> "Non connecté"
                    result?.state == DeparturesState.QUOTA -> "Quota atteint"
                    snapshot == null || stale -> "Indisponible"
                    snapshot.unresolved(now).isNotEmpty() -> "Sens non établi"
                    else -> "Aucun passage annoncé"
                }
                return MetroPanelColumn(nominalDirection.destinations, null, detail = message)
            }
            val first = next.first()
            val second = next.getOrNull(1)
            val atStop = first.vehicleAtStop && !stale && first.recordedAt?.let {
                Duration.between(it, now).seconds in 0..60
            } == true
            val estimatedAtStop = !atStop && !stale && first.estimatedAtStop(now)
            return MetroPanelColumn(listOf(first.destinationName), if (atStop || estimatedAtStop) "À QUAI" else first.waitingMinutes(now)?.toString()?.padStart(2, '0'),
                unit = if (atStop || estimatedAtStop) "" else "min",
                detail = when {
                    stale && !first.timeBasis.estimated -> "HORAIRE NON ACTUALISÉ · THÉORIQUE"
                    stale -> "HORAIRE NON ACTUALISÉ"
                    !first.timeBasis.estimated -> "THÉORIQUE"
                    else -> ""
                },
                following = second?.let { "Suivant : "+it.waitingMinutes(now)?.toString()?.padStart(2, '0')+" min" + if (!it.timeBasis.estimated) " (th.)" else "" }.orEmpty(),
                followingDestination = second?.takeIf {
                    MetroCatalog.normalize(it.destinationName) != MetroCatalog.normalize(first.destinationName)
                }?.let { "→ ${it.destinationName}" }.orEmpty())
        }
        val tag = when {
            loading -> "ACTUALISATION"
            stale -> "DONNÉES ANCIENNES"
            result?.state == DeparturesState.PARTIAL -> "DONNÉES PARTIELLES"
            snapshot != null -> "PROCHAINS PASSAGES"
            result?.state == DeparturesState.QUOTA -> "QUOTA ATTEINT"
            result?.state == DeparturesState.UNSUPPORTED -> "NON CONNECTÉ"
            else -> "INDISPONIBLE"
        }
        val departuresInformation = when {
            loading && snapshot == null -> "Recherche des prochains passages…"
            loading -> "Actualisation des passages…"
            result?.state == DeparturesState.QUOTA -> "La limite de requêtes est atteinte. Réessaie plus tard."
            result?.state == DeparturesState.UNSUPPORTED -> "Les prochains passages ne sont pas encore disponibles pour cette station."
            stale -> "Les horaires affichés peuvent être anciens. Réessaie dans quelques instants."
            snapshot == null -> "Les prochains passages sont momentanément indisponibles."
            result?.state == DeparturesState.NO_DATA -> "Aucun prochain passage annoncé."
            result?.state == DeparturesState.PARTIAL -> "Certains passages ne sont pas disponibles pour le moment."
            else -> "Aucune information à signaler sur cette ligne."
        }
        val traffic = result?.traffic?.takeIf { (it.snapshot == null || it.snapshot.lineRef == MetroLineReferences.forLine(line)) &&
            (it.context == null || MetroLineStyles.key(it.context.lineName) == MetroLineStyles.key(line)) }
        val board = MetroTrafficPresentation.build(traffic, now)
        val messageIndex = MetroInformationRotation.index(now, board.notices.size)
        val information = if (board.details.isNotEmpty()) board.details[messageIndex] +
            (if (board.details.size > 1) " · Information "+(messageIndex+1)+"/"+board.details.size else "")
        else if (board.error != null && result?.state in listOf(DeparturesState.DATA, DeparturesState.NO_DATA, DeparturesState.PARTIAL))
            "Les informations trafic sont momentanément indisponibles."
        else departuresInformation
        return MetroPanelData(column(DepartureDirection.FORWARD, nominal.first),
            column(DepartureDirection.BACKWARD, nominal.second), false,
            board.footer, tag, information, loading, board.alert,
            board.notices.getOrNull(messageIndex)?.lines.orEmpty(), board.notices)
    }

    fun description(station: String, line: String, panel: MetroPanelData): String {
        fun describe(column: MetroPanelColumn) = "Direction ${column.label} : " +
            (column.value?.let { "$it ${column.unit}, " }.orEmpty()) + column.detail + ". " +
            column.following + " " + column.followingDestination + ". "
        return "Ligne ${MetroLineStyles.label(line)}, $station. " + describe(panel.first) + describe(panel.second) +
            panel.footer + ". " + panel.tag + ". " + panel.information
    }
}
