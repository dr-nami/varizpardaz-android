package com.varizpardaz.listener.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.varizpardaz.listener.app.R
import com.varizpardaz.listener.app.status.Age
import com.varizpardaz.listener.app.status.AgeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/** Status colours that are not part of the Material scheme: green for "working", amber for "needs a look". Always paired with words. */
object StatusColors {
    val Good = Color(0xFF15803D)
    val Warn = Color(0xFFB45309)
    val WarnBackground = Color(0xFFFFFBEB)
    val Neutral = Color(0xFF64748B)
}

/** The scrolling body of one tab. The scaffold already accounts for the system bars and the bottom navigation. */
@Composable
fun TabColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Hosts, ids and card numbers are Latin text: keep them left-to-right and left-aligned inside the right-to-left screen. */
@Composable
fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LatinText(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun LatinText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    fillWidth: Boolean = true,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(value, style = style, color = color, textAlign = TextAlign.Start, modifier = if (fillWidth) modifier.fillMaxWidth() else modifier)
    }
}

@Composable
fun rememberPersianNumbers(): NumberFormat = remember { NumberFormat.getIntegerInstance(Locale.forLanguageTag("fa")) }

@Composable
fun ageText(age: Age): String {
    val numbers = rememberPersianNumbers()
    return when (age.unit) {
        AgeUnit.NEVER -> stringResource(R.string.age_never)
        AgeUnit.JUST_NOW -> stringResource(R.string.age_just_now)
        AgeUnit.SECONDS -> stringResource(R.string.age_seconds, numbers.format(age.count))
        AgeUnit.MINUTES -> stringResource(R.string.age_minutes, numbers.format(age.count))
        AgeUnit.HOURS -> stringResource(R.string.age_hours, numbers.format(age.count))
        AgeUnit.DAYS -> stringResource(R.string.age_days, numbers.format(age.count))
    }
}

/**
 * Re-runs [load] every [periodMillis] on a background thread, but only while the app is on screen: a visible screen needs fresh
 * numbers, a backgrounded one must not keep waking the database. Keeps the last good value if one load fails.
 */
@Composable
fun <T : Any> rememberPolled(periodMillis: Long = 2_000, load: suspend () -> T): State<T?> {
    val state = remember { mutableStateOf<T?>(null) }
    val currentLoad = rememberUpdatedState(load)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { withContext(Dispatchers.IO) { currentLoad.value() } }.getOrNull()?.let { state.value = it }
                delay(periodMillis)
            }
        }
    }
    return state
}

/** The hosting activity behind a Compose context (the context is often a wrapper around it). */
fun Context.findActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
