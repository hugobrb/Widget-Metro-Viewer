package fr.widgetmetroviewer.departures

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetroDirectionsTest {
    private fun network(): MetroDirections {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.assets.open("metro_station_order.json").bufferedReader().use {
            MetroDirections.fromJson(it.readText())
        }
    }

    @Test fun everyStationHasAValidDepartureAndNeverDepartsTowardsItself() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lines = context.assets.open("metro_catalog.json").bufferedReader().use { MetroCatalog.parse(it.readText()) }
        val network = network()
        for (line in lines) for (station in line.stations) {
            val (first, second) = network.forStation(station.name, line.name)
            assertTrue("${line.name} : ${station.name}", first.available || second.available)
            for (direction in listOf(first, second).filter { it.available }) {
                assertFalse(direction.destinations.any {
                    MetroCatalog.normalize(it) == MetroCatalog.normalize(station.name)
                })
            }
        }
    }

    @Test fun terminalAndBranchesAreResolvedFromTheSelectedStation() {
        val network = network()
        val chatelet = network.forStation("Châtelet", "11")
        assertEquals("Rosny-Bois-Perrier", chatelet.first.label)
        assertTrue(chatelet.first.available)
        assertFalse(chatelet.second.available)
        assertEquals("Terminus", chatelet.second.unavailableReason)
        val trunk = network.forStation("Maison Blanche", "7")
        assertEquals(2, trunk.first.destinations.size)
        val branch = network.forStation("Porte d'Ivry", "7")
        assertEquals(listOf("Mairie d'Ivry"), branch.first.destinations)
        assertFalse(branch.first.label.contains("Villejuif"))
        assertEquals(2, network.forStation("La Fourche", "13").first.destinations.size)
    }

    @Test fun oneWaySectionsDoNotAdvertiseDeparturesInTheOppositeDirection() {
        val network = network()
        assertFalse(network.forStation("Église d'Auteuil", "10").second.available)
        assertFalse(network.forStation("Mirabeau", "10").first.available)
        assertFalse(network.forStation("Danube", "7B").first.available)
        assertFalse(network.forStation("Place des Fêtes", "7B").second.available)
        assertTrue(network.forStation("Danube", "7B").second.available)
    }

    @Test fun searchNormalizationIgnoresAccentsCaseAndSeparators() {
        assertTrue(MetroCatalog.normalize("Saint-Lazare").contains(MetroCatalog.normalize("SAINT LA")))
        assertTrue(MetroCatalog.normalize("Châtelet").contains(MetroCatalog.normalize("chate")))
    }
}
