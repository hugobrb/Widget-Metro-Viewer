package fr.widgetmetroviewer.departures

import android.app.Application
import kotlinx.coroutines.*

class MetroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try { MetroRefreshScheduler.synchronize(this@MetroApplication) }
            catch (_: Exception) { /* Aucune clé ni donnée de réponse dans les diagnostics. */ }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
