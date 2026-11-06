package com.internetdeco.decocontrol

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject
import java.util.UUID

@Composable internal fun CredentialsDialog(host: String, close: () -> Unit, setupRoot: () -> Unit) {
    val s by Controller.ui.collectAsStateWithLifecycle()
    val saved = s.nodes.firstOrNull { it.host == host }?.passwordSaved == true
    var password by remember(host) { mutableStateOf("") }
    var otherPassword by remember(host) { mutableStateOf(!saved) }
    var rememberPassword by remember(host) { mutableStateOf(true) }
    var submitted by remember(host) { mutableStateOf(false) }
    var forget by remember(host) { mutableStateOf(false) }
    val connection = s.connection(host)
    val busy = connection.busy || "$host:connect" in s.loading
    val error = s.errors["$host:connect"]
    LaunchedEffect(s.results["$host:connect"], busy, submitted) {
        if (submitted && !busy && error == null && s.results.containsKey("$host:connect")) close()
    }
    FormDialog("SSH sign in", close, height = if (!saved) 610.dp else 490.dp, actions = {
        TextButton(onClick = { if (busy) Controller.cancelConnection(host) else close() }) { Text(if (busy) "Cancel connection" else "Close") }
        Button(enabled = !busy && Controller.validHost(host) && (password.isNotEmpty() || (saved && !otherPassword)), onClick = {
            submitted = true
            Controller.clearResult(host, "connect")
            Controller.connect(host, if (saved && !otherPassword) null else password, rememberPassword)
        }) { Text(if (submitted && error != null) "Try again" else "Connect") }
    }) {
        Text("root@$host", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        if (s.nodes.firstOrNull { it.host == host }?.rootAccess() != RootAccess.CONFIGURED) {
            Text("Need to set up root?", style = MaterialTheme.typography.titleMedium)
            Text("Use your TP-Link owner account to install permanent root.", style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = setupRoot, enabled = !busy && s.operation?.running != true, modifier = Modifier.fillMaxWidth()) { Text("Set up root") }
            HorizontalDivider()
        }
        ConnectionIndicator(connection, passwordSaved = saved, cancel = if (busy) ({ Controller.cancelConnection(host) }) else null)
        if (saved && !otherPassword) {
            Text("Use your saved SSH password to reconnect.")
            TextButton(enabled = !busy, onClick = { otherPassword = true }) { Text("Use a different password") }
            TextButton(enabled = !busy && s.operation?.running != true, onClick = { forget = true }) { Text("Forget saved password") }
        } else {
            PasswordField(password, { password = it }, "Root SSH password", !busy)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberPassword, { rememberPassword = it }, enabled = !busy)
                Text("Remember after successful sign-in", style = MaterialTheme.typography.bodySmall)
            }
            if (saved) TextButton(enabled = !busy, onClick = { otherPassword = false; password = "" }) { Text("Use saved password") }
        }
        if (submitted && error != null) ConnectionProblem(error)
        Text("Slow Deco? The app waits for SSH and retries connection errors automatically. Password and device key errors need your attention.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text("Forget saved password?") }, text = { Text("You will need to enter it again. SSH access on your Deco stays available.") },
        confirmButton = { TextButton(onClick = { Controller.forgetPassword(host); otherPassword = true; forget = false }) { Text("Forget") } }, dismissButton = { TextButton(onClick = { forget = false }) { Text("Keep password") } })
}

@Composable internal fun AddNodeDialog(close: () -> Unit) {
    var host by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
    FormDialog("Add Deco", close, height = 380.dp, actions = {
        TextButton(onClick = close) { Text("Cancel") }
        Button(enabled = Controller.validHost(host), onClick = { Controller.addNode(host, name); Controller.tab(1); close() }) { Text("Add device") }
    }) {
        OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Local IP address") }, placeholder = { Text("192.168.1.2") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name, for example Office") }, singleLine = true)
        Text("Each Deco keeps its own connection, password and terminal session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun RootDialog(node: Node?, close: () -> Unit) {
    val host = node?.host.orEmpty(); val s by Controller.ui.collectAsStateWithLifecycle()
    var owner by remember(host) { mutableStateOf(Controller.vault.get("owner_id")) }
    var ownerPassword by remember(host) { mutableStateOf("") }
    var main by remember(host) { mutableStateOf(Controller.ownerHost(host)) }
    var password by remember(host) { mutableStateOf("") }; var repeat by remember(host) { mutableStateOf("") }
    var confirmModel by remember(host) { mutableStateOf(false) }; var ticket by remember(host) { mutableStateOf("") }
    val checked = (s.results["$host:owner_check"] as? JSONObject)?.let { ticket.isNotBlank() && it.optString("request_id") == ticket && it.optString("controller") == main && it.optBoolean("file_service") } == true
    val checking = "$host:owner_check" in s.loading
    val installed = node?.metrics?.optBoolean("password_ssh") == true || node?.identity?.optBoolean("password_ssh") == true
    FormDialog(if (installed) "Permanent SSH" else "Set up permanent root", close, height = 700.dp, actions = {
        TextButton(onClick = close) { Text("Close") }
        if (!installed) Button(enabled = checked && !checking && confirmModel && strongPassword(password) && password == repeat, onClick = {
            Controller.mutate("install", host = host, password = password); Controller.page("operation"); close()
        }) { Text("Install root") }
    }) {
        TargetBadge(node)
        if (installed) {
            Text("Root SSH is already available. Verify startup or adopt this installation into the app.")
            Button(onClick = { Controller.read("boot_check", host = host); close() }) { Text("Verify startup") }
            OutlinedButton(onClick = { Controller.mutate("install", host = host); Controller.page("operation"); close() }) { Text("Verify and adopt existing SSH") }
            Text("Reboot for verification only when a connection interruption is acceptable.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("1 · Verify owner access", fontWeight = FontWeight.Bold)
            OutlinedTextField(main, { main = it.trim(); ticket = "" }, Modifier.fillMaxWidth(), label = { Text("Mesh controller IP address") }, supportingText = { Text("For a satellite, use the main Deco address.") }, singleLine = true)
            OutlinedTextField(owner, { owner = it; ticket = "" }, Modifier.fillMaxWidth(), label = { Text("Owner email or SSH username") }, singleLine = true)
            PasswordField(ownerPassword, { ownerPassword = it; ticket = "" }, if (Controller.vault.get("owner_password").isBlank()) "Owner password" else "Password saved · enter to replace", !checking)
            OutlinedButton(enabled = !checking && s.operation?.running != true && Controller.validHost(host) && Controller.validHost(main) && owner.isNotBlank() && (ownerPassword.isNotEmpty() || Controller.vault.get("owner_password").isNotEmpty()), onClick = {
                Controller.ownerSettings(owner, ownerPassword.ifBlank { Controller.vault.get("owner_password") }, main, host)
                ticket = UUID.randomUUID().toString()
                Controller.read("owner_check", JSONObject().put("owner_host", main).put("request_id", ticket), host)
            }) { Text(if (checking) "Checking owner access…" else if (checked) "Owner access verified" else "Check owner access") }
            s.connections.values.firstOrNull { (it.host == main || it.host == host) && it.busy }?.let { ConnectionIndicator(it, cancel = { Controller.cancelConnection(it.host) }) }
            s.errors["$host:owner_check"]?.let { message -> SelectionContainer { ConnectionProblem(message) } }
            if (checked) Text("Owner SSH and the authenticated file service are ready.", color = MaterialTheme.colorScheme.primary)
            Text("2 · Choose your SSH password", fontWeight = FontWeight.Bold)
            PasswordField(password, { password = it }, "New password · at least 12 characters")
            PasswordField(repeat, { repeat = it }, "Repeat password")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(confirmModel, { confirmModel = it }); Text("Deco X50 · V1 · EU") }
            Text("3 · Back up, install and verify", fontWeight = FontWeight.Bold)
            Text("The app checks the firmware and saves a configuration backup to your phone before installing on $host. Reboot is a separate action.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun PasswordDialog(host: String, close: () -> Unit, action: String = "password") {
    var password by remember(host) { mutableStateOf("") }; var repeat by remember(host) { mutableStateOf("") }
    FormDialog("New SSH password", close, height = 450.dp, actions = {
        TextButton(onClick = close) { Text("Cancel") }
        Button(enabled = password == repeat && strongPassword(password), onClick = { Controller.mutate(action, host = host, password = password); close(); Controller.page("operation") }) { Text("Save password") }
    }) {
        Text("root@$host", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        PasswordField(password, { password = it }, "Password · at least 12 characters")
        PasswordField(repeat, { repeat = it }, "Repeat password")
        Text("The app saves the password in encrypted storage after verification. Your TP-Link account password stays the same.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun SettingsDialog(theme: String, setTheme: (String) -> Unit, close: () -> Unit) {
    var timeout by remember { mutableIntStateOf(Controller.connectionOptions().handshakeTimeoutMs / 1_000) }
    var attempts by remember { mutableIntStateOf(Controller.connectionOptions().attempts) }
    FormDialog("Settings", close, height = 660.dp, actions = { TextButton(onClick = close) { Text("Done") } }) {
        Text("Appearance", fontWeight = FontWeight.Bold)
        listOf("system" to "System default", "dark" to "Dark", "light" to "Light").forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().clickable { setTheme(key) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(theme == key, { setTheme(key) }); Text(label) }
        }
        HorizontalDivider()
        Text("SSH patience", fontWeight = FontWeight.Bold)
        Text("Wait for SSH sign-in", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(30, 60, 90, 120).forEach { value -> FilterChip(timeout == value, { timeout = value; Controller.vault.put("ssh_timeout_seconds", value.toString()) }, label = { Text("${value}s") }) } }
        Text("Connection attempts", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..3).forEach { value -> FilterChip(attempts == value, { attempts = value; Controller.vault.put("ssh_attempts", value.toString()) }, label = { Text(value.toString()) }) } }
        Text("Connection errors are retried. Commands and file writes are sent once. You can cancel a connection at any time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Version ${BuildConfig.VERSION_NAME} · ARM64", fontWeight = FontWeight.SemiBold)
        Text("Rust + Kotlin / Compose. Direct SSH from your phone, encrypted passwords and independent sessions for every Deco.", style = MaterialTheme.typography.bodySmall)
        Text("Termux terminal · GPLv3\nJSch · BSD\nBouncy Castle · MIT\nRust JNI · Apache/MIT", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
