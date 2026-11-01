package fr.widgetmetroviewer.departures

import java.time.Duration
import java.time.Instant

internal enum class DepartureDirection { FORWARD, BACKWARD, UNRESOLVED }
internal enum class DepartureTimeBasis { ESTIMATED_DEPARTURE, ESTIMATED_ARRIVAL, THEORETICAL_DEPARTURE, THEORETICAL_ARRIVAL;
    val estimated: Boolean get() = this == ESTIMATED_DEPARTURE || this == ESTIMATED_ARRIVAL
}
internal data class MetroDeparture(
    val journeyId: String,
    val monitoringRef: String,
    val destinationName: String,
    val destinationRef: String?,
    val directionRef: String?,
    val at: Instant,
    val timeBasis: DepartureTimeBasis,
    val recordedAt: Instant?,
    val vehicleAtStop: Boolean,
    val direction: DepartureDirection = DepartureDirection.UNRESOLVED
) {
    fun isUpcoming(now: Instant): Boolean = !at.isBefore(now) ||
        (vehicleAtStop && !at.isBefore(now.minusSeconds(30)))
    fun waitingMinutes(now: Instant): Long? {
        if (!isUpcoming(now)) return null
        return ((Duration.between(now, at).toMillis().coerceAtLeast(0) + 59999) / 60000)
    }
    val identity: String get() = if (journeyId.isNotBlank()) "journey|$journeyId"
        else "$monitoringRef|$destinationName|$at|$directionRef"
}

internal data class DeparturesSnapshot(
    val station: String,
    val lineRef: String,
    val fetchedAt: Instant,
    val departures: List<MetroDeparture>,
    val ignoredMalformedRecords: Int = 0,
    val queriedMonitoringRefs: List<String> = emptyList()
) {
    fun next(direction: DepartureDirection, now: Instant): List<MetroDeparture> = departures
        .filter { it.direction == direction && it.isUpcoming(now) }.sortedBy { it.at }.take(2)
    fun unresolved(now: Instant): List<MetroDeparture> = departures
        .filter { it.direction == DepartureDirection.UNRESOLVED && it.isUpcoming(now) }.sortedBy { it.at }
}
