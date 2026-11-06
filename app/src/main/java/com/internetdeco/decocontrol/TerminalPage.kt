package com.internetdeco.decocontrol

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun TerminalPage(host: String, online: Boolean, displayHost: String = host, connectionHost: String = host, connect: () -> Unit) {
    if (host.isBlank()) { Empty("Select a Deco", "The terminal opens for the selected device"); return }
    val context = LocalContext.current
    val s by Controller.ui.collectAsStateWithLifecycle()
    val terminal = remember(host) { TerminalHub.get(context, host) }
    val status by terminal.ui.collectAsStateWithLifecycle()
    val connection = s.connection(connectionHost)
    val saved = s.nodes.firstOrNull { it.host == host }?.passwordSaved == true
    var view by remember(host) { mutableStateOf<RemoteTerminalView?>(null) }
    var menu by remember { mutableStateOf(false) }
    var selectedText by remember(host) { mutableStateOf<String?>(null) }
    val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(s.terminalFullscreen && !ime) { Controller.terminalFocus(false) }
    LaunchedEffect(host, online, connection.connected) { if ((online || connection.connected) && Controller.bridge.connected(host)) terminal.start() }
    val open = {
        if (Controller.bridge.connected(host)) terminal.start()
        else if (saved) Controller.connect(host)
        else connect()
    }
    Column(Modifier.fillMaxSize().background(Color(0xff0a0f16))) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("root@$displayHost", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text(when { status.open -> "Terminal connected"; status.connecting -> "Opening terminal…"; else -> "Terminal closed" }, style = MaterialTheme.typography.labelSmall, color = if (status.open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { view?.keyboard(!ime) }) { Text("⌨", fontSize = 22.sp) }
                    IconButton(onClick = { Controller.terminalFocus(!s.terminalFullscreen) }) { Text(if (s.terminalFullscreen) "↙" else "⛶", fontSize = 22.sp) }
                    Box {
                        IconButton(onClick = { menu = true }) { Text("⋮", fontSize = 26.sp) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Select and copy text") }, onClick = { selectedText = terminal.transcript(); menu = false })
                            DropdownMenuItem(text = { Text("Copy transcript") }, onClick = { terminal.copy(); menu = false })
                            DropdownMenuItem(text = { Text("Jump to live output") }, onClick = { view?.scrollToBottom(); menu = false })
                            DropdownMenuItem(text = { Text("Smaller text") }, onClick = { terminal.setFont(status.fontSp - 1f); menu = false })
                            DropdownMenuItem(text = { Text("Larger text") }, onClick = { terminal.setFont(status.fontSp + 1f); menu = false })
                            DropdownMenuItem(text = { Text(if (host.startsWith("ram:")) "Reconnect OpenWrt" else "Change SSH login") }, onClick = { menu = false; connect() })
                            DropdownMenuItem(text = { Text("Close terminal session") }, enabled = status.open || status.connecting, onClick = { terminal.stop(); if (connection.busy) Controller.cancelConnection(host); menu = false })
                        }
                    }
                }
                if (connection.busy) ConnectionIndicator(connection, compact = true, cancel = { terminal.stop(); Controller.cancelConnection(host) })
                else if (!status.open) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (host.startsWith("ram:")) "OpenWrt RAM session" else if (saved) "Saved password ready" else "Sign in to open a shell", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = !status.connecting && "$host:connect" !in s.loading, onClick = open) { Text(if (online) "Open terminal" else "Reconnect") }
                        if (!status.connecting) TextButton(onClick = connect) { Text(if (host.startsWith("ram:")) "Verify OpenWrt" else "Sign in") }
                        else TextButton(onClick = { terminal.stop(); Controller.cancelConnection(host) }) { Text("Cancel") }
                    }
                    (status.error ?: s.errors["$host:connect"])?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        key(host) {
            AndroidView(
                factory = { RemoteTerminalView(it, terminal).also { v -> view = v; v.onSelectText = { selectedText = terminal.transcript() } } },
                modifier = Modifier.fillMaxWidth().weight(1f), update = { it.invalidate() },
            )
        }
        LazyRow(
            Modifier.fillMaxWidth().background(Color(0xff14212e)), contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(listOf("ESC", "TAB", "CTRL", "↑", "↓", "←", "→", "Paste", "Home", "End", "PgUp", "PgDn")) { label ->
                TextButton(
                    onClick = {
                        when (label) {
                            "CTRL" -> terminal.toggleCtrl()
                            "Paste" -> terminal.paste()
                            else -> terminal.key(when (label) { "ESC" -> KeyEvent.KEYCODE_ESCAPE; "TAB" -> KeyEvent.KEYCODE_TAB; "↑" -> KeyEvent.KEYCODE_DPAD_UP; "↓" -> KeyEvent.KEYCODE_DPAD_DOWN; "←" -> KeyEvent.KEYCODE_DPAD_LEFT; "→" -> KeyEvent.KEYCODE_DPAD_RIGHT; "Home" -> KeyEvent.KEYCODE_MOVE_HOME; "End" -> KeyEvent.KEYCODE_MOVE_END; "PgUp" -> KeyEvent.KEYCODE_PAGE_UP; else -> KeyEvent.KEYCODE_PAGE_DOWN })
                        }
                    }, enabled = status.open, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) { Text(label, color = if (label == "CTRL" && status.ctrl) Color(0xff69e3c2) else Color(0xffbacbdc), fontSize = 12.sp) }
            }
        }
    }
    selectedText?.let { text ->
        FormDialog("Terminal transcript", { selectedText = null }, height = 720.dp, actions = {
            TextButton(onClick = { terminal.copy(text) }) { Text("Copy all") }
            TextButton(onClick = { selectedText = null }) { Text("Done") }
        }) { Text("Long press to select text.", style = MaterialTheme.typography.bodySmall); SelectionContainer { Text(text.ifEmpty { "No terminal output yet" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
    }
}
