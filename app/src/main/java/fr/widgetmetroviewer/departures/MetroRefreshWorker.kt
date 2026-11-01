package fr.widgetmetroviewer.departures

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** Les secrets ne sont jamais passés dans les données ou les résultats WorkManager. */
class MetroRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        try {
            if (!MetroPhoneState.canRefresh(context) || !MetroRefreshScheduler.eligible(context)) {
                MetroWidgetProvider.publishNow(context, loading = false)
                return Result.success()
            }
            val prefs = context.getSharedPreferences("metro11_preferences", Context.MODE_PRIVATE)
            val station = prefs.getString("station", null) ?: return Result.success()
            val line = prefs.getString("line", null) ?: return Result.success()
            MetroWidgetProvider.refreshAll(context, loadingOverride = true)
            MetroDeparturesRepository.get(context).load(station, line,
                forceRefresh = inputData.getBoolean(MetroRefreshScheduler.FORCE, false))
            // Les erreurs sont traitées par le repository : pas de retry agressif WorkManager.
            MetroWidgetProvider.publishNow(context, loading = false)
            return Result.success()
        } catch (error: CancellationException) {
            MetroWidgetProvider.refreshAll(context, loadingOverride = false)
            throw error
        } catch (_: Exception) {
            MetroWidgetProvider.refreshAll(context, loadingOverride = false)
            return Result.failure()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { MetroRefreshScheduler.scheduleNext(context) }
            }
        }
    }
}
