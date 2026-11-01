package fr.widgetmetroviewer.departures

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicBoolean

/** Detection locale de l'accueil au premier plan, uniquement apres accord Android explicite. */
internal object MetroRefreshVisibility {
    private val appVisible = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val watchRevision = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val tracker = MetroForegroundTracker()
    private var lastQueryAt = 0L
    private var nextCheck = 0L
    private var lastAllowed = false
    private var homePackage: String? = null
    private var homeCheckedAt = 0L

    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun isAllowed(context: Context): Boolean {
        if (appVisible.get()) return true
        if (!hasUsageAccess(context)) return false
        return synchronized(lock) {
            val elapsed = SystemClock.elapsedRealtime()
            if (elapsed < nextCheck) return@synchronized lastAllowed
            lastAllowed = runCatching {
                val now = System.currentTimeMillis()
                if (lastQueryAt == 0L || now < lastQueryAt) tracker.clear()
                val from = if (lastQueryAt == 0L || now < lastQueryAt) now - 86_400_000 else lastQueryAt - 2000
                val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
                    ?: return@runCatching false
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    // Les valeurs 1/2 sont identiques aux anciens MOVE_TO_FOREGROUND/BACKGROUND (API 26+).
                    when (event.eventType) {
                        1 -> tracker.resumed(event.packageName.orEmpty(), event.className, event.timeStamp)
                        2 -> tracker.paused(event.packageName.orEmpty(), event.className, event.timeStamp)
                    }
                }
                lastQueryAt = now
                if (homeCheckedAt == 0L || elapsed - homeCheckedAt > 60_000) {
                    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    @Suppress("DEPRECATION")
                    val resolved = context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    homePackage = resolved?.activityInfo?.packageName?.takeUnless { it == "android" }
                    homeCheckedAt = elapsed
                }
                tracker.allowsRefresh(false, true, homePackage)
            }.getOrDefault(false)
            nextCheck = elapsed + 1500
            lastAllowed
        }
    }

    fun setAppVisible(context: Context, visible: Boolean) {
        appVisible.set(visible)
        stateChanged(context)
    }

    fun stateChanged(context: Context) {
        synchronized(lock) { nextCheck = 0L }
        val app = context.applicationContext
        synchronized(lock) { watchRevision.value += 1L }
        MetroRefreshScheduler.screenStateChanged(app)
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            watchRevision.collectLatest { _ ->
                val widgets = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, MetroWidgetProvider::class.java)).isNotEmpty()
                if (!widgets) return@collectLatest
                var previous: Boolean? = null
                while (isActive && MetroPhoneState.isUnlocked(app) && hasUsageAccess(app)) {
                    val allowed = isAllowed(app)
                    if (allowed != previous) {
                        previous = allowed
                        MetroRefreshScheduler.screenStateChanged(app)
                    }
                    delay(if (allowed) 2000 else 8000)
                }
            }
        }
        stateChanged(app)
    }
}
