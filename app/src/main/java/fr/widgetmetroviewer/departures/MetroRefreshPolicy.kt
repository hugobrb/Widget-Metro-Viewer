package fr.widgetmetroviewer.departures

internal object MetroRefreshPolicy {
    const val INTERVAL_MILLIS = 30_000L
    const val PERIOD_MINUTES = 15L
    fun eligible(hasWidgets: Boolean, configured: Boolean, keyReadable: Boolean, supported: Boolean): Boolean =
        hasWidgets && configured && keyReadable && supported
}
