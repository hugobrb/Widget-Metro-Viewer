package fr.widgetmetroviewer.departures

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Réglages à droite ; les préférences et les jauges conservent leur stockage existant. */
@Composable
internal fun MetroSettingsPanel(themeMode: String, onThemeChange: (String) -> Unit,
                                content: @Composable (openSettings: () -> Unit) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = minOf(380.dp, maxWidth * .90f)
        // Le tiroir suit le bord de début RTL ; son contenu et l'écran restent en français LTR.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            // Ne pas concurrencer le défilement vertical avec une ouverture horizontale.
            ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet(modifier = Modifier.width(panelWidth).fillMaxHeight(),
                        drawerShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Paramètres", modifier = Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { scope.launch { drawer.close() } }) { Text("Fermer") }
                            }
                            Text("Apparence", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("light" to "Clair", "dark" to "Sombre", "auto" to "Auto").forEach { (mode, label) ->
                                    FilterChip(selected = themeMode == mode, onClick = { onThemeChange(mode) }, label = { Text(label) })
                                }
                            }
                            if (themeMode == "auto") Text("Suit le thème du téléphone.", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider()
                            IdfmUsageSection()
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    content { scope.launch { drawer.open() } }
                }
            }
        }
    }
}
