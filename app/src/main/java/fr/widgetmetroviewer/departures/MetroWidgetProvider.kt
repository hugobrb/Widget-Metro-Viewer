package fr.widgetmetroviewer.departures

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class MetroWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshFromBroadcast(context, true)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        refreshFromBroadcast(context)
    }
    override fun onDisabled(context: Context) {
        MetroRefreshVisibility.stateChanged(context)
        val pending = goAsync()
        scope.launch {
            try { withTimeout(8000) { MetroRefreshScheduler.cancel(context.applicationContext) } }
            finally { pending.finish() }
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED)) refreshFromBroadcast(context)
    }
    private fun refreshFromBroadcast(context: Context, request: Boolean = false) {
        MetroRefreshVisibility.stateChanged(context)
        val pending = goAsync()
        scope.launch {
            try { withTimeout(8000) {
                MetroRefreshScheduler.synchronize(context.applicationContext, immediate = request)
                renderAll(context.applicationContext, revision.incrementAndGet(), null)
            } }
            catch (_: Exception) { /* Le squelette ou le dernier panneau reste visible. */ }
            finally { pending.finish() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val renderMutex = Mutex()
        private val revision = AtomicLong()
        private var localRefresh: Job? = null

        fun refreshAll(context: Context, loadingOverride: Boolean? = null) {
            val ticket = revision.incrementAndGet()
            scope.launch {
                try { withTimeout(8000) { renderAll(context.applicationContext, ticket, loadingOverride) } }
                catch (_: Exception) { }
            }
        }

        // La configuration est commune à tous les widgets pour cette version.
        suspend fun publishNow(context: Context, loading: Boolean) {
            renderAll(context.applicationContext, revision.incrementAndGet(), loading)
        }
        private suspend fun renderAll(context: Context, ticket: Long, loadingOverride: Boolean?) = renderMutex.withLock {
            if (ticket != revision.get()) return@withLock
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MetroWidgetProvider::class.java))
            if (ids.isEmpty()) { localRefresh?.cancel(); return@withLock }
            val prefs = context.getSharedPreferences("metro11_preferences", Context.MODE_PRIVATE)
            val station = prefs.getString("station", null)
            val line = prefs.getString("line", null)
            val views = RemoteViews(context.packageName, R.layout.metro_widget)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_placeholder, open)
            views.setOnClickPendingIntent(R.id.widget_panel, open)
            var nextLocalChange: Instant? = null
            if (station.isNullOrBlank() || line.isNullOrBlank()) {
                views.setViewVisibility(R.id.widget_info_container, View.GONE)
                views.setViewVisibility(R.id.widget_refresh_container, View.GONE)
                views.setViewVisibility(R.id.widget_placeholder, View.VISIBLE)
                views.setViewVisibility(R.id.widget_panel, View.GONE)
                views.setViewVisibility(R.id.widget_clock_container, View.GONE)
            } else {
                // Lecture locale uniquement : aucun appel HTTPS dans le receiver.
                val result = MetroDeparturesRepository.get(context).peek(station, line)
                if (result.origin != DeparturesOrigin.CACHE_STALE) nextLocalChange = result.snapshot?.nextVisualChange(Instant.now())
                val nominal = MetroDirections.get(context).forStation(station, line)
                val panel = MetroPanelPresentation.build(station, line, nominal, result, Instant.now(), loadingOverride ?: MetroRefreshScheduler.isRefreshing(context))
                views.setTextViewText(R.id.widget_station, station)
                views.setTextViewText(R.id.widget_line, MetroLineStyles.label(line))
                views.setTextViewText(R.id.widget_direction_1, panel.first.label)
                views.setTextViewText(R.id.widget_direction_2, panel.second.label)
                views.setTextViewText(R.id.widget_time_1, panel.first.value ?: panel.first.detail)
                views.setTextViewText(R.id.widget_time_2, panel.second.value ?: panel.second.detail)
                MetroInformationViews.populate(context, views, line, panel)
                views.setOnClickPendingIntent(R.id.widget_info_flipper, open)
                views.setImageViewBitmap(R.id.widget_panel, MetroPanelRenderer.bitmap(station, line, panel))
                views.setContentDescription(R.id.widget_panel, MetroPanelRenderer.description(station, line, panel))
                val refresh = PendingIntent.getBroadcast(context, 1,
                    Intent(context, MetroRefreshReceiver::class.java).setAction(MetroRefreshReceiver.ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_refresh, if (MetroRefreshVisibility.hasUsageAccess(context)) refresh else open)
                // Les travaux en attente ne doivent jamais bloquer les clics suivants.
                views.setBoolean(R.id.widget_refresh, "setEnabled", true)
                views.setViewVisibility(R.id.widget_refresh_container, View.VISIBLE)
                views.setViewVisibility(R.id.widget_panel, View.VISIBLE)
                views.setViewVisibility(R.id.widget_clock_container, View.VISIBLE)
                views.setViewVisibility(R.id.widget_placeholder, View.GONE)
            }
            if (ticket == revision.get()) {
                manager.updateAppWidget(ids, views)
                localRefresh?.cancel()
                val boundary = nextLocalChange
                if (boundary != null && MetroPhoneState.canRefresh(context)) localRefresh = scope.launch {
                    delay(Duration.between(Instant.now(), boundary).toMillis().coerceAtLeast(1))
                    // Transition visuelle locale, sans appel PRIM. Android peut suspendre le processus.
                    if (MetroPhoneState.canRefresh(context)) refreshAll(context)
                }
            }
        }
    }
}
