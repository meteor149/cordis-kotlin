package org.cordis.demo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.cordis.demo.AndroidPluginLabRuntime
import org.cordis.demo.PluginLabApp

class MainActivity : ComponentActivity() {
    lateinit var pluginRuntime: AndroidPluginLabRuntime
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginRuntime = AndroidPluginLabRuntime(this)
        setContent { PluginLabApp(pluginRuntime) }
    }
}
