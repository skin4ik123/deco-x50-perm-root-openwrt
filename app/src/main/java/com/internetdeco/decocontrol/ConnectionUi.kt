package com.internetdeco.decocontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** Top anchored forms share one IME inset owner and a scrollable body. */
@Composable internal fun FormDialog(
    title: String,
    close: () -> Unit,
    height: Dp = 540.dp,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().height(minOf(maxHeight, height)),
                shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp,
            ) {
                Column {
                    Text(title, Modifier.padding(22.dp, 20.dp, 22.dp, 12.dp), style = MaterialTheme.typography.titleLarge)
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
                    )
                    HorizontalDivider(Modifier.padding(top = 10.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions,
                    )
                }
            }
        }
    }
}

internal fun ScreenState.connection(host: String): ConnectionState {
    val active = connections.values.filter { it.profileKey == host && it.port in setOf(22, 20003) }.maxByOrNull { it.startedAt }
    val online = nodes.any { it.host == host && it.online }
    if (online && active?.phase in setOf(ConnectionPhase.FAILED, ConnectionPhase.CANCELLED)) return active!!.copy(phase = ConnectionPhase.CONNECTED)
    return active ?: ConnectionState(host, phase = if (online) ConnectionPhase.CONNECTED else ConnectionPhase.DISCONNECTED)
}

@Composable internal fun ConnectionIndicator(
    connection: ConnectionState,
    modifier: Modifier = Modifier,
    passwordSaved: Boolean = false,
    compact: Boolean = false,
    cancel: (() -> Unit)? = null,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connection.busy, connection.startedAt) {
        while (connection.busy) { now = System.currentTimeMillis(); delay(1_000) }
    }
    val color = when {
        connection.connected -> MaterialTheme.colorScheme.primary
        connection.phase == ConnectionPhase.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (connection.busy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
        else Text(if (connection.connected) "●" else "○", color = color)
        Column(Modifier.weight(1f)) {
            Text(connection.label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (connection.busy) {
                val seconds = ((now - connection.startedAt).coerceAtLeast(0) / 1_000)
                val retry = if (connection.phase == ConnectionPhase.RETRY_WAIT) " · next in ${((connection.retryAt - now).coerceAtLeast(0) + 999) / 1_000}s" else ""
                Text("Attempt ${connection.attempt.coerceAtLeast(1)} of ${connection.attempts} · ${seconds}s$retry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!compact) Text(if (passwordSaved) "Password saved on this phone" else "Direct SSH · ${connection.host}:${connection.port}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (connection.busy && cancel != null) TextButton(onClick = cancel) { Text("Cancel") }
    }
}

@Composable internal fun ConnectionProblem(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable internal fun PasswordField(
    value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
    )
}
