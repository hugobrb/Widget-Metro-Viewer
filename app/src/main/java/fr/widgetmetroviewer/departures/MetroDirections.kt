package fr.widgetmetroviewer.departures

import android.content.Context
import org.json.JSONObject

internal data class MetroDirection(
    val destinations: List<String>,
    val available: Boolean,
    val unavailableReason: String = ""
) {
    val label: String get() = destinations.joinToString(" / ")
}

/** Parcours nominaux et classement des destinations PRIM, branches et boucles comprises. */
internal class MetroDirections private constructor(
    private val routes: Map<String, Pair<List<List<String>>, List<List<String>>>>
) {
    fun forStation(station: String, line: String): Pair<MetroDirection, MetroDirection> {
        val pair = routes[MetroLineStyles.key(line)]
        val key = MetroCatalog.normalize(station)
        val knownStation = pair?.let { (a, b) -> (a + b).any { path ->
            path.any { MetroCatalog.normalize(it) == key }
        } } == true
        fun resolve(paths: List<List<String>>): MetroDirection {
            val serving = paths.filter { path -> path.any { MetroCatalog.normalize(it) == key } }
            val destinations = (serving.ifEmpty { paths }).map { it.last() }.distinct()
            val available = serving.any { path ->
                path.indexOfFirst { MetroCatalog.normalize(it) == key } < path.lastIndex
            }
            val reason = when {
                available -> ""
                destinations.any { MetroCatalog.normalize(it) == key } -> "Terminus"
                pair == null -> "Ligne non configurée"
                !knownStation -> "Station non reconnue"
                else -> "Sens non desservi"
            }
            return MetroDirection(destinations.ifEmpty { listOf("Direction indisponible") }, available, reason)
        }
        return resolve(pair?.first.orEmpty()) to resolve(pair?.second.orEmpty())
    }

    /** Destination et quai sont des preuves ; un sens ambigu reste explicitement non résolu. */
    fun classify(departures: List<MetroDeparture>, station: String, line: MetroLine): List<MetroDeparture> {
        val paths = routes[MetroLineStyles.key(line.name)] ?: return departures.map { it.copy(direction = DepartureDirection.UNRESOLVED) }
        val here = MetroCatalog.normalize(station)
        val byName = line.stations.associateBy { MetroCatalog.normalize(it.name) }
        fun stopId(value: String) = Regex("^(?:IDFM:|STIF:StopPoint:Q:)([0-9]+):?$").matchEntire(value)?.groupValues?.get(1) ?: value
        val byRef = line.stations.flatMap { stop -> stop.stopIds.map { stopId(it) to stop } }.toMap()
        fun destination(item: MetroDeparture): MetroStation? = item.destinationRef?.let { byRef[stopId(it)] }
            ?: byName[MetroCatalog.normalize(item.destinationName)]
        fun conflict(item: MetroDeparture): Boolean {
            val a = byName[MetroCatalog.normalize(item.destinationName)]
            val b = item.destinationRef?.let { byRef[stopId(it)] }
            return a != null && b != null && MetroCatalog.normalize(a.name) != MetroCatalog.normalize(b.name)
        }
        fun reachable(paths: List<List<String>>, target: String): Boolean = paths.any { path ->
            val names = path.map(MetroCatalog::normalize)
            val index = names.indexOf(here)
            index >= 0 && names.drop(index + 1).contains(target)
        }
        // La boucle 7 bis continue de Pré-Saint-Gervais vers Louis Blanc via Danube.
        val forward = if (MetroLineStyles.key(line.name) == "7B" &&
            paths.first.any { path -> path.any { MetroCatalog.normalize(it) == here } } &&
            paths.second.none { path -> path.any { MetroCatalog.normalize(it) == here } })
            paths.first.map { it + paths.second.first().drop(1) } else paths.first
        fun direct(item: MetroDeparture): DepartureDirection? {
            if (conflict(item)) return null
            val target = destination(item)?.name?.let(MetroCatalog::normalize) ?: return null
            val a = reachable(forward, target)
            val b = reachable(paths.second, target)
            return when {
                a && !b -> DepartureDirection.FORWARD
                b && !a -> DepartureDirection.BACKWARD
                else -> null
            }
        }
        val valid = departures.filterNot { destination(it)?.name?.let(MetroCatalog::normalize) == here && !conflict(it) }
        val evidence = valid.groupBy { it.monitoringRef }.mapValues { (_, items) -> items.mapNotNull(::direct).toSet() }
        val nominal = forStation(station, line.name)
        return IdfmDeparturesParser.deduplicate(valid.map { item ->
            val known = destination(item)
            val inferred = when {
                conflict(item) -> null
                known != null -> direct(item) // Ne pas transformer une destination située sur une autre branche.
                else -> evidence[item.monitoringRef]?.singleOrNull()
            }
            val direction = when {
                inferred == DepartureDirection.FORWARD && nominal.first.available -> inferred
                inferred == DepartureDirection.BACKWARD && nominal.second.available -> inferred
                else -> DepartureDirection.UNRESOLVED
            }
            item.copy(direction = direction, destinationName = if (item.destinationName == "Destination non renseignée" && known != null)
                known.name else item.destinationName)
        })
    }

    companion object {
        @Volatile private var cached: MetroDirections? = null

        fun get(context: Context): MetroDirections = cached ?: synchronized(this) {
            cached ?: context.assets.open("metro_station_order.json").bufferedReader(Charsets.UTF_8)
                .use { fromJson(it.readText()) }.also { cached = it }
        }

        internal fun fromJson(json: String): MetroDirections {
            val lines = JSONObject(json).getJSONObject("lines")
            val routes = mutableMapOf<String, Pair<List<List<String>>, List<List<String>>>>()
            for (line in lines.keys()) {
                val definitions = lines.getJSONArray(line)
                val sections = (0 until definitions.length()).map { index ->
                    val names = definitions.getJSONObject(index).getJSONArray("stations")
                    (0 until names.length()).map { names.getString(it) }
                }
                val main = sections.firstOrNull().orEmpty()
                if (main.isEmpty()) continue
                val forward: List<List<String>>
                val backward: List<List<String>>
                when (line) {
                    "7", "13" -> {
                        forward = sections.drop(1).map { main + it }
                        backward = forward.map { it.reversed() }
                    }
                    "10" -> {
                        val javel = main.indexOfFirst { MetroCatalog.normalize(it) == "javelandrecitroen" }
                        require(javel >= 0 && sections.size == 2)
                        forward = listOf(main)
                        backward = listOf(main.takeLast(2).reversed() + sections[1] + main.take(javel + 1).reversed())
                    }
                    "7B" -> {
                        val botzaris = main.indexOfFirst { MetroCatalog.normalize(it) == "botzaris" }
                        require(botzaris >= 0 && sections.size == 2)
                        forward = listOf(main)
                        backward = listOf(listOf(main.last()) + sections[1] + main.take(botzaris + 1).reversed())
                    }
                    else -> {
                        forward = listOf(main)
                        backward = listOf(main.reversed())
                    }
                }
                require(forward.isNotEmpty() && backward.isNotEmpty())
                routes[line] = forward to backward
            }
            return MetroDirections(routes)
        }
    }
}
