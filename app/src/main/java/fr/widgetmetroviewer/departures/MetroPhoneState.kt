package fr.widgetmetroviewer.departures

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/** Écran réellement interactif, sans écran de verrouillage (y compris sans code PIN). */
internal object MetroPhoneState {
    fun canRefresh(context: Context): Boolean = isUnlocked(context) && MetroRefreshVisibility.isAllowed(context)

    fun isUnlocked(context: Context): Boolean = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked
    }.getOrDefault(false)
}
