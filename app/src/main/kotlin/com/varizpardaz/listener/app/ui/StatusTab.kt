package com.varizpardaz.listener.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.varizpardaz.listener.app.R
import com.varizpardaz.listener.app.data.AppContainer
import com.varizpardaz.listener.app.status.Ages
import com.varizpardaz.listener.app.status.Checklist
import com.varizpardaz.listener.app.status.ChecklistId
import com.varizpardaz.listener.app.status.ChecklistItem
import com.varizpardaz.listener.app.status.Health
import com.varizpardaz.listener.app.status.Link
import com.varizpardaz.listener.app.status.Overall
import com.varizpardaz.listener.app.status.SystemFacts
import com.varizpardaz.listener.core.CardInfo
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.update.UpdateState

/** One reading of the numbers on this tab, with the time it was taken so "how long ago" does not depend on when it is drawn. */
private class Reading(val status: AppContainer.Status, val nowMillis: Long)

/** The main screen: is the phone doing its job, what it serves, what the counters say, and what Android still needs from the user. */
@Composable
fun StatusTab(container: AppContainer, credentials: DeviceCredentials, facts: SystemFacts, actions: ChecklistActions) {
    val reading by rememberPolled { Reading(container.status(credentials.serverPublicKey), System.currentTimeMillis()) }
    val current = reading
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val status = current.status
    val items = Checklist.build(facts)
    val link = Link.state(status.lastHeartbeatMillis, current.nowMillis, container.processStartedMillis)
    val health = Overall.of(link, items)

    TabColumn {
        (status.update as? UpdateState.Available)?.let { UpdateAvailableCard(container.updates, it.offer) }
        HealthCard(health, ageText(Ages.of(status.lastHeartbeatMillis, current.nowMillis)))
        status.merchantName?.let { MerchantCard(it) }
        CardsCard(status.cards)
        StatsCard(status, current.nowMillis)
        ChecklistCard(items, actions)
        PrivacyCard()
    }
}

@Composable
private fun HealthCard(health: Health, lastContact: String) {
    val (color, title) = when (health) {
        Health.ACTIVE -> StatusColors.Good to R.string.health_active
        Health.NEEDS_ACTION -> StatusColors.Warn to R.string.health_needs_action
        Health.CONNECTING -> StatusColors.Neutral to R.string.health_connecting
        Health.DOWN -> MaterialTheme.colorScheme.error to R.string.health_down
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = color)
        }
        StatRow(stringResource(R.string.status_last_contact), lastContact)
        if (health == Health.DOWN) Text(stringResource(R.string.health_down_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MerchantCard(name: String) {
    SectionCard {
        Text(stringResource(R.string.status_merchant_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CardsCard(cards: List<CardInfo>) {
    val numbers = rememberPersianNumbers()
    SectionCard {
        Text(stringResource(R.string.status_cards_title), style = MaterialTheme.typography.titleMedium)
        if (cards.isEmpty()) {
            Text(stringResource(R.string.status_cards_empty), style = MaterialTheme.typography.bodyMedium)
        }
        cards.forEach { card ->
            Column {
                // The server sends the number already masked ("6104 **** **** 1234"); it is shown exactly as received.
                LatinText(card.label, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        card.simSlot?.let { stringResource(R.string.status_card_sim, numbers.format(it + 1)) }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LatinText(card.bank, color = MaterialTheme.colorScheme.onSurfaceVariant, fillWidth = false)
                }
            }
        }
    }
}

/** Counts and times only: how many items wait to be sent and how many texts were set aside. Never any message content. */
@Composable
private fun StatsCard(status: AppContainer.Status, nowMillis: Long) {
    val numbers = rememberPersianNumbers()
    fun count(name: CounterName) = numbers.format(status.counters[name] ?: 0L)
    SectionCard {
        Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleMedium)
        StatRow(stringResource(R.string.stats_queue), numbers.format(status.queueDepth))
        StatRow(stringResource(R.string.stats_last_message), ageText(Ages.of(status.lastMessageAtMillis, nowMillis)))
        StatRow(stringResource(R.string.stats_uploaded), count(CounterName.UPLOADED))
        StatRow(stringResource(R.string.stats_rejected), count(CounterName.REJECTED))
        StatRow(stringResource(R.string.stats_dropped_sender), count(CounterName.DROPPED_SENDER))
        StatRow(stringResource(R.string.stats_dropped_otp), count(CounterName.DROPPED_OTP))
        StatRow(stringResource(R.string.stats_dropped_unclassified), count(CounterName.DROPPED_UNCLASSIFIED))
        Text(stringResource(R.string.stats_since_pairing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChecklistCard(items: List<ChecklistItem>, actions: ChecklistActions) {
    SectionCard {
        Text(stringResource(R.string.checklist_title), style = MaterialTheme.typography.titleMedium)
        items.forEach { ChecklistRow(it, actions) }
        if (items.none { it.required && !it.done }) {
            Text(stringResource(R.string.checklist_all_done), style = MaterialTheme.typography.bodySmall, color = StatusColors.Good)
        }
    }
}

@Composable
private fun ChecklistRow(item: ChecklistItem, actions: ChecklistActions) {
    val (title, why) = item.id.texts()
    val pending = !item.done
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (pending && item.required) StatusColors.WarnBackground else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(if (pending && item.required) 8.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                if (!item.required) {
                    Text(stringResource(R.string.checklist_recommended), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                item.done -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(R.drawable.ic_nav_status), contentDescription = null, tint = StatusColors.Good, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.checklist_done), style = MaterialTheme.typography.bodyMedium, color = StatusColors.Good)
                }
                else -> {
                    val label = stringResource(if (item.id == ChecklistId.MAKER_STEPS) R.string.checklist_open_guide else R.string.checklist_fix)
                    if (item.required) Button(onClick = { actions.fix(item.id) }) { Text(label) } else OutlinedButton(onClick = { actions.fix(item.id) }) { Text(label) }
                }
            }
        }
        if (pending) {
            Text(stringResource(why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // A sideloaded app on Android 13+ cannot be given SMS access until "restricted settings" are allowed for it.
            if (actions.permissionRefused && Build.VERSION.SDK_INT >= 33 && (item.id == ChecklistId.RECEIVE_SMS || item.id == ChecklistId.READ_SMS)) {
                Text(stringResource(R.string.sms_permission_hint_restricted), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun ChecklistId.texts(): Pair<Int, Int> = when (this) {
    ChecklistId.RECEIVE_SMS -> R.string.ck_receive_sms_title to R.string.ck_receive_sms_why
    ChecklistId.READ_SMS -> R.string.ck_read_sms_title to R.string.ck_read_sms_why
    ChecklistId.POST_NOTIFICATIONS -> R.string.ck_post_notifications_title to R.string.ck_post_notifications_why
    ChecklistId.BATTERY -> R.string.ck_battery_title to R.string.ck_battery_why
    ChecklistId.BACKGROUND_RESTRICTION -> R.string.ck_restricted_title to R.string.ck_restricted_why
    ChecklistId.UNUSED_APP_PAUSE -> R.string.ck_unused_title to R.string.ck_unused_why
    ChecklistId.MAKER_STEPS -> R.string.ck_maker_title to R.string.ck_maker_why
    ChecklistId.NOTIFICATION_ACCESS -> R.string.ck_notif_access_title to R.string.ck_notif_access_why
}

@Composable
private fun PrivacyCard() {
    SectionCard(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium)
    }
}
