package fr.widgetmetroviewer.departures

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun IdfmConnectionCard(selectedStation: String? = null, selectedLine: String? = null,
                                onDataChanged: (Boolean) -> Unit = {}) {
    val notifyDataChanged by rememberUpdatedState(onDataChanged)
    val context = LocalContext.current
    val store = remember { IdfmKeyStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val api = remember { IdfmApiClient(requestAllowed = { MetroPhoneState.canRefresh(context) }) }
    val usageStore = remember { IdfmApiUsage(context.applicationContext) }
    var references by remember { mutableStateOf(emptyList<IdfmStationReference>()) }
    val target = references.firstOrNull {
        selectedLine != null && it.lineRef == MetroLineReferences.forLine(selectedLine) && selectedStation != null &&
            MetroCatalog.normalize(it.name) == MetroCatalog.normalize(selectedStation)
    } ?: references.firstOrNull()
    // Jamais rememberSaveable : aucun secret dans l'état sauvegardé de l'activité.
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var stored by remember { mutableStateOf(false) }
    var unreadable by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Lecture de la configuration…") }
    var failure by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        val local = withContext(Dispatchers.IO) {
            runCatching { IdfmStationReference.load(context.applicationContext) }.getOrDefault(emptyList())
        }
        references = local
        when (withContext(Dispatchers.IO) { store.read() }) {
            is IdfmKeyStore.State.Available -> {
                stored = true
                status = "Clé enregistrée · connexion non testée"
            }
            IdfmKeyStore.State.Missing -> status = "Connexion non configurée"
            IdfmKeyStore.State.Unreadable -> {
                unreadable = true
                failure = true
                status = "Clé illisible ou protection invalidée. Enregistre-la à nouveau ou supprime-la."
            }
        }
        busy = false
    }

    Card(shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CONNEXION ÎLE-DE-FRANCE MOBILITÉS", fontSize = 12.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(value = input, onValueChange = { input = it },
                enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (stored) "Remplacer la clé API PRIM" else "Clé API PRIM") },
                placeholder = { Text("Coller la clé personnelle") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(enabled = !busy, onClick = { visible = !visible }) {
                        Text(if (visible) "Masquer" else "Afficher")
                    }
                })
            Button(enabled = !busy && input.isNotBlank(), onClick = {
                val candidate = input.trim()
                if (candidate.length > 4096 || candidate.any { it.code !in 33..126 }) {
                    failure = true
                    status = "Clé non enregistrée : retire les espaces et retours à la ligne."
                } else {
                    visible = false
                    busy = true
                    scope.launch {
                        val success = withContext(Dispatchers.IO) { store.save(candidate) }
                        failure = !success
                        if (success) {
                            stored = true
                            unreadable = false
                            input = ""
                            status = "Clé enregistrée · connexion non testée"
                            notifyDataChanged(true)
                        } else status = "Enregistrement impossible. Réessaie ou supprime l’ancienne clé illisible."
                        busy = false
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer la clé") }
            OutlinedButton(enabled = !busy && stored && target != null, onClick = {
                val station = target ?: return@OutlinedButton
                visible = false
                busy = true
                failure = false
                status = "Test en cours…"
                scope.launch {
                    try {
                        val current = withContext(Dispatchers.IO) { usageStore.read() }
                        val wait = (current.nextAllowedAt - System.currentTimeMillis() + 999) / 1000
                        if (wait > 0) {
                            status = "Patiente encore $wait secondes avant un nouveau test."
                        } else if (current.dailyLimit != null && current.attempts >= current.dailyLimit) {
                            failure = true
                            status = "Le plafond local renseigné est atteint. Vérifie ta consommation sur PRIM."
                        } else {
                            when (val key = withContext(Dispatchers.IO) { store.read() }) {
                                is IdfmKeyStore.State.Available -> {
                                    val result = withTimeoutOrNull(45000) {
                                        api.test(key.value, station, onAttempt = { usageStore.recordAttempt() })
                                    } ?: IdfmTestResult(IdfmTestStatus.TIMEOUT, "Le test a dépassé 45 secondes. Réessaie plus tard.")
                                    status = when (result.status) {
                                        IdfmTestStatus.VALID -> "Connexion établie"
                                        IdfmTestStatus.NO_DATA -> "Connexion établie · aucun passage annoncé"
                                        IdfmTestStatus.PAUSED -> "Actualisation en pause"
                                        IdfmTestStatus.INVALID_KEY -> "Clé refusée. Vérifie la clé enregistrée."
                                        IdfmTestStatus.FORBIDDEN -> "Accès non autorisé. Vérifie tes accès sur PRIM."
                                        IdfmTestStatus.QUOTA -> "Limite de requêtes atteinte. Réessaie plus tard."
                                        IdfmTestStatus.NETWORK -> "Connexion impossible. Vérifie ton accès à Internet."
                                        else -> "Service momentanément indisponible. Réessaie plus tard."
                                    }
                                    failure = !result.authorized
                                    withContext(Dispatchers.IO) {
                                        if (result.status != IdfmTestStatus.PAUSED) usageStore.postpone(result.retryAfterSeconds ?: 30)
                                    }
                                }
                                IdfmKeyStore.State.Missing -> {
                                    stored = false
                                    status = "Connexion non configurée. Enregistre une clé avant le test."
                                }
                                IdfmKeyStore.State.Unreadable -> {
                                    stored = false
                                    unreadable = true
                                    failure = true
                                    status = "Clé illisible. Enregistre-la à nouveau ou supprime-la."
                                }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failure = true
                        status = "Le test n’a pas pu être terminé. Réessaie plus tard."
                    } finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Tester la connexion") }
            if (input.isNotBlank()) Text("La clé saisie doit être enregistrée avant d’être testée.", fontSize = 12.sp)
            TextButton(enabled = !busy && (stored || unreadable), onClick = {
                visible = false
                busy = true
                scope.launch {
                    val success = withContext(Dispatchers.IO) { store.delete() }
                    failure = !success
                    if (success) {
                        stored = false
                        unreadable = false
                        input = ""
                        status = "Connexion non configurée · clé supprimée"
                        notifyDataChanged(true)
                    } else status = "Suppression impossible. Réessaie."
                    busy = false
                }
            }) { Text("Supprimer la clé") }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://prim.iledefrance-mobilites.fr")))
            }) { Text("Obtenir une clé sur le portail PRIM") }
        }
    }
}
