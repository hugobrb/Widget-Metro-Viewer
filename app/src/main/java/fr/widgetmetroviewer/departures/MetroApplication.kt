package fr.widgetmetroviewer.departures

import android.app.Application
import kotlinx.coroutines.*

class MetroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var visible = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                visible++
                MetroRefreshVisibility.setAppVisible(this@MetroApplication, true)
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                visible = (visible - 1).coerceAtLeast(0)
                MetroRefreshVisibility.setAppVisible(this@MetroApplication, visible > 0)
            }
            override fun onActivityResumed(activity: android.app.Activity) { MetroRefreshVisibility.stateChanged(this@MetroApplication) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        MetroRefreshVisibility.start(this)
        androidx.core.content.ContextCompat.registerReceiver(this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    MetroRefreshVisibility.stateChanged(context)
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
