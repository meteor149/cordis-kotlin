package org.cordis.demo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.cordis.Context as CordisContext
import org.cordis.Fiber
import org.cordis.EffectHandle
import org.cordis.demo.api.TimerTheme
import org.cordis.demo.api.TimerThemeSink
import org.cordis.loader.AndroidModuleLoader
import org.cordis.loader.AndroidPluginComponents
import org.cordis.loader.EntryOptions
import org.cordis.loader.Loader
import org.cordis.loader.LoaderConfig
import org.cordis.loader.LoaderPlugin

class MainActivity : Activity(), TimerThemeSink {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timerHandler = Handler(Looper.getMainLooper())
    private val cordisContext = CordisContext()

    private lateinit var rootView: LinearLayout
    private lateinit var timerCard: LinearLayout
    private lateinit var brandSymbol: TextView
    private lateinit var themeName: TextView
    private lateinit var themeTagline: TextView
    private lateinit var statusText: TextView
    private lateinit var timeText: TextView
    private lateinit var dial: TimerDialView
    private lateinit var startButton: TextView
    private lateinit var resetButton: TextView
    private lateinit var detailButton: TextView
    private val presetButtons = mutableListOf<TextView>()
    private val themeCards = linkedMapOf<String, ThemeCardViews>()

    private var loaderFiber: Fiber<LoaderConfig>? = null
    private var themeService: EffectHandle? = null
    private var loader: Loader? = null
    private var moduleLoader: AndroidModuleLoader? = null
    private var components: AndroidPluginComponents? = null
    private var activePlugin: ThemePluginSpec? = null
    private var currentTheme: TimerTheme = DEFAULT_THEME
    private var switchingTheme = false

    private var durationMs = 25 * 60_000L
    private var remainingMs = durationMs
    private var endRealtime = 0L
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            remainingMs = (endRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            renderTime()
            if (remainingMs == 0L) {
                running = false
                statusText.text = "Session complete · Nicely done"
                startButton.text = "Start another"
                Toast.makeText(this@MainActivity, "Timer complete", Toast.LENGTH_SHORT).show()
            } else {
                timerHandler.postDelayed(this, 100L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        durationMs = savedInstanceState?.getLong(KEY_DURATION, durationMs) ?: durationMs
        remainingMs = savedInstanceState?.getLong(KEY_REMAINING, durationMs) ?: durationMs
        running = savedInstanceState?.getBoolean(KEY_RUNNING, false) ?: false
        endRealtime = savedInstanceState?.getLong(KEY_END_REALTIME, 0L) ?: 0L

        buildInterface()
        renderTheme(DEFAULT_THEME)
        renderTime()
        setPluginControlsEnabled(false)

        uiScope.launch {
            runCatching {
                themeService = cordisContext.provide(TimerThemeSink.Key, this@MainActivity).also { it.awaitReady() }
                loaderFiber = cordisContext.plugin(LoaderPlugin, LoaderConfig()).also { it.await() }
                val cordisLoader = cordisContext.require(Loader.Key)
                val androidLoader = AndroidModuleLoader(applicationContext)
                cordisLoader.internal = androidLoader
                loader = cordisLoader
                moduleLoader = androidLoader
                components = AndroidPluginComponents.install(androidLoader)
                PluginCatalog.installAll(applicationContext, androidLoader)
                setPluginControlsEnabled(true)
                val restored = savedInstanceState?.getString(KEY_PLUGIN_ID)
                activatePlugin(PluginCatalog.plugins.firstOrNull { it.id == restored } ?: PluginCatalog.plugins.first())
            }.onFailure { error ->
                Log.e(TAG, "Unable to initialize Cordis plugin runtime", error)
                statusText.text = "Plugin initialization failed"
                Toast.makeText(this@MainActivity, error.message ?: "Plugin initialization failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (running) {
            remainingMs = (endRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            timerHandler.post(ticker)
        }
    }

    override fun onStop() {
        timerHandler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_DURATION, durationMs)
        outState.putLong(KEY_REMAINING, remainingMs)
        outState.putLong(KEY_END_REALTIME, endRealtime)
        outState.putBoolean(KEY_RUNNING, running)
        outState.putString(KEY_PLUGIN_ID, activePlugin?.id)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
        val fiber = loaderFiber
        val service = themeService
        val installedComponents = components
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            fiber?.dispose()
            service?.dispose()
            installedComponents?.close()
        }
        uiScope.cancel()
        super.onDestroy()
    }

    override fun applyTheme(theme: TimerTheme) = onUiThread {
        currentTheme = theme
        renderTheme(theme)
    }

    override fun clearTheme(themeId: String) = onUiThread {
        if (currentTheme.id == themeId) {
            currentTheme = DEFAULT_THEME
            renderTheme(DEFAULT_THEME)
        }
    }

    private suspend fun activatePlugin(spec: ThemePluginSpec) {
        if (switchingTheme || activePlugin?.id == spec.id) return
        switchingTheme = true
        setPluginControlsEnabled(false)
        statusText.text = "Cordis is applying ${spec.title}…"
        try {
            val cordisLoader = checkNotNull(loader)
            val androidLoader = checkNotNull(moduleLoader)
            if (cordisLoader.store.containsKey(ACTIVE_THEME_ENTRY)) {
                cordisLoader.remove(ACTIVE_THEME_ENTRY)
            }
            // Import once at the Android boundary so verification/class-loading failures are
            // surfaced to this screen instead of being reduced to an inactive Loader entry.
            androidLoader.import(androidLoader.moduleUrl(spec.id), null)
            cordisLoader.create(
                EntryOptions(
                    id = ACTIVE_THEME_ENTRY,
                    name = androidLoader.moduleUrl(spec.id),
                    config = Unit,
                ),
            )
            checkNotNull(cordisLoader.resolve(ACTIVE_THEME_ENTRY).fiber) {
                "Plugin ${spec.title} failed to create a Cordis Fiber"
            }
            activePlugin = spec
            statusText.text = if (running) "Focus in progress" else "Ready · Pick a duration"
            renderPluginSelection()
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to activate ${spec.id}", error)
            statusText.text = "Theme switch failed"
            Toast.makeText(this, error.message ?: "Theme switch failed", Toast.LENGTH_LONG).show()
        } finally {
            switchingTheme = false
            setPluginControlsEnabled(true)
        }
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(36))
        }
        scroll.addView(rootView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandSymbol = text("✦", 30f, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(0x22FFFFFF, 18f)
        }
        header.addView(brandSymbol, LinearLayout.LayoutParams(dp(54), dp(54)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        themeName = text("Cordis Timer", 22f, Typeface.BOLD)
        themeTagline = text("A plugin-powered focus timer", 13f)
        titles.addView(themeName)
        titles.addView(themeTagline)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rootView.addView(header)

        timerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(22), dp(18), dp(20))
        }
        rootView.addView(timerCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(24) })

        statusText = text("Preparing plugins…", 13f, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }
        timerCard.addView(statusText)

        val dialFrame = FrameLayout(this)
        dial = TimerDialView(this)
        dialFrame.addView(dial, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        timeText = text("25:00", 56f, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
            letterSpacing = 0.02f
        }
        center.addView(timeText)
        center.addView(text("TIME REMAINING", 12f, Typeface.BOLD).apply {
            tag = TAG_MUTED_TEXT
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        })
        dialFrame.addView(center, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        timerCard.addView(dialFrame, LinearLayout.LayoutParams(dp(286), dp(286)).apply { topMargin = dp(8) })

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1 to "1 min", 5 to "5 min", 25 to "25 min").forEach { (minutes, label) ->
            val button = text(label, 13f, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { selectDuration(minutes) }
            }
            presetButtons += button
            presets.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        timerCard.addView(presets, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        startButton = actionButton("Start focus").apply { setOnClickListener { toggleTimer() } }
        resetButton = actionButton("Reset").apply { setOnClickListener { resetTimer() } }
        actions.addView(startButton, LinearLayout.LayoutParams(0, dp(54), 1.7f).apply { marginEnd = dp(6) })
        actions.addView(resetButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        timerCard.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        rootView.addView(text("Theme plugins", 18f, Typeface.BOLD), verticalParams(top = 28))
        rootView.addView(text("Each option is an isolated APK loaded by AndroidModuleLoader", 12f).apply {
            tag = TAG_SECTION_SUBTITLE
        }, verticalParams(top = 4))

        val themes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        PluginCatalog.plugins.forEach { spec ->
            val symbol = text(spec.symbol, 25f, Typeface.BOLD).apply { gravity = Gravity.CENTER }
            val title = text(spec.title, 13f, Typeface.BOLD).apply { gravity = Gravity.CENTER }
            val caption = text(spec.caption.substringBefore(" · "), 11f).apply { gravity = Gravity.CENTER }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(12), dp(5), dp(10))
                isClickable = true
                isFocusable = true
                contentDescription = "Apply the ${spec.title} plugin"
                setOnClickListener { uiScope.launch { activatePlugin(spec) } }
                addView(symbol)
                addView(title, verticalParams(top = 5))
                addView(caption, verticalParams(top = 2))
            }
            themeCards[spec.id] = ThemeCardViews(card, symbol, title, caption)
            themes.addView(card, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        rootView.addView(themes, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })

        detailButton = actionButton("Open plugin resource page  ↗").apply {
            setOnClickListener {
                val selected = activePlugin ?: return@setOnClickListener
                runCatching {
                    checkNotNull(components).startActivity(
                        this@MainActivity,
                        selected.id,
                        selected.activityClass,
                    )
                }.onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
            }
        }
        rootView.addView(detailButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(14)
        })
    }

    private fun toggleTimer() {
        if (running) {
            remainingMs = (endRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            running = false
            timerHandler.removeCallbacks(ticker)
            startButton.text = "Resume focus"
            statusText.text = "Paused · Take your time"
        } else {
            if (remainingMs == 0L) remainingMs = durationMs
            endRealtime = SystemClock.elapsedRealtime() + remainingMs
            running = true
            startButton.text = "Pause"
            statusText.text = "Focus in progress"
            timerHandler.post(ticker)
        }
    }

    private fun resetTimer() {
        running = false
        timerHandler.removeCallbacks(ticker)
        remainingMs = durationMs
        startButton.text = "Start focus"
        statusText.text = "Ready · Pick a duration"
        renderTime()
    }

    private fun selectDuration(minutes: Int) {
        durationMs = minutes * 60_000L
        remainingMs = durationMs
        running = false
        timerHandler.removeCallbacks(ticker)
        startButton.text = "Start focus"
        statusText.text = "Set to $minutes minutes"
        renderTime()
        renderPresetSelection()
    }

    private fun renderTime() {
        val totalSeconds = (remainingMs + 999L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        timeText.text = String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        dial.progress = if (durationMs == 0L) 0f else remainingMs.toFloat() / durationMs.toFloat()
    }

    @Suppress("DEPRECATION")
    private fun renderTheme(theme: TimerTheme) {
        rootView.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(theme.backgroundTop, theme.backgroundBottom),
        )
        timerCard.background = gradient(theme.surfaceStrong, theme.surface, 28f)
        brandSymbol.text = theme.symbol
        brandSymbol.setTextColor(theme.accent)
        themeName.text = theme.name
        themeName.setTextColor(theme.primaryText)
        themeTagline.text = theme.tagline
        themeTagline.setTextColor(theme.secondaryText)
        statusText.setTextColor(theme.accent)
        timeText.setTextColor(theme.primaryText)
        dial.progressColor = theme.accent
        dial.trackColor = withAlpha(theme.primaryText, 0x20)
        startButton.background = rounded(theme.accent, 17f)
        startButton.setTextColor(theme.buttonText)
        resetButton.background = rounded(theme.accentSoft, 17f)
        resetButton.setTextColor(theme.primaryText)
        detailButton.background = rounded(theme.accentSoft, 17f, theme.accent, 1)
        detailButton.setTextColor(theme.primaryText)
        walkTextViews(rootView) { view ->
            when (view.tag) {
                TAG_MUTED_TEXT, TAG_SECTION_SUBTITLE -> view.setTextColor(theme.secondaryText)
                else -> if (view !== brandSymbol && view !== themeName && view !== themeTagline &&
                    view !== statusText && view !== timeText && view !in presetButtons &&
                    view !== startButton && view !== resetButton && view !== detailButton &&
                    themeCards.values.none { it.contains(view) }
                ) view.setTextColor(theme.primaryText)
            }
        }
        window.statusBarColor = theme.backgroundTop
        window.navigationBarColor = theme.backgroundBottom
        window.decorView.systemUiVisibility = if (theme.isLightStatusBar) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        renderPresetSelection()
        renderPluginSelection()
    }

    private fun renderPresetSelection() {
        val selectedMinutes = durationMs / 60_000L
        val values = listOf(1L, 5L, 25L)
        presetButtons.forEachIndexed { index, button ->
            val selected = selectedMinutes == values[index]
            button.background = rounded(
                if (selected) currentTheme.accent else withAlpha(currentTheme.primaryText, 0x0E),
                14f,
            )
            button.setTextColor(if (selected) currentTheme.buttonText else currentTheme.secondaryText)
        }
    }

    private fun renderPluginSelection() {
        themeCards.forEach { (id, views) ->
            val spec = PluginCatalog.plugins.first { it.id == id }
            val selected = activePlugin?.id == id
            views.root.background = rounded(
                if (selected) withAlpha(spec.previewColor, 0x42) else withAlpha(currentTheme.primaryText, 0x0B),
                18f,
                if (selected) spec.previewColor else withAlpha(currentTheme.primaryText, 0x18),
                if (selected) 2 else 1,
            )
            views.symbol.setTextColor(spec.previewColor)
            views.title.setTextColor(currentTheme.primaryText)
            views.caption.setTextColor(currentTheme.secondaryText)
            views.root.alpha = if (views.root.isEnabled) 1f else 0.55f
        }
    }

    private fun setPluginControlsEnabled(enabled: Boolean) {
        themeCards.values.forEach { it.root.isEnabled = enabled }
        if (::detailButton.isInitialized) detailButton.isEnabled = enabled && activePlugin != null
        if (::rootView.isInitialized) renderPluginSelection()
    }

    private fun text(value: String, sizeSp: Float, style: Int = Typeface.NORMAL) = TextView(this).apply {
        text = value
        textSize = sizeSp
        typeface = Typeface.create("sans-serif", style)
        includeFontPadding = false
    }

    private fun actionButton(value: String) = text(value, 14f, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
    }

    private fun verticalParams(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp)
            if (strokeColor != null && strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun gradient(top: Int, bottom: Int, radiusDp: Float) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(top, bottom),
    ).apply { cornerRadius = dp(radiusDp) }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun walkTextViews(group: ViewGroup, action: (TextView) -> Unit) {
        repeat(group.childCount) { index ->
            when (val child = group.getChildAt(index)) {
                is TextView -> action(child)
                is ViewGroup -> walkTextViews(child, action)
            }
        }
    }

    private fun onUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else runOnUiThread(action)
    }

    private data class ThemeCardViews(
        val root: LinearLayout,
        val symbol: TextView,
        val title: TextView,
        val caption: TextView,
    ) {
        fun contains(view: TextView): Boolean = view === symbol || view === title || view === caption
    }

    companion object {
        private const val ACTIVE_THEME_ENTRY = "active-theme"
        private const val KEY_DURATION = "duration"
        private const val KEY_REMAINING = "remaining"
        private const val KEY_END_REALTIME = "end-realtime"
        private const val KEY_RUNNING = "running"
        private const val KEY_PLUGIN_ID = "plugin-id"
        private const val TAG_MUTED_TEXT = "muted-text"
        private const val TAG_SECTION_SUBTITLE = "section-subtitle"
        private const val TAG = "CordisTimerDemo"

        private val DEFAULT_THEME = TimerTheme(
            id = "default",
            name = "Cordis Timer",
            tagline = "Loading theme plugins",
            symbol = "✦",
            backgroundTop = 0xFF102820.toInt(),
            backgroundBottom = 0xFF07120F.toInt(),
            surface = 0xFF10231D.toInt(),
            surfaceStrong = 0xFF17372D.toInt(),
            primaryText = 0xFFF2FFF9.toInt(),
            secondaryText = 0xFFA7C4B8.toInt(),
            accent = 0xFF78E6BD.toInt(),
            accentSoft = 0xFF24483B.toInt(),
            buttonText = 0xFF092019.toInt(),
            isLightStatusBar = false,
        )
    }
}
