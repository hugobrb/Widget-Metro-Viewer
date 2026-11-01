package fr.widgetmetroviewer.departures

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetroCatalogTest {
    @Test fun officialSnapshotContainsAllSixteenMetroLines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lines = context.assets.open("metro_catalog.json").bufferedReader().use {
            MetroCatalog.parse(it.readText())
        }
        assertEquals(listOf("1", "2", "3", "3B", "4", "5", "6", "7", "7B", "8", "9", "10", "11", "12", "13", "14"),
            lines.map { it.name })
        assertTrue(lines.all { line -> line.stations.isNotEmpty() && line.id.startsWith("IDFM:") })
        val line11 = lines.single { it.name == "11" }
        assertEquals(19, line11.stations.size)
        assertEquals(1, line11.stations.count { MetroCatalog.normalize(it.name) == "chatelet" })
        assertTrue(line11.stations.all { it.stopIds.isNotEmpty() })
        assertEquals(line11.stations.size, line11.stations.map { MetroCatalog.normalize(it.name) }.distinct().size)
        assertTrue(line11.stations.any {
            MetroCatalog.normalize(it.name) == MetroCatalog.normalize("Montreuil–Hôpital")
        })
    }

    @Test fun platformIdsArePreservedWhenStationsAreGrouped() {
        val json = """[
            {"mode":"Metro","id":"IDFM:C01381","shortname":"11","stop_id":"IDFM:1","stop_name":"Châtelet"},
            {"mode":"Metro","id":"IDFM:C01381","shortname":"11","stop_id":"IDFM:2","stop_name":"Châtelet"},
            {"mode":"Metro","id":"IDFM:C01381","shortname":"11","stop_id":"IDFM:1","stop_name":"Châtelet"},
            {"mode":"Bus","id":"IDFM:bus","shortname":"11","stop_id":"IDFM:3","stop_name":"Autre arrêt"}
        ]"""
        val station = MetroCatalog.parse(json).single().stations.single()
        assertEquals(listOf("IDFM:1", "IDFM:2"), station.stopIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyResponseIsRejectedBeforeReplacingCache() {
        MetroCatalog.parse("[]")
    }

    @Test fun stationOrderPreservesAllStationsAndPlatformIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lines = context.assets.open("metro_catalog.json").bufferedReader().use {
            MetroCatalog.parse(it.readText())
        }
        val order = context.assets.open("metro_station_order.json").bufferedReader().use { it.readText() }
        val sorted = MetroStationOrder.apply(lines, order)
        lines.zip(sorted).forEach { (original, ordered) ->
            assertEquals(original.id, ordered.id)
            assertEquals(original.stations.map { it.name to it.stopIds }.toSet(),
                ordered.stations.map { it.name to it.stopIds }.toSet())
            assertEquals(original.stations.size, ordered.stations.size)
            assertTrue(ordered.stations.none { it.section == MetroStationOrder.UNORDERED })
        }
        val line11 = sorted.single { it.name == "11" }.stations
        assertEquals("chatelet", MetroCatalog.normalize(line11.first().name))
        assertEquals("rosnyboisperrier", MetroCatalog.normalize(line11.last().name))
        assertEquals("hoteldeville", MetroCatalog.normalize(line11[1].name))
        assertEquals(3, sorted.single { it.name == "7" }.stations.map { it.section }.distinct().size)
        assertEquals(3, sorted.single { it.name == "13" }.stations.map { it.section }.distinct().size)
        val withAddition = lines.single { it.name == "11" }.let {
            it.copy(stations = it.stations + MetroStation("Nouvelle station", listOf("IDFM:new")))
        }
        val addition = MetroStationOrder.apply(listOf(withAddition), order).single().stations.last()
        assertEquals("Nouvelle station", addition.name)
        assertEquals(MetroStationOrder.UNORDERED, addition.section)
    }
}
