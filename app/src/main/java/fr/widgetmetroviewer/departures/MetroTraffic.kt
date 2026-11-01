package fr.widgetmetroviewer.departures

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

internal data class MetroTrafficMessage(val id: String, val text: String, val channel: String,
                                        val validUntil: Instant?, val recordedAt: Instant?)
internal data class MetroTrafficSnapshot(val lineRef: String, val fetchedAt: Instant, val messages: List<MetroTrafficMessage>)
internal data class MetroTrafficResult(val snapshot: MetroTrafficSnapshot? = null, val error: String? = null,
                                       val network: MetroNetworkTrafficResult? = null, val context: MetroTrafficContext? = null) {
    fun active(now: Instant): List<MetroTrafficMessage> = snapshot?.messages.orEmpty().filter { it.validUntil?.isAfter(now) != false }
    fun fresh(now: Instant): Boolean = error == null && snapshot != null &&
        Duration.between(snapshot.fetchedAt, now).let { !it.isNegative && it <= Duration.ofMinutes(5) }
}

internal object IdfmTrafficParser {
    fun parse(body: JSONObject, lineRef: String, now: Instant): MetroTrafficSnapshot {
        val result = mutableListOf<MetroTrafficMessage>()
        val cancelled = mutableSetOf<String>()
        for (delivery in IdfmSiri.deliveries(body, "GeneralMessageDelivery")) {
            if (!delivery.optBoolean("Status", true)) continue
            for (cancel in IdfmSiri.objects(delivery.opt("InfoMessageCancellation")))
                IdfmSiri.text(cancel.opt("InfoMessageIdentifier"))?.let { cancelled += it }
            for (message in IdfmSiri.objects(delivery.opt("GeneralMessage")) + IdfmSiri.objects(delivery.opt("InfoMessage"))) {
                val content = message.optJSONObject("Content") ?: error("Contenu trafic manquant")
                val returnedLines = IdfmSiri.objects(content.opt("LineRef")).mapNotNull { IdfmSiri.text(it) }
                val plainLine = IdfmSiri.text(content.opt("LineRef")) ?: IdfmSiri.text(message.opt("LineRef"))
                if (returnedLines.isNotEmpty() && lineRef !in returnedLines) continue
                if (returnedLines.isEmpty() && plainLine != null && plainLine != lineRef) continue
                val channel = IdfmSiri.text(message.opt("InfoChannelRef")).orEmpty()
                if (channel.equals("Commercial", true)) continue
                val untilText = IdfmSiri.text(message.opt("ValidUntilTime"))
                val until = IdfmDeparturesParser.instant(untilText)
                require(untilText == null || until != null)
                if (until != null && !until.isAfter(now)) continue
                val recorded = IdfmDeparturesParser.instant(IdfmSiri.text(message.opt("RecordedAtTime")))
                val id = IdfmSiri.text(message.opt("InfoMessageIdentifier"))
                    ?: IdfmSiri.text(message.opt("ItemIdentifier")).orEmpty()
                for (text in IdfmSiri.objects(content.opt("Message"))) {
                    val language = text.optJSONObject("MessageText")?.optString("lang").orEmpty()
                    if (language.isNotEmpty() && !language.startsWith("fr", true)) continue
                    val value = IdfmSiri.text(text.opt("MessageText")) ?: continue
                    val clean = value.replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim().take(3000)
                    if (clean.isNotBlank()) result += MetroTrafficMessage(id, clean, channel, until, recorded)
                }
            }
        }
        return MetroTrafficSnapshot(lineRef, now, result.filter { it.id !in cancelled }
            .distinctBy { it.text }.sortedBy { if (it.channel.equals("Perturbation", true)) 0 else 1 }.take(50))
    }
}
