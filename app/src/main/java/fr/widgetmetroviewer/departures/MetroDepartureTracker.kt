package fr.widgetmetroviewer.departures

import java.time.Duration
import java.time.Instant

/** Suit une course sur un quai, sans utiliser son horaire comme identifiant. */
internal object MetroDepartureTracker {
    private val absenceGrace = Duration.ofSeconds(MetroDeparture.RETENTION_SECONDS)
    private val historyFor = Duration.ofMinutes(20)

    fun merge(previous: DeparturesSnapshot?, incoming: List<MetroDeparture>, withdrawals: List<MetroDeparture>,
              observedAt: Instant, now: Instant): List<MetroDeparture> {
        val known = previous?.departures.orEmpty().associateBy { it.identity }.toMutableMap()
        val seen = mutableSetOf<String>()
        for (item in IdfmDeparturesParser.deduplicate(incoming + withdrawals)) {
            val old = known[item.identity]
            if (old?.lastObservedAt?.let { observedAt < it } == true) continue
            val oldStamp = old?.recordedAt
            val newStamp = item.recordedAt
            if (oldStamp != null && (newStamp == null && !item.withdrawn || newStamp != null && newStamp < oldStamp)) continue
            if (old != null && oldStamp != null && newStamp == oldStamp && (!item.withdrawn && (item.at != old.at || old.withdrawn))) continue
            // Une heure deja passee ne cree pas une nouvelle presence estimee a quai.
            if (!item.withdrawn && item.at < now && !item.vehicleAtStop) continue
            if (old?.withdrawn == true && !item.withdrawn && (newStamp == null || oldStamp == null || newStamp <= oldStamp)) continue
            seen += item.identity
            known[item.identity] = item.copy(lastObservedAt = observedAt)
        }
        val displayable = known.values.filter { item ->
            val lastSeen = item.lastObservedAt ?: previous?.fetchedAt ?: observedAt
            val age = Duration.between(lastSeen, now)
            item.isUpcoming(now) && !age.isNegative && age <= absenceGrace &&
                (item.identity in seen || item.journeyId.isNotBlank())
        }.sortedBy { it.at }.take(300)
        // L'historique permet de refuser une ancienne revision apres redemarrage du processus.
        val live = displayable.map { it.identity }.toSet()
        val history = known.values.filter { it.identity !in live &&
            Duration.between(it.lastObservedAt ?: previous?.fetchedAt ?: observedAt, now).let { age -> !age.isNegative && age <= historyFor }
        }.sortedByDescending { it.lastObservedAt ?: previous?.fetchedAt ?: observedAt }.take(100)
        return (displayable + history.map { it.copy(withdrawn = true) }).sortedBy { it.at }
    }
}
