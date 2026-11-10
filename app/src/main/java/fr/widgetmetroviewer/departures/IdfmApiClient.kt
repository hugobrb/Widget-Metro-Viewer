package fr.widgetmetroviewer.departures

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

internal enum class IdfmTestStatus { PAUSED, VALID, NO_DATA, INVALID_KEY, FORBIDDEN, QUOTA, NETWORK, TIMEOUT, SERVICE, FORMAT }
internal data class IdfmTestResult(val status: IdfmTestStatus, val message: String, val retryAfterSeconds: Long? = null) {
    val authorized: Boolean get() = status == IdfmTestStatus.VALID || status == IdfmTestStatus.NO_DATA
}

internal sealed interface IdfmResponse {
    class Success(val body: JSONObject, val validation: IdfmTestResult) : IdfmResponse {
        override fun toString(): String = "Success(SIRI response)"
    }
    data class Failure(val error: IdfmTestResult) : IdfmResponse
}

/** HTTPS natif : aucun intercepteur, aucun journal d'en-têtes, aucune redirection avec la clé. */
internal class IdfmApiClient(
    private val requestAllowed: () -> Boolean = { true },
    private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }
) {
    suspend fun test(key: String, station: IdfmStationReference, onAttempt: () -> Unit = {}): IdfmTestResult =
        when (val result = fetch(key, station, onAttempt = onAttempt)) {
            is IdfmResponse.Success -> result.validation
            is IdfmResponse.Failure -> result.error
        }

    suspend fun fetch(key: String, station: IdfmStationReference,
                      monitoringRef: String = station.monitoringRefs.first(),
                      onAttempt: () -> Unit = {}): IdfmResponse =
        request(key, requestUrl(station, monitoringRef), 1_048_576, ::parseResponse, onAttempt)

    suspend fun fetchLine(key: String, lineRef: String, onAttempt: () -> Unit): IdfmResponse =
        request(key, serviceUrl("requete-ligne", lineRef), 8_388_608, { body ->
            runCatching { IdfmLineDeparturesParser.validate(JSONObject(body)) }.fold(
                { IdfmTestResult(IdfmTestStatus.VALID, "Passages par ligne reçus.") },
                { IdfmTestResult(IdfmTestStatus.FORMAT, "Format de la réponse par ligne non reconnu.") })
        }, onAttempt)

    suspend fun fetchTraffic(key: String, lineRef: String, onAttempt: () -> Unit): IdfmResponse =
        request(key, serviceUrl("general-message", lineRef), 1_048_576, { body ->
            runCatching { IdfmSiri.deliveries(JSONObject(body), "GeneralMessageDelivery") }.fold(
                { IdfmTestResult(IdfmTestStatus.VALID, "Messages trafic reçus.") },
                { IdfmTestResult(IdfmTestStatus.FORMAT, "Format des messages trafic non reconnu.") })
        }, onAttempt)

    suspend fun fetchTrafficReports(key: String, page: Int, onAttempt: () -> Unit): IdfmResponse =
        request(key, trafficReportsUrl(page), 8_388_608, { body ->
            runCatching { IdfmNetworkTrafficParser.validate(JSONObject(body)) }.fold(
                { IdfmTestResult(IdfmTestStatus.VALID, "État de circulation reçu.") },
                { IdfmTestResult(IdfmTestStatus.FORMAT, "Format des informations de circulation non reconnu.") })
        }, onAttempt)

    private suspend fun request(key: String, url: URL, maxBytes: Int,
                                validate: (String) -> IdfmTestResult, onAttempt: () -> Unit): IdfmResponse =
        kotlinx.coroutines.coroutineScope<IdfmResponse> {
            if (!requestAllowed()) return@coroutineScope IdfmResponse.Failure(
                IdfmTestResult(IdfmTestStatus.PAUSED, "Actualisation en attente d’une connexion Internet."))
            val owner = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!
            val monitor = launch {
                while (isActive) {
                    kotlinx.coroutines.delay(250)
                    if (!requestAllowed()) owner.cancel(kotlinx.coroutines.CancellationException("Actualisation suspendue"))
                }
            }
            try {
                suspendCancellableCoroutine { continuation ->
                    val activeConnection = AtomicReference<HttpsURLConnection?>()
                    val future = executor.submit {
                        val result = try {
                            require(key.isNotBlank() && key.length <= 4096 && key.all { it.code in 33..126 })
                            val connection = connect(url)
                            activeConnection.set(connection)
                            try {
                                if (!continuation.isActive) return@submit
                                connection.instanceFollowRedirects = false
                                connection.connectTimeout = 15000
                                connection.readTimeout = 20000
                                connection.requestMethod = "GET"
                                connection.setRequestProperty("Accept", "application/json")
                                connection.setRequestProperty("apikey", key)
                                if (!requestAllowed()) throw InternetUnavailable()
                                onAttempt()
                                val code = connection.responseCode
                                if (code != 200) IdfmResponse.Failure(httpError(code, connection.getHeaderField("Retry-After"))) else {
                                    val bytes = ByteArrayOutputStream()
                                    connection.inputStream.use { stream ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            if (!continuation.isActive) return@submit
                                            val count = stream.read(buffer)
                                            if (count < 0) break
                                            if (bytes.size() + count > maxBytes) throw InvalidResponse()
                                            bytes.write(buffer, 0, count)
                                        }
                                    }
                                    val body = bytes.toString("UTF-8")
                                    val validation = validate(body)
                                    if (validation.authorized) IdfmResponse.Success(JSONObject(body), validation)
                                    else IdfmResponse.Failure(validation)
                                }
                            } finally { connection.disconnect() }
                        } catch (_: InternetUnavailable) {
                            IdfmResponse.Failure(IdfmTestResult(IdfmTestStatus.PAUSED, "Actualisation en attente d’une connexion Internet."))
                        } catch (_: IdfmLocalQuotaException) {
                            IdfmResponse.Failure(IdfmTestResult(IdfmTestStatus.QUOTA, "Le plafond local de requêtes est atteint.", 0))
                        } catch (_: SocketTimeoutException) {
                            IdfmResponse.Failure(IdfmTestResult(IdfmTestStatus.TIMEOUT, "PRIM met trop de temps à répondre. Réessaie plus tard."))
                        } catch (_: UnknownHostException) {
                            IdfmResponse.Failure(IdfmTestResult(IdfmTestStatus.NETWORK, "Serveur PRIM introuvable. Vérifie ta connexion Internet."))
                        } catch (_: SSLException) {
                            IdfmResponse.Failure(IdfmTestResult(IdfmTestStatus.NETWORK, "La connexion sécurisée à PRIM a échoué."))
                        } catch (_: InvalidResponse) {
                            IdfmResponse.Failure(IdfmTestResult(IdfmTestStatus.FORMAT, "La réponse PRIM est trop volumineuse ou illisible."))
                        } catch (_: IOException) {
                            IdfmResponse.Failure(IdfmTestResult(IdfmTestStatus.NETWORK, "Connexion impossible. Vérifie Internet puis réessaie."))
                        } catch (_: Exception) {
                            IdfmResponse.Failure(IdfmTestResult(IdfmTestStatus.FORMAT, "Impossible de vérifier la connexion. Vérifie la clé enregistrée."))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                    continuation.invokeOnCancellation {
                        try { activeConnection.get()?.disconnect() } catch (_: Exception) { /* Annulation sans diagnostic sensible. */ }
                        future.cancel(true)
                    }
                }
            } finally { monitor.cancel() }
        }

    private class InternetUnavailable : Exception()
    private class InvalidResponse : IOException()

    companion object {
        private val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "idfm-https").apply { isDaemon = true }
        }
        private const val ENDPOINT = "https://prim.iledefrance-mobilites.fr/marketplace/stop-monitoring"

        internal fun trafficReportsUrl(page: Int): URL {
            require(page in 0..4)
            return URL("https://prim.iledefrance-mobilites.fr/marketplace/v2/navitia/line_reports/physical_modes/physical_mode%3AMetro/line_reports?count=100&start_page=" + page)
        }

        internal fun serviceUrl(path: String, lineRef: String): URL {
            require(path in setOf("requete-ligne", "general-message"))
            require(Regex("STIF:Line::C[0-9]+:").matches(lineRef))
            return URL("https://prim.iledefrance-mobilites.fr/marketplace/" + path + "?LineRef=" + URLEncoder.encode(lineRef, "UTF-8"))
        }

        internal fun requestUrl(station: IdfmStationReference, monitoringRef: String = station.monitoringRefs.first()): URL {
            require(monitoringRef in station.monitoringRefs)
            fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
            return URL(ENDPOINT + "?MonitoringRef=" + encode(monitoringRef) +
                "&LineRef=" + encode(station.lineRef))
        }

        internal fun httpError(code: Int, retry: String?): IdfmTestResult = when (code) {
            401 -> IdfmTestResult(IdfmTestStatus.INVALID_KEY, "Clé API refusée (401). Vérifie-la ou génère une nouvelle clé PRIM.")
            403 -> IdfmTestResult(IdfmTestStatus.FORBIDDEN, "Accès refusé (403). Vérifie la clé et les droits d’accès à l’API Prochains passages.")
            404 -> IdfmTestResult(IdfmTestStatus.SERVICE, "Arrêt ou service introuvable (404). Le référentiel doit être vérifié.")
            429 -> IdfmTestResult(IdfmTestStatus.QUOTA, "Limite PRIM atteinte (429). Les tests sont temporairement suspendus.", retrySeconds(retry))
            in 500..599 -> IdfmTestResult(IdfmTestStatus.SERVICE, "PRIM est temporairement indisponible. Réessaie plus tard.")
            in 300..399 -> IdfmTestResult(IdfmTestStatus.SERVICE, "Le service PRIM a changé d’adresse. Aucune clé n’a été retransmise à une autre adresse.")
            else -> IdfmTestResult(IdfmTestStatus.SERVICE, "PRIM a refusé la requête (HTTP $code).")
        }

        internal fun retrySeconds(header: String?): Long {
            val seconds = header?.trim()?.toLongOrNull() ?: runCatching {
                val until = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                (until - System.currentTimeMillis() + 999) / 1000
            }.getOrNull() ?: 900L
            return seconds.coerceIn(30, 604800)
        }

        internal fun parseResponse(body: String): IdfmTestResult = try {
            val service = JSONObject(body).getJSONObject("Siri").getJSONObject("ServiceDelivery")
            if (service.has("Status") && !service.optBoolean("Status", true)) {
                IdfmTestResult(IdfmTestStatus.SERVICE, "PRIM répond, mais le service signale une erreur.")
            } else {
                val deliveries = service.getJSONArray("StopMonitoringDelivery")
                if (deliveries.length() == 0) {
                    IdfmTestResult(IdfmTestStatus.NO_DATA, "Connexion autorisée · aucun passage annoncé sur le quai testé.")
                } else {
                    var visits = 0
                    var noInformation = false
                    var error = false
                    for (index in 0 until deliveries.length()) {
                        val delivery = deliveries.getJSONObject(index)
                        if (!delivery.optBoolean("Status", true)) {
                            if (delivery.optJSONObject("ErrorCondition")?.has("NoInfoForTopicError") == true) noInformation = true
                            else error = true
                        } else visits += delivery.optJSONArray("MonitoredStopVisit")?.length() ?: 0
                    }
                    when {
                        error -> IdfmTestResult(IdfmTestStatus.SERVICE, "PRIM répond, mais ne peut pas fournir les données de ce quai.")
                        visits == 0 || noInformation -> IdfmTestResult(IdfmTestStatus.NO_DATA, "Connexion autorisée · aucun passage disponible sur le quai testé.")
                        else -> IdfmTestResult(IdfmTestStatus.VALID, "Connexion valide · réponse PRIM reçue ($visits passages sur le quai testé).")
                    }
                }
            }
        } catch (_: Exception) {
            IdfmTestResult(IdfmTestStatus.FORMAT, "PRIM a répondu dans un format inattendu. La connexion n’est pas validée.")
        }
    }
}
