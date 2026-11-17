package com.varizpardaz.listener.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.varizpardaz.listener.app.R
import com.varizpardaz.listener.app.guide.GuideStep
import com.varizpardaz.listener.app.guide.Maker
import com.varizpardaz.listener.app.guide.MakerGuide
import com.varizpardaz.listener.app.guide.MakerGuides
import com.varizpardaz.listener.app.guide.Opened
import com.varizpardaz.listener.app.guide.SettingsLauncher
import com.varizpardaz.listener.app.guide.StepAction
import com.varizpardaz.listener.app.status.SystemFacts

/**
 * How to keep the app alive on a phone whose maker adds its own background limits. The phone's maker is picked first, but every
 * maker's steps can be opened (a phone's label does not always match its software). Nothing here changes any setting itself:
 * the buttons only open the right system screen, and the maker's own settings are the user's to switch.
 */
@Composable
fun BatteryTab(detected: Maker, facts: SystemFacts, onConfirmChanged: (Boolean) -> Unit) {
    val guides = remember(detected) { MakerGuides.ordered(detected) }
    var selected by rememberSaveable { mutableStateOf(detected.name) }
    val guide = guides.firstOrNull { it.maker.name == selected } ?: guides.first()

    TabColumn {
        SectionCard(background = StatusColors.WarnBackground) {
            Text(stringResource(R.string.battery_warning_title), style = MaterialTheme.typography.titleSmall, color = StatusColors.Warn)
            Text(stringResource(R.string.battery_warning_body), style = MaterialTheme.typography.bodyMedium)
        }
        ExemptionCard(exempt = facts.batteryExempt)

        Text(stringResource(R.string.battery_pick_maker), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            guides.forEach { g ->
                FilterChip(
                    selected = g.maker == guide.maker,
                    onClick = { selected = g.maker.name },
                    label = { Text(stringResource(g.chipLabel)) },
                )
            }
        }
        GuideCard(
            guide = guide,
            detected = guide.maker == detected && detected != Maker.OTHER,
            // The confirmation is about this phone's own maker; it makes no sense on another maker's page.
            confirmed = facts.makerStepsConfirmed.takeIf { guide.maker == detected },
            onConfirmChanged = onConfirmChanged,
        )
    }
}

@Composable
private fun ExemptionCard(exempt: Boolean) {
    val context = LocalContext.current
    var fallback by remember { mutableStateOf(false) }
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.battery_exempt_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (exempt) R.string.battery_exempt_on else R.string.battery_exempt_off),
                color = if (exempt) StatusColors.Good else StatusColors.Warn,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(stringResource(R.string.ck_battery_why), style = MaterialTheme.typography.bodyMedium)
        if (!exempt) {
            Button(
                onClick = { fallback = SettingsLauncher.requestIgnoreBatteryOptimizations(context) == Opened.FALLBACK },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.battery_exempt_button)) }
            if (fallback) Text(stringResource(R.string.battery_exempt_fallback), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GuideCard(guide: MakerGuide, detected: Boolean, confirmed: Boolean?, onConfirmChanged: (Boolean) -> Unit) {
    val numbers = rememberPersianNumbers()
    SectionCard {
        Text(stringResource(guide.title), style = MaterialTheme.typography.titleMedium)
        if (detected) Text(stringResource(R.string.battery_detected), style = MaterialTheme.typography.labelMedium, color = StatusColors.Good)
        guide.steps.forEachIndexed { index, step -> StepRow(numbers.format(index + 1), step) }
        Text(stringResource(R.string.battery_names_vary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (confirmed != null) {
            if (confirmed) {
                Text(stringResource(R.string.battery_done_state), color = StatusColors.Good, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.battery_done_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onConfirmChanged(false) }) { Text(stringResource(R.string.battery_done_undo)) }
            } else {
                Button(onClick = { onConfirmChanged(true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.battery_done_button)) }
            }
        }
    }
}

@Composable
private fun StepRow(number: String, step: GuideStep) {
    val context = LocalContext.current
    var fellBack by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(step.text), style = MaterialTheme.typography.bodyMedium)
            val action = step.action
            if (action != StepAction.None) {
                OutlinedButton(
                    onClick = {
                        fellBack = when (action) {
                            is StepAction.Maker -> SettingsLauncher.openMakerScreen(context, action.components) == Opened.FALLBACK
                            StepAction.AppSettings -> { SettingsLauncher.openAppSettings(context); false }
                            StepAction.None -> false
                        }
                    },
                ) { Text(stringResource(R.string.battery_open_screen)) }
                if (fellBack) Text(stringResource(R.string.battery_opened_fallback), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
