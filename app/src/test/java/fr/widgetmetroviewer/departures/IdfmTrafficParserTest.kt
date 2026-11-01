package fr.widgetmetroviewer.departures

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class IdfmTrafficParserTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private val line = "STIF:Line::C01381:"
    private fun message(id: String, text: String, channel: String = "Perturbation", until: String = "2026-10-31T13:00:00Z",
                        forLine: String = line) = JSONObject().put("InfoMessageIdentifier", JSONObject().put("value", id))
        .put("InfoChannelRef", JSONObject().put("value", channel)).put("ValidUntilTime", until)
        .put("Content", JSONObject().put("LineRef", JSONArray().put(JSONObject().put("value", forLine)))
            .put("Message", JSONArray().put(JSONObject().put("MessageText", JSONObject().put("value", text)))))
    private fun body(vararg messages: JSONObject) = JSONObject().put("Siri", JSONObject().put("ServiceDelivery", JSONObject()
        .put("GeneralMessageDelivery", JSONArray().put(JSONObject().put("Status", true).put("GeneralMessage", JSONArray(messages.toList()))))))
    @Test fun onlyCurrentNonCommercialMessagesForTheSelectedLineAreKept() {
        val result = IdfmTrafficParser.parse(body(message("a", "Incident"), message("b", "Pub", "Commercial"),
            message("c", "Expiré", until = "2026-10-31T11:00:00Z"), message("d", "Autre ligne", forLine = "STIF:Line::C99999:")), line, now)
        assertEquals(listOf("Incident"), result.messages.map { it.text })
    }
    @Test fun htmlAndEntitiesAreCleanedAndIdenticalTextsAreDeduplicated() {
        val result = IdfmTrafficParser.parse(body(message("a", "<b>Trafic</b>&nbsp; ralenti &amp; perturbé"),
            message("b", "Trafic ralenti & perturbé")), line, now)
        assertEquals("Trafic ralenti & perturbé", result.messages.single().text)
    }
    @Test fun cancellationsRemoveMessagesFromTheCurrentResponse() {
        val json = body(message("a", "Ancien message"), message("b", "Information"))
        IdfmSiri.service(json).getJSONArray("GeneralMessageDelivery").getJSONObject(0)
            .put("InfoMessageCancellation", JSONArray().put(JSONObject().put("InfoMessageIdentifier", "a")))
        assertEquals(listOf("Information"), IdfmTrafficParser.parse(json, line, now).messages.map { it.text })
    }
    @Test fun emptyResponseIsValidButFailureIsNotSilentlyTrafficNormal() {
        assertTrue(IdfmTrafficParser.parse(body(), line, now).messages.isEmpty())
        val invalid = body()
        IdfmSiri.service(invalid).getJSONArray("GeneralMessageDelivery").getJSONObject(0).put("Status", false)
        assertTrue(runCatching { IdfmTrafficParser.parse(invalid, line, now) }.isFailure)
    }
    @Test fun cachedMessagesExpireEvenWithoutAnotherNetworkResponse() {
        val snapshot = IdfmTrafficParser.parse(body(message("a", "Message", until = "2026-10-31T12:01:00Z")), line, now)
        val result = MetroTrafficResult(snapshot)
        assertEquals(1, result.active(now).size)
        assertTrue(result.active(now.plusSeconds(61)).isEmpty())
        assertFalse(MetroTrafficResult(snapshot, "Hors connexion").fresh(now))
    }
}
