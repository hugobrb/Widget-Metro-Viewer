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
    val direction: DepartureDirection = DepartureDirection.UNRESOLVED,
    val lastObservedAt: Instant? = null,
    val withdrawn: Boolean = false
) {
    companion object {
        const val ESTIMATED_STOP_SECONDS = 10L
        const val RETENTION_SECONDS = 90L
    }

    fun estimatedAtStop(now: Instant): Boolean = !withdrawn && timeBasis.estimated &&
        !now.isBefore(at) && now.isBefore(at.plusSeconds(ESTIMATED_STOP_SECONDS))
    fun isUpcoming(now: Instant): Boolean = !withdrawn &&
        (lastObservedAt == null || !now.isAfter(lastObservedAt.plusSeconds(RETENTION_SECONDS))) && (!at.isBefore(now) || estimatedAtStop(now) ||
        (vehicleAtStop && !at.isBefore(now.minusSeconds(30))))
    fun waitingMinutes(now: Instant): Long? {
        if (!isUpcoming(now)) return null
        return ((Duration.between(now, at).toMillis().coerceAtLeast(0) + 59999) / 60000)
    }
    val identity: String get() = if (journeyId.isNotBlank()) "journey|$journeyId|$monitoringRef"
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
    fun nextVisualChange(now: Instant): Instant? = departures.filter { it.isUpcoming(now) }.flatMap { item ->
        val phase = when {
            item.at > now -> {
                val minutes = ((Duration.between(now, item.at).toMillis() + 59999) / 60000).coerceAtLeast(1)
                item.at.minusSeconds((minutes - 1) * 60)
            }
            item.estimatedAtStop(now) -> item.at.plusSeconds(MetroDeparture.ESTIMATED_STOP_SECONDS)
            item.vehicleAtStop -> item.at.plusSeconds(30).plusMillis(1)
            else -> null
        }
        listOfNotNull(phase, item.lastObservedAt?.plusSeconds(MetroDeparture.RETENTION_SECONDS)?.plusMillis(1),
            item.recordedAt?.takeIf { item.vehicleAtStop }?.plusSeconds(60)?.plusMillis(1))
    }.filter { it > now }.minOrNull()

    fun next(direction: DepartureDirection, now: Instant): List<MetroDeparture> = departures
        .filter { it.direction == direction && it.isUpcoming(now) }.sortedBy { it.at }.take(2)
    fun unresolved(now: Instant): List<MetroDeparture> = departures
        .filter { it.direction == DepartureDirection.UNRESOLVED && it.isUpcoming(now) }.sortedBy { it.at }
}
