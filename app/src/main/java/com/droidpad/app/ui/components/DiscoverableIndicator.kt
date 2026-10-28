package com.droidpad.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droidpad.app.R
import com.droidpad.app.hid.HidConnectionManager
import kotlinx.coroutines.delay
import java.util.Locale

/** Milliseconds left until [until] (an elapsedRealtime timestamp), ticking while visible; null when not discoverable. */
@Composable
fun rememberDiscoverableRemaining(until: Long?): Long? {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(until) {
        while (until != null) {
            now = SystemClock.elapsedRealtime()
            if (now >= until) break
            delay(250)
        }
    }
    return until?.let { (it - now).coerceAtLeast(0) }?.takeIf { it > 0 }
}

fun formatRemaining(ms: Long): String {
    val s = (ms + 999) / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** "Visible to nearby devices · 1:42 left" with a draining progress bar. */
@Composable
fun DiscoverableIndicator(remainingMs: Long, modifier: Modifier = Modifier) {
    val total = HidConnectionManager.DEFAULT_DISCOVERABLE_SECONDS * 1000f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.discoverable_active, formatRemaining(remainingMs)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { (remainingMs / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
