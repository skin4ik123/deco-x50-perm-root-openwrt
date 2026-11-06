package com.internetdeco.decocontrol

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Disconnection alone never establishes that root is missing. */
internal enum class RootAccess { NOT_CHECKED, SAVED_LOGIN, CONNECTED, CONFIGURED }

internal fun Node.rootAccess(): RootAccess {
    val latest = metrics?.takeIf { it.has("password_ssh") && !it.isNull("password_ssh") } ?: identity
    return when {
        latest?.optBoolean("password_ssh") == true -> RootAccess.CONFIGURED
        online && (identity?.optInt("uid", -1) == 0 || metrics?.optInt("uid", -1) == 0) -> RootAccess.CONNECTED
        passwordSaved -> RootAccess.SAVED_LOGIN
        else -> RootAccess.NOT_CHECKED
    }
}

@Composable internal fun RootSetupCard(node: Node, busy: Boolean, setup: () -> Unit, signIn: () -> Unit) {
    Panel {
        Text("START HERE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(node.host, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Set up root access", style = MaterialTheme.typography.titleLarge)
        Text("Unlock monitoring, terminal and files with permanent root.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Deco X50 V1 (EU) · stock firmware", style = MaterialTheme.typography.labelMedium)
        Button(onClick = setup, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Set up root") }
        Text("Use your TP-Link owner account. Compatibility and backups are checked before installation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        TextButton(onClick = signIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Already have root? Sign in") }
    }
}
