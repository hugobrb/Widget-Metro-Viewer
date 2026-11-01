package fr.widgetmetroviewer.departures

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun MetroRefreshAccessSection() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(MetroRefreshVisibility.hasUsageAccess(context)) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = MetroRefreshVisibility.hasUsageAccess(context)
                MetroRefreshVisibility.stateChanged(context)
                MetroWidgetProvider.refreshAll(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Actualisation intelligente", fontWeight = FontWeight.SemiBold)
        Text(if (granted) "Détection de l’accueil autorisée" else "Détection de l’accueil désactivée")
        Text("Les appels reprennent dans l’app ou sur l’accueil et sont suspendus sur les autres applications. Sans autorisation, ouvrez l’app pour actualiser.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.fromParts("package", context.packageName, null))
            runCatching { context.startActivity(intent) }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (granted) "Gérer l’autorisation Android" else "Autoriser la détection de l’accueil")
        }
        Text("Contrôle local uniquement : aucun historique enregistré ou envoyé. La détection concerne l’accueil, quelle que soit sa page. Android peut retarder la reprise.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
