package fr.widgetmetroviewer.departures

import org.json.JSONArray
import org.json.JSONObject

internal object IdfmSiri {
    fun text(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() && it != "null" }
        is JSONObject -> text(value.opt("value"))
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { text(value.opt(it)) }
        else -> null
    }
    fun objects(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        else -> emptyList()
    }
    fun service(body: JSONObject): JSONObject = body.getJSONObject("Siri").getJSONObject("ServiceDelivery").also {
        require(it.optBoolean("Status", true))
    }
    fun deliveries(body: JSONObject, field: String): List<JSONObject> {
        val service = service(body)
        require(service.has(field))
        val value = service.get(field)
        require(value is JSONArray || value is JSONObject)
        return objects(value).onEach {
            require(it.optBoolean("Status", true) || it.optJSONObject("ErrorCondition")?.has("NoInfoForTopicError") == true)
        }
    }
}
