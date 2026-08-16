package org.cordis.demo.api

import org.cordis.ServiceKey

data class TimerTheme(
    val id: String,
    val name: String,
    val tagline: String,
    val symbol: String,
    val backgroundTop: Int,
    val backgroundBottom: Int,
    val surface: Int,
    val surfaceStrong: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val accentSoft: Int,
    val buttonText: Int,
    val isLightStatusBar: Boolean,
)

interface TimerThemeSink {
    fun applyTheme(theme: TimerTheme)
    fun clearTheme(themeId: String)

    companion object {
        val Key = ServiceKey<TimerThemeSink>("demo/timer-theme-sink")
    }
}
