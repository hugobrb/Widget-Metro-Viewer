package fr.widgetmetroviewer.departures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/** Receiver privé : seul le PendingIntent du widget permet de demander cette actualisation. */
class MetroRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        scope.launch {
            try { withTimeout(8000) { MetroRefreshScheduler.synchronize(context.applicationContext, immediate = true, force = true) } }
            catch (_: Exception) { MetroWidgetProvider.refreshAll(context, loadingOverride = false) }
            finally { pending.finish() }
        }
    }
    companion object {
        const val ACTION = "fr.widgetmetroviewer.departures.REFRESH"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
