package org.cordis.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.cordis.demo.api.PluginSurfaceInfo
import org.cordis.demo.api.TimerTheme
import org.cordis.demo.api.TimerThemeSink

data class DemoPlugin(
    val id: String,
    val title: String,
    val subtitle: String,
    val symbol: String,
    val accent: Int,
    val proof: String,
)

data class RuntimeFeature(
    val name: String,
    val detail: String,
)

enum class LabEventKind { INFO, SUCCESS, ROLLBACK, ERROR }

data class LabEvent(
    val sequence: Int,
    val label: String,
    val detail: String,
    val kind: LabEventKind,
)

data class PluginLabState(
    val theme: TimerTheme = DEFAULT_THEME,
    val status: String = "Runtime is offline",
    val activePluginId: String? = null,
    val generation: String = "—",
    val busy: Boolean = false,
    val ready: Boolean = false,
    val surface: PluginSurfaceInfo? = null,
    val events: List<LabEvent> = emptyList(),
)

abstract class PluginLabRuntime(
    val platformName: String,
    val artifactKind: String,
    val plugins: List<DemoPlugin>,
    val features: List<RuntimeFeature>,
) : TimerThemeSink {
    private val mutableState = MutableStateFlow(PluginLabState())
    val state: StateFlow<PluginLabState> = mutableState.asStateFlow()
    private var sequence = 0

    abstract suspend fun start()
    abstract suspend fun activate(pluginId: String)
    abstract suspend fun installNextGeneration()
    abstract suspend fun runRollbackProbe()
    abstract suspend fun openPluginSurface()
    abstract suspend fun close()

    final override fun applyTheme(theme: TimerTheme) {
        mutableState.update { it.copy(theme = theme) }
        record("effect/apply", "${theme.name} changed the shared Compose UI", LabEventKind.SUCCESS)
    }

    final override fun clearTheme(themeId: String) {
        mutableState.update { current ->
            if (current.theme.id == themeId) current.copy(theme = DEFAULT_THEME) else current
        }
        record("effect/dispose", "$themeId released its UI effect", LabEventKind.INFO)
    }

    protected suspend fun operation(label: String, block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, status = label) }
        try {
            block()
        } catch (error: Throwable) {
            record("operation/error", error.message ?: error::class.simpleName.orEmpty(), LabEventKind.ERROR)
            mutableState.update { it.copy(status = "Operation failed: ${error.message ?: "unknown error"}") }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    protected fun markReady(detail: String) {
        mutableState.update { it.copy(ready = true, status = detail) }
        record("runtime/ready", detail, LabEventKind.SUCCESS)
    }

    protected fun markActive(pluginId: String, generation: String, detail: String) {
        mutableState.update {
            it.copy(activePluginId = pluginId, generation = generation, status = detail, surface = null)
        }
        record("entry/active", "$pluginId · $generation", LabEventKind.SUCCESS)
    }

    protected fun markRollback(detail: String) {
        mutableState.update { it.copy(status = detail) }
        record("reload/rollback", detail, LabEventKind.ROLLBACK)
    }

    protected fun showSurface(surface: PluginSurfaceInfo) {
        mutableState.update { it.copy(surface = surface) }
        record("resource/open", surface.provenance, LabEventKind.INFO)
    }

    fun dismissSurface() {
        mutableState.update { it.copy(surface = null) }
    }

    protected fun record(label: String, detail: String, kind: LabEventKind = LabEventKind.INFO) {
        val event = LabEvent(++sequence, label, detail, kind)
        mutableState.update { it.copy(events = (listOf(event) + it.events).take(MAX_EVENTS)) }
    }

    companion object {
        private const val MAX_EVENTS = 18
    }
}

val DEMO_PLUGINS = listOf(
    DemoPlugin(
        id = "theme.forest",
        title = "Forest",
        subtitle = "Hot-swappable",
        symbol = "✦",
        accent = 0xFF69D5BD.toInt(),
        proof = "fresh generation + rollback",
    ),
    DemoPlugin(
        id = "theme.ocean",
        title = "Ocean",
        subtitle = "Dependency graph",
        symbol = "◉",
        accent = 0xFF62C8FF.toInt(),
        proof = "declared palette module",
    ),
    DemoPlugin(
        id = "theme.sunset",
        title = "Sunset",
        subtitle = "Private classpath",
        symbol = "☼",
        accent = 0xFFFF9B78.toInt(),
        proof = "host API boundary",
    ),
)

val DEFAULT_THEME = TimerTheme(
    id = "default",
    name = "Cordis Plugin Lab",
    tagline = "Observe code crossing a runtime boundary",
    symbol = "⌁",
    backgroundTop = 0xFF111A2E.toInt(),
    backgroundBottom = 0xFF0A1020.toInt(),
    surface = 0xFF17233B.toInt(),
    surfaceStrong = 0xFF213154.toInt(),
    primaryText = 0xFFF5F7FF.toInt(),
    secondaryText = 0xFFAAB8D8.toInt(),
    accent = 0xFF7EA2FF.toInt(),
    accentSoft = 0xFF2B4070.toInt(),
    buttonText = 0xFF0C1427.toInt(),
    isLightStatusBar = false,
)
