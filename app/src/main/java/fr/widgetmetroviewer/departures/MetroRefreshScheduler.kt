package fr.widgetmetroviewer.departures

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

internal object MetroRefreshScheduler {
    internal const val PERIODIC = "metro_departures_periodic_v1"
    internal const val IMMEDIATE = "metro_departures_now_v1"
    internal const val LOOP = "metro_departures_minute_v1"
    internal const val FORCE = "force_refresh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduling = Mutex()
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun configurationChanged(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try { synchronize(app, immediate = true, replace = true) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { MetroWidgetProvider.refreshAll(app, loadingOverride = false) }
        }
    }

    suspend fun eligible(context: Context): Boolean = withContext(Dispatchers.IO) {
        val manager = AppWidgetManager.getInstance(context)
        val hasWidgets = manager.getAppWidgetIds(ComponentName(context, MetroWidgetProvider::class.java)).isNotEmpty()
        val prefs = context.getSharedPreferences("metro11_preferences", Context.MODE_PRIVATE)
        val station = prefs.getString("station", null)
        val line = prefs.getString("line", null)
        if (!hasWidgets || station.isNullOrBlank() || line.isNullOrBlank()) return@withContext false
        val supported = IdfmStationReference.load(context).any {
            it.lineRef == MetroLineReferences.forLine(line) && MetroCatalog.normalize(it.name) == MetroCatalog.normalize(station)
        }
        MetroRefreshPolicy.eligible(hasWidgets, true, IdfmKeyStore(context).read() is IdfmKeyStore.State.Available, supported)
    }

    suspend fun synchronize(context: Context, immediate: Boolean = false, replace: Boolean = false,
                            force: Boolean = false) = withContext(Dispatchers.IO) {
        scheduling.withLock {
            val wm = WorkManager.getInstance(context)
            if (!eligible(context)) {
                cancelAll(wm)
                MetroWidgetProvider.refreshAll(context, loadingOverride = false)
                return@withLock
            }
            // Filet de reprise persistant après arrêt du processus.
            val periodic = PeriodicWorkRequestBuilder<MetroRefreshWorker>(MetroRefreshPolicy.PERIOD_MINUTES, TimeUnit.MINUTES)
                .setInitialDelay(MetroRefreshPolicy.PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic).await()
            if (replace) {
                wm.cancelUniqueWork(LOOP).await()
                wm.cancelUniqueWork(IMMEDIATE).await()
            }
            if (immediate) {
                val running = wm.getWorkInfosForUniqueWork(IMMEDIATE).get(5, TimeUnit.SECONDS)
                    .any { it.state == WorkInfo.State.RUNNING }
                val request = immediateRequest(force)
                // Un clic remplace une attente précédente, sans interrompre un chargement en cours.
                val policy = if (replace || (force && !running)) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
                wm.enqueueUniqueWork(IMMEDIATE, policy, request).await()
                MetroWidgetProvider.refreshAll(context)
            }
            enqueueNext(wm, if (immediate) MetroRefreshPolicy.INTERVAL_MILLIS else 0)
        }
    }

    suspend fun scheduleNext(context: Context) = withContext(Dispatchers.IO) {
        scheduling.withLock {
            if (eligible(context))
                enqueueNext(WorkManager.getInstance(context), MetroRefreshPolicy.INTERVAL_MILLIS)
        }
    }

    private suspend fun enqueueNext(wm: WorkManager, minimumDelay: Long) {
        val work = wm.getWorkInfosForUniqueWork(LOOP).get(5, TimeUnit.SECONDS)
        // Un seul successeur : les demandes manuelles et périodiques ne doublent pas la chaîne.
        if (work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return
        val delay = minimumDelay
        val request = delayedRequest(delay)
        wm.enqueueUniqueWork(LOOP, ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
    }

    internal fun immediateRequest(force: Boolean): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<MetroRefreshWorker>().setConstraints(constraints)
            .setInputData(workDataOf(FORCE to force))
        // Avant Android 12, expedited impose un service avec notification : conserver le chemin standard.
        if (force && android.os.Build.VERSION.SDK_INT >= 31)
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        return builder.build()
    }

    internal fun delayedRequest(delay: Long = MetroRefreshPolicy.INTERVAL_MILLIS): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<MetroRefreshWorker>().setConstraints(constraints)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).build()

    suspend fun cancel(context: Context) = withContext(Dispatchers.IO) {
        scheduling.withLock { cancelAll(WorkManager.getInstance(context)) }
    }
    private suspend fun cancelAll(wm: WorkManager) {
        for (name in listOf(PERIODIC, IMMEDIATE, LOOP)) wm.cancelUniqueWork(name).await()
    }

    fun isRefreshing(context: Context): Boolean = runCatching {
        val wm = WorkManager.getInstance(context)
        listOf(IMMEDIATE, PERIODIC, LOOP).any { name ->
            wm.getWorkInfosForUniqueWork(name).get(1, TimeUnit.SECONDS).any {
                it.state == WorkInfo.State.RUNNING
            }
        }
    }.getOrDefault(false)
}
