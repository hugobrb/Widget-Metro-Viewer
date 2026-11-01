package fr.widgetmetroviewer.departures

/** Etat minimal en memoire : aucune liste d'applications ni historique conserve. */
internal class MetroForegroundTracker {
    private var packageName: String? = null
    private var activityName: String? = null
    private var timestamp: Long = Long.MIN_VALUE

    fun resumed(pkg: String, activity: String?, at: Long) {
        if (at < timestamp) return
        packageName = pkg
        activityName = activity
        timestamp = at
    }
    fun paused(pkg: String, activity: String?, at: Long) {
        if (at < timestamp) return
        if (pkg == packageName && (activity == null || activityName == null || activity == activityName)) {
            packageName = null
            activityName = null
            timestamp = at
        }
    }
    fun clear() { packageName = null; activityName = null; timestamp = Long.MIN_VALUE }
    fun allowsRefresh(appVisible: Boolean, usageAccess: Boolean, homePackage: String?): Boolean =
        appVisible || (usageAccess && homePackage != null && packageName == homePackage)
}
