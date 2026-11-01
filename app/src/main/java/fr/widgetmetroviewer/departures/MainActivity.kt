package fr.widgetmetroviewer.departures

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("metro11_preferences", Context.MODE_PRIVATE)
        setContent {
            var themeMode by rememberSaveable {
                mutableStateOf(preferences.getString("theme_mode", "auto") ?: "auto")
            }
            val dark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val colors = if (dark) darkColorScheme(
                primary = Color(0xFFB8C6FF), onPrimary = Color(0xFF101A49),
                background = Color(0xFF0B101B), surface = Color(0xFF151E2D),
                onBackground = Color(0xFFE8EDF6), onSurface = Color(0xFFE8EDF6),
                onSurfaceVariant = Color(0xFFB9C4D6), outlineVariant = Color(0xFF3B475C)
            ) else lightColorScheme(
                primary = Color(0xFF101A49), onPrimary = Color.White,
                background = Color(0xFFF2F3F5), surface = Color.White,
                onBackground = Color(0xFF101A49), onSurface = Color(0xFF101A49),
                onSurfaceVariant = Color(0xFF596575), outlineVariant = Color(0xFFD5DAE3)
            )
            MaterialTheme(colorScheme = colors) {
                MetroConfigurationScreen(
                    themeMode = themeMode,
                    onThemeChange = { mode ->
                        themeMode = mode
                        preferences.edit().putString("theme_mode", mode).apply()
                    },
                    initialStation = preferences.getString("station", null),
                    initialLine = preferences.getString("line", null),
                    initialLineId = preferences.getString("line_id", null),
                    onSave = { station, line ->
                        preferences.edit()
                            .putString("station", station.name)
                            .putString("line", line.name)
                            .putString("line_id", line.id)
                            .apply()
                        MetroWidgetProvider.refreshAll(this@MainActivity)
                        MetroRefreshScheduler.configurationChanged(this@MainActivity)
                    }
                )
            }
        }
    }
}

@Composable
private fun MetroConfigurationScreen(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    initialStation: String?,
    initialLine: String?,
    initialLineId: String?,
    onSave: (MetroStation, MetroLine) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<CatalogResult?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedLineId by rememberSaveable { mutableStateOf(initialLineId) }
    var selectedLineName by rememberSaveable { mutableStateOf(initialLine) }
    var selectedStationName by rememberSaveable { mutableStateOf(initialStation) }
    var savedMessage by rememberSaveable { mutableStateOf(false) }
    var departuresRevision by remember { mutableIntStateOf(0) }

    suspend fun load() {
        busy = true
        error = null
        try {
            catalog = withContext(Dispatchers.IO) { MetroCatalog.load(context.applicationContext) }
        } catch (exception: Exception) {
            if (exception is kotlinx.coroutines.CancellationException) throw exception
            error = "Impossible de lire le catalogue. Réessaie le chargement."
        } finally { busy = false }
    }
    LaunchedEffect(Unit) { load() }

    val lines = catalog?.lines.orEmpty()
    val line = lines.firstOrNull { it.id == selectedLineId }
        ?: lines.firstOrNull { it.name == selectedLineName }
    val station = line?.stations?.firstOrNull {
        selectedStationName?.let { name -> MetroCatalog.normalize(it.name) == MetroCatalog.normalize(name) } == true
    }

    MetroSettingsPanel(themeMode, onThemeChange) { openSettings ->
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Text("M", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("MON PANNEAU MÉTRO", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("Tes prochains départs, à portée de regard", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = openSettings) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_settings), "Paramètres")
                }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Préparation des lignes et stations…")
            }
            if (catalog == null) MetroPlaceholder()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (error != null) {
                OutlinedButton(enabled = !busy, onClick = { scope.launch { load() } }) {
                    Text("Réessayer")
                }
            }
            if (catalog != null) {
                SelectionMenu(
                    title = "CHOISIR LA LIGNE",
                    selected = line?.let { "Ligne ${MetroLineStyles.label(it.name)}" } ?: "Choisir une ligne",
                    selectedLine = line?.name,
                    badgeLine = { it.name },
                    values = lines,
                    label = { "Ligne ${MetroLineStyles.label(it.name)}" },
                    enabled = !busy,
                    onSelect = { chosen ->
                        selectedLineId = chosen.id
                        selectedLineName = chosen.name
                        if (chosen.id != line?.id) selectedStationName = null
                        savedMessage = false
                    }
                )
                SelectionMenu(
                    title = "CHOISIR LA STATION",
                    selected = station?.name ?: "Choisir une station",
                    selectedLine = line?.name,
                    badgeLine = { line?.name.orEmpty() },
                    values = line?.stations.orEmpty(),
                    searchable = true,
                    section = { it.section },
                    label = { it.name },
                    enabled = !busy && line != null,
                    onSelect = { selectedStationName = it.name; savedMessage = false }
                )
                Text("APERÇU DU WIDGET", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (line != null && station != null) {
                    MetroPreview(station, line.name, departuresRevision)
                } else {
                    MetroPlaceholder()
                }
                Button(
                    enabled = !busy && line != null && station != null,
                    onClick = {
                        if (line != null && station != null) {
                            onSave(station, line)
                            savedMessage = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("ENREGISTRER MA CONFIGURATION", fontWeight = FontWeight.Bold)
                }
                if (savedMessage) Text("✓ Configuration enregistrée et widget actualisé",
                    color = if (themeMode == "dark" || (themeMode == "auto" && isSystemInDarkTheme())) Color(0xFF81D89E) else Color(0xFF237A43))
            }
            IdfmConnectionCard(selectedStation = station?.name, selectedLine = line?.name,
                onDataChanged = { keyChanged ->
                    if (keyChanged) {
                        MetroDeparturesRepository.get(context).invalidateConnectionState()
                        MetroRefreshScheduler.configurationChanged(context)
                    }
                    departuresRevision++
                    MetroWidgetProvider.refreshAll(context)
                })
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MetroCatalog.SOURCE)))
            }) { Text("Source : Île-de-France Mobilités · Licence ODbL") }
        }
    }
    }
}

@Composable
private fun <T> SelectionMenu(
    title: String,
    selected: String,
    selectedLine: String?,
    badgeLine: (T) -> String,
    values: List<T>,
    label: (T) -> String,
    enabled: Boolean,
    section: (T) -> String? = { null },
    searchable: Boolean = false,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(selectedLine) { mutableStateOf("") }
    Card(shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                    if (selectedLine != null) {
                        MetroLineBadge(selectedLine)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(selected, modifier = Modifier.weight(1f),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("⌄", fontSize = 22.sp)
                }
                DropdownMenu(expanded = expanded && !searchable, onDismissRequest = { expanded = false },
                    modifier = Modifier.heightIn(max = 320.dp)) {
                    values.forEachIndexed { index, value ->
                        val group = section(value)
                        if (group != null && (index == 0 || group != section(values[index - 1]))) {
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text(group, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenuItem(text = { Text(label(value)) },
                            leadingIcon = { MetroLineBadge(badgeLine(value)) }, onClick = {
                            onSelect(value)
                            expanded = false
                        })
                    }
                }
            }
        }
    }
    if (searchable && expanded) {
        val normalizedQuery = MetroCatalog.normalize(query)
        val filtered = values.filter { normalizedQuery in MetroCatalog.normalize(label(it)) }
        AlertDialog(
            onDismissRequest = { expanded = false; query = "" },
            title = { Text("Choisir une station") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("Rechercher une station") },
                        placeholder = { Text("Nom de la station") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Effacer") }
                        }
                    )
                    if (filtered.isEmpty()) {
                        Text("Aucune station trouvée sur cette ligne.")
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                            itemsIndexed(filtered) { index, value ->
                                val group = section(value)
                                if (group != null && (index == 0 || group != section(filtered[index - 1]))) {
                                    Text(group, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 8.dp))
                                }
                                ListItem(
                                    headlineContent = { Text(label(value)) },
                                    leadingContent = { MetroLineBadge(badgeLine(value)) },
                                    modifier = Modifier.clickable {
                                        onSelect(value)
                                        expanded = false
                                        query = ""
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false; query = "" }) { Text("Fermer") }
            }
        )
    }

}

@Composable
private fun MetroPreview(station: MetroStation, line: String, revision: Int) {
    val context = LocalContext.current
    val nominal = remember(station.name, line) { MetroDirections.get(context).forStation(station.name, line) }
    var result by remember(station.name, line, revision) { mutableStateOf<DeparturesResult?>(null) }
    var loading by remember(station.name, line, revision) { mutableStateOf(true) }
    var now by remember { mutableStateOf(Instant.now()) }
    var manualRefresh by remember { mutableIntStateOf(0) }
    var forceNext by remember { mutableStateOf(false) }
    var resumeRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var previewVisible by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner) {
        var initialResume = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            previewVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (initialResume) initialResume = false else resumeRevision++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(station.name, line, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            val repository = MetroDeparturesRepository.get(context)
            repository.changes.collect { result = repository.peek(station.name, line); now = Instant.now() }
        }
    }
    // Aucun tick de l'aperçu quand l'activité n'est plus visible.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (isActive) {
                delay(1000)
                if (MetroPhoneState.canRefresh(context)) now = Instant.now()
            }
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (isActive) {
                delay(MetroRefreshPolicy.INTERVAL_MILLIS)
                if (MetroPhoneState.canRefresh(context)) resumeRevision++
            }
        }
    }
    LaunchedEffect(station.name, line, revision, manualRefresh, resumeRevision, previewVisible) {
        if (!previewVisible) return@LaunchedEffect
        val repository = MetroDeparturesRepository.get(context)
        try {
            result = repository.peek(station.name, line)
            loading = result?.state !in listOf(DeparturesState.DEMO, DeparturesState.UNSUPPORTED)
            val force = forceNext
            forceNext = false
            result = repository.load(station.name, line, forceRefresh = force)
            now = Instant.now()
            MetroWidgetProvider.refreshAll(context)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            result = DeparturesResult(DeparturesState.UNAVAILABLE, message = "Passages indisponibles. Réessaie plus tard.")
        } finally { loading = false }
    }
    val display = remember(station.name, line, nominal, result, now, loading) {
        MetroPanelPresentation.build(station.name, line, nominal, result, now, loading)
    }
    val panel = remember(station.name, line, display) {
        MetroPanelRenderer.bitmap(station.name, line, display).asImageBitmap()
    }
    val informationFallback = if (display.informationNotices.isEmpty()) display.information else ""
    val informationViews = remember(line, display.informationNotices, informationFallback) {
        MetroInformationViews.build(context, line, display)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().aspectRatio(MetroPanelRenderer.ASPECT_RATIO)) {
            Image(bitmap = panel, contentDescription = MetroPanelRenderer.description(station.name, line, display),
                modifier = Modifier.matchParentSize())
            AndroidView(modifier = Modifier.matchParentSize(), factory = { viewContext ->
                informationViews.apply(viewContext, android.widget.FrameLayout(viewContext)).also { it.tag = informationViews }
            }, update = { view ->
                if (view.tag !== informationViews) {
                    informationViews.reapply(context, view)
                    view.tag = informationViews
                }
            })
            IconButton(onClick = { forceNext = true; manualRefresh++ }, enabled = !loading,
                modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_widget_refresh), "Actualiser les passages", tint = Color(0xFF060833))
            }
            AndroidView(modifier = Modifier.matchParentSize(), factory = { viewContext ->
                LayoutInflater.from(viewContext).inflate(R.layout.metro_panel_clock, null)
            })
        }
        if (display.demo) Text("Mode démonstration · horaires fictifs", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (result?.state == DeparturesState.UNSUPPORTED) Text("Le temps réel n’est pas encore disponible pour cette station.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        result?.traffic?.let { traffic ->
            val board = MetroTrafficPresentation.build(traffic, now)
            val messages = board.notices
            if (messages.isNotEmpty()) {
                var expanded by remember(station.name, line) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Masquer les informations" else "Voir toutes les informations (" + messages.size + ")")
                }
                if (expanded) messages.forEach { message ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        message.lines.chunked(6).forEach { names ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                names.forEach { name -> MetroLineBadge(name) }
                            }
                        }
                        Text(message.displayText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Pastille de ligne, avec le numéro et la mention bis sur deux niveaux. */
@Composable
private fun MetroLineBadge(line: String) {
    val style = MetroLineStyles.get(line)
    val bis = MetroLineStyles.isBis(line)
    Column(
        modifier = Modifier.size(36.dp).background(Color(style.background), CircleShape),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(MetroLineStyles.number(line), color = Color(style.foreground),
            fontSize = if (bis) 18.sp else 21.sp,
            lineHeight = if (bis) 18.sp else 24.sp,
            fontWeight = FontWeight.Bold, maxLines = 1)
        if (bis) Text("bis", color = Color(style.foreground), fontSize = 8.sp,
            lineHeight = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MetroPlaceholder() {
    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(MetroPanelRenderer.ASPECT_RATIO),
        factory = { context ->
            LayoutInflater.from(context).inflate(R.layout.metro_panel_placeholder, null)
        }
    )
}
