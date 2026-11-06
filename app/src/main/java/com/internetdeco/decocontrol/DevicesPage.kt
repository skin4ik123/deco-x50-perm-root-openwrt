package com.internetdeco.decocontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable internal fun NodesPage(s: ScreenState, add: () -> Unit, connect: () -> Unit, setupRoot: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column { Text("Your Decos", style = MaterialTheme.typography.titleLarge); Text("Independent connections. Switch instantly.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (s.nodes.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = add) { Text("+ Add device") }
                OutlinedButton(enabled = s.loading.none { it.endsWith(":discover") }, onClick = { Controller.discover() }) { Text(if (s.loading.any { it.endsWith(":discover") }) "Checking…" else "Check availability") }
            }
        }
        if (s.nodes.isEmpty()) item { Panel { Text("Set up your first Deco", style = MaterialTheme.typography.titleLarge); Text("Add your Deco to set up permanent root or sign in to existing SSH."); Button(onClick = add, modifier = Modifier.fillMaxWidth()) { Text("+ Add Deco") } } }
        items(s.nodes.sortedByDescending { it.favorite }, key = { it.host }) { n ->
            val connection = s.connection(n.host)
            val ram = RamState(result(s, n.host, "ram_status") as? JSONObject)
            Surface(onClick = { Controller.select(n.host); Controller.tab(1) }, shape = RoundedCornerShape(22.dp), color = if (n.host == s.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(15.dp)) { Box(Modifier.padding(13.dp)) { IconGlyph("nodes") } }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) { Text(n.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(n.host, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { Controller.favorite(n.host) }) { Text(if (n.favorite) "★" else "☆", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary) }
                    }
                    ConnectionIndicator(connection, passwordSaved = n.passwordSaved, cancel = { Controller.cancelConnection(n.host) })
                    if (ram.pending) RamEntryCard(n, ram) { Controller.select(n.host); Controller.page("openwrt") }
                    when (n.rootAccess()) {
                        RootAccess.CONFIGURED -> Text("Permanent SSH configured", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        RootAccess.NOT_CHECKED -> Text("Root access not checked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Unit
                    }
                    if (!connection.busy && !n.online && n.reachable == true) Text("Device reachable · SSH sign-in required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    s.errors["${n.host}:connect"]?.let { ConnectionProblem(it) }
                    if (!connection.busy && n.rootAccess() == RootAccess.NOT_CHECKED) {
                        Button(onClick = { Controller.select(n.host); setupRoot() }, enabled = s.operation?.running != true && !ram.pending, modifier = Modifier.fillMaxWidth()) { Text("Set up root") }
                        TextButton(onClick = { Controller.select(n.host); connect() }, modifier = Modifier.fillMaxWidth()) { Text("Already have root? Sign in") }
                    } else if (!connection.busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (n.online) TextButton(onClick = { Controller.select(n.host); Controller.tab(3) }) { Text("Terminal") }
                        if (n.passwordSaved && !n.online) TextButton(onClick = { Controller.select(n.host); connect() }) { Text("Sign in") }
                        FilledTonalButton(onClick = {
                            Controller.select(n.host)
                            if (n.passwordSaved && !n.online) Controller.connect(n.host) else if (n.online) Controller.tab(1) else connect()
                        }) { Text(if (n.online) "Open device" else if (n.passwordSaved) "Reconnect" else "Connect") }
                    }
                    if (!ram.pending && !connection.busy && n.rootAccess() in setOf(RootAccess.CONNECTED, RootAccess.SAVED_LOGIN)) OutlinedButton(enabled = s.operation?.running != true, modifier = Modifier.fillMaxWidth(), onClick = { Controller.select(n.host); setupRoot() }) { Text(if (n.rootAccess() == RootAccess.CONNECTED) "Make root permanent" else "Set up root") }
                }
            }
        }
    }
}
