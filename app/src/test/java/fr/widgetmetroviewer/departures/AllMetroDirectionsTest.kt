package fr.widgetmetroviewer.departures

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class AllMetroDirectionsTest {
    private fun asset(name: String) = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }.readText()
    private val lines = MetroCatalog.parse(asset("metro_catalog.json"))
    private val network = MetroDirections.fromJson(asset("metro_station_order.json"))
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private fun departure(destination: String, quay: String = "quay", ref: String? = null) = MetroDeparture(
        destination + quay, quay, destination, ref, null, now.plusSeconds(180),
        DepartureTimeBasis.ESTIMATED_DEPARTURE, now, false)
    private fun classify(line: String, station: String, vararg items: MetroDeparture) = network.classify(items.toList(), station, lines.single { it.name == line })

    @Test fun everyCatalogStationHasVerifiedReferencesOnItsOwnLine() {
        val refs = IdfmStationReference.parse(asset("idfm_metro_stops.json"))
        assertEquals(405, refs.size)
        assertEquals(16, refs.map { it.lineRef }.distinct().size)
        assertEquals(804, refs.sumOf { it.monitoringRefs.size })
        for (line in lines) for (stop in line.stations) {
            val reference = refs.single { it.lineRef == MetroLineReferences.forLine(line.name) &&
                MetroCatalog.normalize(it.name) == MetroCatalog.normalize(stop.name) }
            assertTrue(reference.monitoringRefs.isNotEmpty())
            val direction = network.forStation(stop.name, line.name)
            assertTrue(direction.first.available || direction.second.available)
        }
    }

    @Test fun actualDestinationsResolveForEveryStationOnAllSixteenLines() {
        for (line in lines) for (station in line.stations) {
            val nominal = network.forStation(station.name, line.name)
            for ((direction, expected) in listOf(nominal.first to DepartureDirection.FORWARD, nominal.second to DepartureDirection.BACKWARD)) {
                if (!direction.available) continue
                for (destination in direction.destinations) {
                    val result = classify(line.name, station.name, departure(destination)).single()
                    assertEquals(line.name + " / " + station.name + " → " + destination, expected, result.direction)
                }
            }
        }
    }

    @Test fun branchesDoNotLeakIntoEachOther() {
        assertEquals(DepartureDirection.UNRESOLVED, classify("13", "Gabriel Péri", departure("Saint-Denis - Université")).single().direction)
        assertEquals(DepartureDirection.BACKWARD, classify("13", "Gabriel Péri", departure("Châtillon - Montrouge")).single().direction)
        val trunk = classify("7", "Maison Blanche", departure("Mairie d'Ivry", "a"), departure("Villejuif - Louis Aragon", "b"))
        assertTrue(trunk.all { it.direction == DepartureDirection.FORWARD })
        assertEquals(DepartureDirection.UNRESOLVED, classify("7", "Porte d'Ivry", departure("Villejuif - Louis Aragon")).single().direction)
    }

    @Test fun loopsRespectTheActualOneWayRoute() {
        assertEquals(DepartureDirection.FORWARD, classify("10", "Église d'Auteuil", departure("Boulogne - Pont de Saint-Cloud")).single().direction)
        assertEquals(DepartureDirection.BACKWARD, classify("10", "Mirabeau", departure("Gare d'Austerlitz")).single().direction)
        assertEquals(DepartureDirection.FORWARD, classify("7B", "Place des Fêtes", departure("Louis Blanc")).single().direction)
        assertEquals(DepartureDirection.BACKWARD, classify("7B", "Danube", departure("Louis Blanc")).single().direction)
    }

    @Test fun partialServicesUseReachabilityAndUnknownsNeedQuayEvidence() {
        val items = classify("1", "Bastille", departure("Gare de Lyon"), departure("Destination inconnue"))
        assertEquals(2, items.size)
        assertTrue(items.all { it.direction != DepartureDirection.UNRESOLVED })
        assertEquals(DepartureDirection.UNRESOLVED, classify("1", "Bastille", departure("Destination inconnue")).single().direction)
        val lyon = lines.single { it.name == "1" }.stations.single { it.name == "Gare de Lyon" }
        assertEquals(DepartureDirection.UNRESOLVED, classify("1", "Bastille", departure("Château de Vincennes", ref = lyon.stopIds.first())).single().direction)
    }

    @Test fun selectedLineFiltersPresentationEvenAtAnInterchange() {
        val snapshot = DeparturesSnapshot("Châtelet", MetroLineReferences.forLine("11")!!, now, listOf(departure("Rosny-Bois-Perrier").copy(direction = DepartureDirection.FORWARD)))
        val panel = MetroPanelPresentation.build("Châtelet", "1", network.forStation("Châtelet", "1"),
            DeparturesResult(DeparturesState.DATA, snapshot, DeparturesOrigin.NETWORK, ""), now)
        assertNull(panel.first.value)
        assertNull(panel.second.value)
    }
}
