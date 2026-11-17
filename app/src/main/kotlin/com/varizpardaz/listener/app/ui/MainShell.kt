package com.varizpardaz.listener.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.varizpardaz.listener.app.R
import com.varizpardaz.listener.app.data.AppContainer
import com.varizpardaz.listener.core.pairing.DeviceCredentials

private enum class Tab(val label: Int, val icon: Int) {
    STATUS(R.string.nav_status, R.drawable.ic_nav_status),
    BATTERY(R.string.nav_battery, R.drawable.ic_nav_battery),
    SETTINGS(R.string.nav_settings, R.drawable.ic_nav_settings),
}

/** The paired app: three tabs (status, battery guide, settings) and nothing else. There is deliberately no message list. */
@Composable
fun MainShell(container: AppContainer, credentials: DeviceCredentials, onUnpair: suspend () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(Tab.STATUS.ordinal) }

    // What Android currently says about permissions and battery settings. Re-read whenever the app comes back to the front
    // (the user may have been in system settings) and whenever the tab changes.
    var facts by remember { mutableStateOf(container.systemFacts()) }
    val refresh = { facts = container.systemFacts() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(tab) { refresh() }

    val actions = rememberChecklistActions(onChanged = refresh, openBatteryGuide = { tab = Tab.BATTERY.ordinal })

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry.ordinal,
                        onClick = { tab = entry.ordinal },
                        icon = { Icon(painterResource(entry.icon), contentDescription = null) },
                        label = { Text(stringResource(entry.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (Tab.entries[tab]) {
                Tab.STATUS -> StatusTab(container, credentials, facts, actions)
                Tab.BATTERY -> BatteryTab(
                    detected = container.detectedMaker,
                    facts = facts,
                    onConfirmChanged = {
                        container.setMakerStepsConfirmed(it)
                        refresh()
                    },
                )
                Tab.SETTINGS -> SettingsTab(container, credentials, onUnpair)
            }
        }
    }
}
