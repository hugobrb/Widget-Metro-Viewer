package fr.widgetmetroviewer.departures

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MetroTrafficNoticeTest {
    @Test fun theSameStationNoticeIsSharedByAllItsLineBadges() {
        val groups = MetroTrafficNotice.group(listOf(
            MetroTrafficNotice("République : arrêt non desservi.", listOf("8"), listOf("République"), onlyConnections = true),
            MetroTrafficNotice("République : arrêt non desservi.", listOf("9"), listOf("République"), onlyConnections = true),
            MetroTrafficNotice("République : arrêt non desservi.", listOf("8"), listOf("République"), onlyConnections = true)))
        assertEquals(1, groups.size)
        assertEquals(listOf("8", "9"), groups.single().lines)
        assertEquals(listOf("République"), groups.single().stations)
        assertTrue(groups.single().displayText.contains("M8 / M9"))
    }
    @Test fun linePrefixesAndFormattingDoNotRepeatTheSameInformation() {
        val groups = MetroTrafficNotice.group(listOf(
            MetroTrafficNotice("Ligne 3 : <b>Travaux</b> à République.", listOf("3"), listOf("République")),
            MetroTrafficNotice("M8 : Travaux   à République.", listOf("8"), listOf("République"))))
        assertEquals(1, groups.size)
        assertEquals(listOf("3", "8"), groups.single().lines)
        assertEquals("Travaux à République.", groups.single().text)
    }
    @Test fun differentDatesNegationsAndStationContextsStaySeparate() {
        val groups = MetroTrafficNotice.group(listOf(
            MetroTrafficNotice("Fermeture le 1/11.", listOf("3")),
            MetroTrafficNotice("Fermeture le 11/1.", listOf("8")),
            MetroTrafficNotice("L’arrêt est desservi.", listOf("3")),
            MetroTrafficNotice("L’arrêt n’est pas desservi.", listOf("8")),
            MetroTrafficNotice("Arrêt non desservi.", listOf("8"), listOf("Balard")),
            MetroTrafficNotice("Arrêt non desservi.", listOf("9"), listOf("République"))))
        assertEquals(6, groups.size)
    }
    @Test fun mixedFreshnessCannotHideAnOldNotice() {
        val groups = MetroTrafficNotice.group(listOf(
            MetroTrafficNotice("Travaux à République.", listOf("8"), stale = false),
            MetroTrafficNotice("Travaux à République.", listOf("9"), stale = true)))
        assertEquals(1, groups.size)
        assertTrue(groups.single().stale)
        assertTrue(groups.single().displayText.contains("non actualisée"))
    }
    @Test fun messageRotationVisitsEveryGroupAtTenSecondIntervalsAndWrapsAround() {
        val start = Instant.parse("2026-10-31T12:00:00Z")
        val indices = (0..5).map { MetroInformationRotation.index(start.plusSeconds(it * 10L), 5) }
        assertEquals(setOf(0, 1, 2, 3, 4), indices.take(5).toSet())
        assertEquals(indices.first(), indices.last())
        assertEquals(0, MetroInformationRotation.index(start, 0))
        assertEquals(0, MetroInformationRotation.index(start, 1))
    }
}
