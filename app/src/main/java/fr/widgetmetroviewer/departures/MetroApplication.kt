package fr.widgetmetroviewer.departures

import android.app.Application
import kotlinx.coroutines.*

class MetroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    MetroRefreshScheduler.screenStateChanged(context)
                }
            }, android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            try { MetroRefreshScheduler.synchronize(this@MetroApplication) }
            catch (_: Exception) { /* Aucune clé ni donnée de réponse dans les diagnostics. */ }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
