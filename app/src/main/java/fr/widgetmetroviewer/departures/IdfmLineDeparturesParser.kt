package fr.widgetmetroviewer.departures

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Adaptation SIRI EstimatedTimetable vers le parser partagé ; filtrage strict des quais sélectionnés. */
internal object IdfmLineDeparturesParser {
    fun validate(body: JSONObject) {
        val service = IdfmSiri.service(body)
        if (service.has("EstimatedTimetableDelivery")) IdfmSiri.deliveries(body, "EstimatedTimetableDelivery")
        else IdfmSiri.deliveries(body, "StopMonitoringDelivery")
    }

    fun parse(body: JSONObject, lineRef: String, monitoringRefs: List<String>, now: Instant): ParsedDepartures {
        validate(body)
        val service = IdfmSiri.service(body)
        if (!service.has("EstimatedTimetableDelivery")) {
            // Certains producteurs utilisent un ensemble de MonitoredStopVisit.
            val rows = monitoringRefs.map { ref ->
                val safe = JSONObject(body.toString())
                IdfmSiri.objects(IdfmSiri.service(safe).opt("StopMonitoringDelivery")).forEach { delivery ->
                    val visits = IdfmSiri.objects(delivery.opt("MonitoredStopVisit")).filter { visit ->
                        val journey = visit.optJSONObject("MonitoredVehicleJourney")
                        IdfmSiri.text(journey?.optJSONObject("MonitoredCall")?.opt("StopPointRef")) == ref ||
                            IdfmSiri.text(visit.opt("MonitoringRef")) == ref
                    }
                    delivery.put("MonitoredStopVisit", JSONArray(visits))
                }
                IdfmDeparturesParser.parse(safe, lineRef, ref, now)
            }
            return ParsedDepartures(IdfmDeparturesParser.deduplicate(rows.flatMap { it.departures }),
                rows.sumOf { it.malformedRecords }, rows.flatMap { it.cancelledJourneyIds }.toSet())
        }
        val visits = JSONArray()
        var malformed = 0
        for (delivery in IdfmSiri.deliveries(body, "EstimatedTimetableDelivery")) {
            if (!delivery.optBoolean("Status", true)) continue
            for (frame in IdfmSiri.objects(delivery.opt("EstimatedJourneyVersionFrame"))) {
                for (journey in IdfmSiri.objects(frame.opt("EstimatedVehicleJourney"))) {
                    if (IdfmSiri.text(journey.opt("LineRef")) != lineRef) continue
                    val calls = IdfmSiri.objects(journey.optJSONObject("EstimatedCalls")?.opt("EstimatedCall"))
                    for (call in calls) {
                        val ref = IdfmSiri.text(call.opt("StopPointRef"))
                        if (ref == null) { malformed++; continue }
                        if (ref !in monitoringRefs) continue
                        if (IdfmSiri.text(call.opt("DepartureStatus")) in listOf("departed", "missed", "notExpected")) continue
                        val normalized = JSONObject(journey.toString())
                        normalized.remove("EstimatedCalls")
                        normalized.remove("RecordedCalls")
                        normalized.put("MonitoredCall", call)
                        // Les compteurs de courses RATP en ET ne constituent pas un identifiant de train fiable.
                        // On conserve chaque passage distinct (quai + horaire), sans fusionner tous les trains.
                        val framed = normalized.optJSONObject("FramedVehicleJourneyRef")
                        val dated = IdfmSiri.text(framed?.opt("DatedVehicleJourneyRef"))
                            ?: IdfmSiri.text(normalized.opt("DatedVehicleJourneyRef"))
                        val at = listOf("ExpectedDepartureTime", "ExpectedArrivalTime", "AimedDepartureTime", "AimedArrivalTime")
                            .firstNotNullOfOrNull { IdfmSiri.text(call.opt(it)) }
                        normalized.remove("DatedVehicleJourneyRef")
                        normalized.remove("FramedVehicleJourneyRef")
                        if (dated != null && at != null) normalized.put("FramedVehicleJourneyRef", JSONObject()
                            .put("DatedVehicleJourneyRef", dated + "|" + ref + "|" + at))
                        val recorded = IdfmSiri.text(journey.opt("RecordedAtTime")) ?: IdfmSiri.text(frame.opt("RecordedAtTime"))
                        visits.put(JSONObject().put("MonitoringRef", JSONObject().put("value", ref))
                            .put("RecordedAtTime", recorded.orEmpty()).put("MonitoredVehicleJourney", normalized))
                    }
                }
            }
        }
        val normalized = JSONObject().put("Siri", JSONObject().put("ServiceDelivery", JSONObject()
            .put("StopMonitoringDelivery", JSONArray().put(JSONObject().put("Status", true).put("MonitoredStopVisit", visits)))))
        val rows = monitoringRefs.map { IdfmDeparturesParser.parse(normalized, lineRef, it, now) }
        return ParsedDepartures(IdfmDeparturesParser.deduplicate(rows.flatMap { it.departures }),
            malformed + rows.sumOf { it.malformedRecords }, rows.flatMap { it.cancelledJourneyIds }.toSet())
    }
}
