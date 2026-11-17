package com.varizpardaz.listener.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.varizpardaz.listener.app.data.PairingState
import com.varizpardaz.listener.app.monitor.MonitorService
import com.varizpardaz.listener.app.ui.AppRoot
import com.varizpardaz.listener.app.ui.theme.ListenerTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        // A visible app may start a foreground service; this covers a service that was stopped or killed meanwhile.
        val container = (application as ListenerApp).container
        if (container.pairing.state.value is PairingState.Paired) MonitorService.ensureRunning(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ListenerApp).container
        setContent {
            ListenerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(container)
                }
            }
        }
    }
}
