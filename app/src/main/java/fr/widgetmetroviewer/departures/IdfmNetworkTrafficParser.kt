package fr.widgetmetroviewer.departures

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal data class MetroNetworkTrafficPage(val lineIds: Set<String>, val disruptions: List<MetroNetworkDisruption>, val hasNext: Boolean)

/** line_reports et liens vers disruptions : documentation PRIM Navitia v2. */
internal object IdfmNetworkTrafficParser {
    private val paris = ZoneId.of("Europe/Paris")
    private val localTime = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT)
    fun instant(value: String): Instant = IdfmDeparturesParser.instant(value)
        ?: LocalDateTime.parse(value, localTime).atZone(paris).toInstant()
    fun validate(body: JSONObject) {
        require(!body.has("error"))
        require(body.opt("line_reports") is JSONArray)
        require(!body.has("disruptions") || body.opt("disruptions") is JSONArray)
    }
    private fun links(obj: JSONObject): List<String> = IdfmSiri.objects(obj.opt("links"))
        .filter { it.optString("type") == "disruption" || it.optString("rel") == "disruptions" }
        .map { it.getString("id") }

    fun parse(body: JSONObject, page: Int): MetroNetworkTrafficPage {
        validate(body)
        val reports = IdfmSiri.objects(body.opt("line_reports"))
        val disruptions = IdfmSiri.objects(body.opt("disruptions")).associateBy { it.getString("id") }
        data class Link(val lineId: String, val disruptionId: String, val wide: Boolean, val name: String?, val id: String?)
        val mappings = mutableListOf<Link>()
        val lineIds = linkedSetOf<String>()
        reports.forEach { report ->
            val line = report.getJSONObject("line")
            val lineId = line.getString("id")
            lineIds += lineId
            links(line).forEach { mappings += Link(lineId, it, true, null, null) }
            IdfmSiri.objects(report.opt("pt_objects")).forEach { wrapper ->
                val type = wrapper.getString("embedded_type")
                val obj = wrapper.optJSONObject(type) ?: wrapper
                val station = type in setOf("stop_point", "stop_area")
                (links(wrapper) + links(obj)).distinct().forEach { disruptionId ->
                    mappings += Link(lineId, disruptionId, !station, if (station) obj.optString("name") else null,
                        if (station) obj.optString("id") else null)
                }
            }
        }
        val result = mappings.groupBy { it.lineId to it.disruptionId }.map { (identity, entries) ->
            val row = disruptions[identity.second] ?: error("Lien trafic absent")
            val periods = IdfmSiri.objects(row.opt("application_periods")).map {
                MetroTrafficPeriod(instant(it.getString("begin")), instant(it.getString("end"))).also { period -> require(period.end.isAfter(period.begin)) }
            }
            val impacts = IdfmSiri.objects(row.opt("impacted_objects"))
            val explicitTypes = impacts.mapNotNull { it.optJSONObject("pt_object")?.optString("embedded_type")?.takeIf { type -> type.isNotBlank() } }
            val onlyStops = explicitTypes.isNotEmpty() && explicitTypes.all { it in setOf("stop_point", "stop_area") } &&
                impacts.none { it.has("impacted_section") }
            val severity = row.optJSONObject("severity")
            val texts = IdfmSiri.objects(row.opt("messages")).mapNotNull { IdfmSiri.text(it.opt("text")) }
            val text = texts.map(::plainText).filter { it.isNotBlank() }.distinct().joinToString(" ").take(4000)
            require(text.isNotBlank())
            MetroNetworkDisruption(identity.second, identity.first, text, severity?.optString("effect").orEmpty(),
                entries.any { it.wide } && !onlyStops, entries.mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }.distinct(),
                entries.mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } }.distinct(), periods,
                row.optString("status"), severity?.optString("name").orEmpty())
        }
        val pagination = body.optJSONObject("pagination")
        val hasNext = if (pagination != null) {
            require(pagination.getInt("start_page") == page)
            val count = pagination.getInt("items_per_page")
            require(count > 0 && pagination.getInt("total_result") >= 0)
            page.toLong() * count + reports.size < pagination.getInt("total_result")
        } else {
            require(reports.size < 100)
            IdfmSiri.objects(body.opt("links")).any { it.optString("rel") == "next" }
        }
        return MetroNetworkTrafficPage(lineIds, result, hasNext)
    }
    internal fun plainText(value: String): String {
        var text = value.replace(Regex("<[^>]*>"), " ")
        text = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(text) { match ->
            runCatching {
                val code = if (match.groupValues[1].startsWith("x")) match.groupValues[1].drop(1).toInt(16) else match.groupValues[1].toInt()
                String(Character.toChars(code))
            }.getOrDefault(" ")
        }
        return text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ").trim()
    }
}
