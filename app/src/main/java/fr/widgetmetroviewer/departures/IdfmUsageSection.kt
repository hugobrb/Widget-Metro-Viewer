package fr.widgetmetroviewer.departures

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun IdfmUsageSection() {
    Text("Consommation par API", fontWeight = FontWeight.SemiBold)
    Text("Consommation sur ce téléphone. Consulte PRIM pour connaître les quotas de ton compte.",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf(IdfmApiService.LINE, IdfmApiService.UNIT, IdfmApiService.TRAFFIC, IdfmApiService.TRAFFIC_REPORTS).forEach { service ->
        key(service) { IdfmUsageRow(service) }
    }

}

@Composable
private fun IdfmUsageRow(service: IdfmApiService) {
    val context = LocalContext.current
    val store = remember(service) { IdfmApiUsage(context.applicationContext, service = service) }
    val scope = rememberCoroutineScope()
    val revision by IdfmApiUsage.changes.collectAsState()
    var usage by remember(service) { mutableStateOf(IdfmUsage(0, null, 0)) }
    var input by remember(service) { mutableStateOf("") }
    var saving by remember(service) { mutableStateOf(false) }
    var error by remember(service) { mutableStateOf<String?>(null) }
    LaunchedEffect(store) { input = withContext(Dispatchers.IO) { store.read().dailyLimit?.toString().orEmpty() } }
    LaunchedEffect(store, revision) { usage = withContext(Dispatchers.IO) { store.read() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(service.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        val limit = usage.dailyLimit
        LinearProgressIndicator(progress = { if (limit == null) 0f else (usage.attempts.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text(if (limit == null) "Aujourd’hui : "+usage.attempts+" tentative(s) · plafond non renseigné"
            else "Aujourd’hui : "+usage.attempts+" / "+limit+" · restant : "+(limit-usage.attempts).coerceAtLeast(0), fontSize = 12.sp)
        if (usage.nextAllowedAt > System.currentTimeMillis()) Text("Nouvelle tentative après "+
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(usage.nextAllowedAt)), fontSize = 11.sp)
        usage.lastError?.let { message ->
            val status = when {
                message.contains("403") -> "Accès à ce service non autorisé. Vérifie ton compte PRIM."
                message.contains("401") -> "Clé refusée. Vérifie la clé enregistrée."
                limit != null && usage.attempts >= limit -> "Plafond quotidien atteint."
                else -> "Dernière actualisation impossible. Réessaie plus tard."
            }
            Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(value = input, onValueChange = { input = it.filter(Char::isDigit).take(9) },
            label = { Text("Plafond quotidien local (optionnel)") }, enabled = !saving, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !saving && (input.isBlank() || (input.toIntOrNull() ?: 0) > 0), onClick = {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { input.toIntOrNull()?.let { store.setLimit(it) } ?: store.clearLimit() }
                    error = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Impossible d’enregistrer le plafond." }
                finally { saving = false }
            }
        }) { Text(if (input.isBlank()) "Retirer le plafond local" else "Enregistrer le plafond") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    }
}
