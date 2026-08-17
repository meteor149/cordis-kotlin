package org.cordis.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PluginLabApp(runtime: PluginLabRuntime) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(runtime) {
        try {
            runtime.start()
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { runtime.close() }
        }
    }

    val theme = state.theme
    val colors = MaterialTheme.colorScheme.copy(
        primary = Color(theme.accent),
        onPrimary = Color(theme.buttonText),
        surface = Color(theme.surface),
        onSurface = Color(theme.primaryText),
        secondary = Color(theme.accentSoft),
        onSecondary = Color(theme.primaryText),
    )
    val typography = Typography(
        headlineLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 34.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.1).sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 21.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        ),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.7.sp,
        ),
    )

    MaterialTheme(colorScheme = colors, typography = typography) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(theme.backgroundTop), Color(theme.backgroundBottom))),
                ),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 920.dp
                    if (wide) {
                        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            MainConsole(runtime, state, Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                            SignalRail(state.events, Modifier.width(350.dp).fillMaxHeight())
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            MainConsole(runtime, state, Modifier.fillMaxWidth())
                            SignalRail(state.events, Modifier.fillMaxWidth().height(430.dp))
                        }
                    }
                }
            }
        }
    }

    state.surface?.let { surface ->
        AlertDialog(
            onDismissRequest = runtime::dismissSurface,
            title = { Text(surface.title, style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(surface.body)
                    Text(
                        surface.provenance.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = { TextButton(onClick = runtime::dismissSurface) { Text("Close proof") } },
        )
    }
}

@Composable
private fun MainConsole(runtime: PluginLabRuntime, state: PluginLabState, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 980.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Header(runtime, state)
        TimerInstrument(state)
        PluginRack(runtime, state)
        RuntimeActions(runtime, state)
    }
}

@Composable
private fun Header(runtime: PluginLabRuntime, state: PluginLabState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
                    .background(Color(state.theme.accent).copy(alpha = 0.18f))
                    .border(1.dp, Color(state.theme.accent).copy(alpha = 0.48f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.theme.symbol, color = Color(state.theme.accent), fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("CORDIS / PLUGIN LAB", style = MaterialTheme.typography.labelMedium, color = Color(state.theme.accent))
                AnimatedContent(state.theme.name) { name ->
                    Text(name, style = MaterialTheme.typography.headlineLarge, color = Color(state.theme.primaryText))
                }
            }
            StatusLamp(state.ready, state.busy)
        }
        Text(state.theme.tagline, style = MaterialTheme.typography.bodyLarge, color = Color(state.theme.secondaryText))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataPill(runtime.platformName)
            DataPill(runtime.artifactKind)
            DataPill("GEN ${state.generation}")
        }
    }
}

@Composable
private fun StatusLamp(ready: Boolean, busy: Boolean) {
    val color = when {
        busy -> Color(0xFFFFC66D)
        ready -> Color(0xFF69D5BD)
        else -> Color(0xFFFF7F7F)
    }
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.07f)).padding(10.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(if (busy) "BUSY" else if (ready) "ONLINE" else "OFFLINE", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun DataPill(value: String) {
    Text(
        value.uppercase(),
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.07f)).padding(9.dp, 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
    )
}

@Composable
private fun TimerInstrument(state: PluginLabState) {
    var selectedMinutes by remember { mutableIntStateOf(5) }
    var remainingSeconds by remember { mutableIntStateOf(selectedMinutes * 60) }
    var running by remember { mutableStateOf(false) }
    val totalSeconds = selectedMinutes * 60
    val progress by animateFloatAsState(
        if (totalSeconds == 0) 0f else remainingSeconds.toFloat() / totalSeconds,
        label = "timer-progress",
    )

    LaunchedEffect(running) {
        while (running && remainingSeconds > 0) {
            delay(1_000)
            remainingSeconds--
        }
        if (remainingSeconds == 0) running = false
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(state.theme.surface).copy(alpha = 0.92f)),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().border(
            1.dp,
            Color(state.theme.accent).copy(alpha = 0.22f),
            RoundedCornerShape(28.dp),
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
            val compact = maxWidth < 620.dp
            if (compact) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    TimerDial(remainingSeconds, progress, state, Modifier.size(238.dp))
                    TimerControls(selectedMinutes, running, onPreset = {
                        selectedMinutes = it
                        remainingSeconds = it * 60
                        running = false
                    }, onToggle = {
                        if (remainingSeconds == 0) remainingSeconds = selectedMinutes * 60
                        running = !running
                    }, onReset = {
                        running = false
                        remainingSeconds = selectedMinutes * 60
                    })
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    TimerDial(remainingSeconds, progress, state, Modifier.size(250.dp))
                    TimerControls(selectedMinutes, running, onPreset = {
                        selectedMinutes = it
                        remainingSeconds = it * 60
                        running = false
                    }, onToggle = {
                        if (remainingSeconds == 0) remainingSeconds = selectedMinutes * 60
                        running = !running
                    }, onReset = {
                        running = false
                        remainingSeconds = selectedMinutes * 60
                    }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TimerDial(seconds: Int, progress: Float, state: PluginLabState, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.045f
            val inset = stroke * 1.8f
            drawArc(
                Color(state.theme.primaryText).copy(alpha = 0.10f),
                -90f,
                360f,
                false,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                Color(state.theme.accent),
                -90f,
                360f * progress,
                false,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val minutes = seconds / 60
            val remainder = seconds % 60
            Text(
                "${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}",
                fontFamily = FontFamily.Monospace,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = Color(state.theme.primaryText),
            )
            Text("SESSION CLOCK", style = MaterialTheme.typography.labelMedium, color = Color(state.theme.secondaryText))
        }
    }
}

@Composable
private fun TimerControls(
    selectedMinutes: Int,
    running: Boolean,
    onPreset: (Int) -> Unit,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("HOST STATE SURVIVES PLUGIN REPLACEMENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Keep this timer running, then replace Forest. The clock belongs to the Compose host while the palette belongs to the plugin.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 25).forEach { minutes ->
                OutlinedButton(onClick = { onPreset(minutes) }, enabled = minutes != selectedMinutes) {
                    Text("$minutes min")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onToggle, modifier = Modifier.weight(1f)) { Text(if (running) "Pause clock" else "Start clock") }
            OutlinedButton(onClick = onReset) { Text("Reset") }
        }
    }
}

@Composable
private fun PluginRack(runtime: PluginLabRuntime, state: PluginLabState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Plugin rack", style = MaterialTheme.typography.headlineSmall)
                Text("Each card proves a different class-loader boundary.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f))
            }
            Text("${runtime.plugins.size} ARTIFACTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 680.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    runtime.plugins.forEach { plugin ->
                        PluginCard(runtime, state, plugin, Modifier.weight(1f))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    runtime.plugins.forEach { plugin -> PluginCard(runtime, state, plugin, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun PluginCard(runtime: PluginLabRuntime, state: PluginLabState, plugin: DemoPlugin, modifier: Modifier) {
    val active = state.activePluginId == plugin.id
    val shape = RoundedCornerShape(20.dp)
    val scope = rememberCoroutineScope()
    Column(
        modifier.clip(shape)
            .background(if (active) Color(plugin.accent).copy(alpha = 0.19f) else Color.White.copy(alpha = 0.055f))
            .border(if (active) 2.dp else 1.dp, Color(plugin.accent).copy(alpha = if (active) 0.8f else 0.22f), shape)
            .clickable(enabled = state.ready && !state.busy) { scope.launch { runtime.activate(plugin.id) } }
            .padding(16.dp)
            .testTag("plugin-${plugin.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(plugin.symbol, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color(plugin.accent))
            Spacer(Modifier.weight(1f))
            Text(if (active) "ACTIVE" else "LOAD", style = MaterialTheme.typography.labelMedium, color = Color(plugin.accent))
        }
        Text(plugin.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(plugin.subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
        HorizontalDivider(color = Color(plugin.accent).copy(alpha = 0.18f))
        Text(plugin.proof.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color(plugin.accent))
    }
}

@Composable
private fun RuntimeActions(runtime: PluginLabRuntime, state: PluginLabState) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Runtime probes", style = MaterialTheme.typography.headlineSmall)
        runtime.features.forEach { feature ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text("✓", color = Color(0xFF69D5BD), fontWeight = FontWeight.Bold)
                Column {
                    Text(feature.name, fontWeight = FontWeight.SemiBold)
                    Text(feature.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { scope.launch { runtime.installNextGeneration() } },
                enabled = state.ready && !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7EA2FF), contentColor = Color(0xFF0C1427)),
                modifier = Modifier.testTag("reload-next"),
            ) { Text("Replace Forest") }
            OutlinedButton(
                onClick = { scope.launch { runtime.runRollbackProbe() } },
                enabled = state.ready && !state.busy,
                modifier = Modifier.testTag("rollback-probe"),
            ) { Text("Prove rollback") }
        }
        OutlinedButton(
            onClick = { scope.launch { runtime.openPluginSurface() } },
            enabled = state.activePluginId != null && !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("open-surface"),
        ) { Text(if (runtime.platformName == "Android") "Open plugin Activity + resources  ↗" else "Read active JAR resource  ↗") }
        AnimatedVisibility(state.status.isNotBlank()) {
            Text(state.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SignalRail(events: List<LabEvent>, modifier: Modifier = Modifier) {
    Card(
        modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE0C1427)),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text("SIGNAL / EVENT BUS", style = MaterialTheme.typography.labelMedium, color = Color(0xFF7EA2FF))
            Text("Cordis lifecycle trace", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFF5F7FF))
            Text(
                "Newest signal enters at the top. A rollback is amber; committed generations are mint.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAAB8D8),
            )
            Spacer(Modifier.height(16.dp))
            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Waiting for runtime…", style = MaterialTheme.typography.labelMedium, color = Color(0xFF657493))
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    events.forEachIndexed { index, event -> SignalNode(event, index != events.lastIndex) }
                }
            }
        }
    }
}

@Composable
private fun SignalNode(event: LabEvent, continues: Boolean) {
    val color = when (event.kind) {
        LabEventKind.INFO -> Color(0xFF7EA2FF)
        LabEventKind.SUCCESS -> Color(0xFF69D5BD)
        LabEventKind.ROLLBACK -> Color(0xFFFFC66D)
        LabEventKind.ERROR -> Color(0xFFFF7F7F)
    }
    Row(Modifier.fillMaxWidth().height(74.dp)) {
        Box(Modifier.width(28.dp).fillMaxHeight()) {
            if (continues) Box(Modifier.width(1.dp).fillMaxHeight().align(Alignment.Center).background(Color(0xFF2B3B5C)))
            Box(
                Modifier.size(13.dp).align(Alignment.TopCenter).clip(CircleShape).background(color)
                    .border(3.dp, Color(0xFF16213A), CircleShape),
            )
        }
        Column(Modifier.padding(start = 9.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(event.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = color)
                Spacer(Modifier.weight(1f))
                Text("#${event.sequence.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelMedium, color = Color(0xFF657493))
            }
            Text(event.detail, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC1CAE0), maxLines = 2)
        }
    }
}
