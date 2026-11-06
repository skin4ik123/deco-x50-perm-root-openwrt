package com.internetdeco.decocontrol

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File

@Composable internal fun RamEntryCard(node: Node, state: RamState, open: () -> Unit) {
    Surface(onClick = open, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
                Text("W", modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.title, fontWeight = FontWeight.SemiBold)
                Text(if (state.pending) "Saved operation · ${node.host}" else "OpenWrt RAM boot · X50 V1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable private fun RamStep(number: String, title: String, done: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                Text(if (done) "✓" else number, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        content()
    }
}

private fun openExternal(action: String, uri: Uri? = null) {
    runCatching { Controller.app.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Controller.toast("No app is available to open this link") }
}

/** All remote changes are explicit service actions. Opening this page only reads local state. */
@Composable internal fun OpenWrtPage(s: ScreenState, node: Node?, connect: () -> Unit, autoRefresh: Boolean = true) {
    if (node == null) { Empty("Select a Deco", "Add or select the X50 you want to boot"); return }
    val host = node.host
    val ram = RamState(result(s, host, "ram_status") as? JSONObject)
    var server by rememberSaveable(host) { mutableStateOf(ram.serverIp) }
    var serverReady by rememberSaveable(host, ram.operationId) { mutableStateOf(false) }
    var confirm by remember(host) { mutableStateOf("") }
    var exportedBackup by remember(host) { mutableStateOf<File?>(null) }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = s.ramImage.path?.let(::File)
        if (uri != null && file != null) Controller.saveExport(file, uri)
    }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) exportedBackup?.let { Controller.saveExport(it, uri) }
    }
    if (autoRefresh) LaunchedEffect(host) { Controller.refreshRamStatus(host); Controller.refreshRamImage() }
    LaunchedEffect(ram.operationId) { if (ram.operationId.isNotBlank()) server = ram.serverIp }
    val busy = s.operation?.running == true
    val ownOperation = s.operation?.takeIf { it.host == host && it.action.startsWith("ram_") }
    val statusError = s.errors["$host:ram_status"]
    val rootAvailable = node.passwordSaved || node.online || node.rootAccess() == RootAccess.CONFIGURED
    val request: (String) -> Unit = { action ->
        Controller.mutate(action, JSONObject().put("operation_id", ram.operationId), host)
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TargetBadge(node)
                Text("Boot OpenWrt in RAM", style = MaterialTheme.typography.titleLarge)
                Text("The stock firmware stays installed. The Windows laptop sends the RAM image over Ethernet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("X50 V1 · reviewed stock 4.4.60 / IPQ5018 profile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (ownOperation?.running == true) item {
            Panel {
                Text(ownOperation.stage, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Working on $host. You can switch devices; this operation keeps its target.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        statusError?.let { message -> item { Panel { Text(message, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = { Controller.refreshRamStatus(host) }) { Text("Read saved state again") } } } }
        if (ownOperation?.error != null && !ownOperation.running) item {
            Panel {
                Text("Operation stopped", fontWeight = FontWeight.SemiBold)
                SelectionContainer { Text(ownOperation.error, color = MaterialTheme.colorScheme.error) }
                Text("The saved operation and backups have been kept. Check the current state below before another boot.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (ram.pending || ram.operationId.isNotBlank()) item {
            Panel {
                Text(ram.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (ram.message.isNotBlank()) Text(ram.message)
                if (ram.error.isNotBlank()) SelectionContainer { Text(ram.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (ram.backupFolder != null) Text("Boot environment backup saved on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ram.backupFolder != null) OutlinedButton(enabled = !busy, onClick = {
                    Controller.zipBackup(File(ram.backupFolder!!)) { file ->
                        exportedBackup = file
                        saveBackup.launch("Deco-$host-RAM-boot-backup.zip")
                    }
                }) { Text("Export boot backup") }
                Text("Operation ${ram.operationId.take(8)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ram.canPrepare || ram.canStart) {
            item {
                RamStep("1", "Prepare the Windows laptop", serverReady) {
                    Text("Connect the laptop directly to this Deco by Ethernet. Keep the phone on the stock Deco Wi-Fi.")
                    OutlinedTextField(server, { server = it.trim() }, Modifier.fillMaxWidth(), enabled = !busy && !ram.pending, singleLine = true,
                        label = { Text("Laptop Ethernet IPv4 address") }, supportingText = { Text("Subnet mask 255.255.255.0 · router 192.168.1.1") },
                        isError = server.isNotBlank() && !validTftpServer(server), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text("Run your TFTP server on this adapter, UDP port 69. Keep its DHCP server off. Put the exact file below in its TFTP folder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(RamImageRepository.FILE_NAME, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    if (s.ramImage.downloading) {
                        LinearProgressIndicator(progress = { (s.ramImage.received.toFloat() / s.ramImage.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${bytes(s.ramImage.received.toDouble())} / ${bytes(s.ramImage.total.toDouble())}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = Controller::cancelRamImage) { Text("Cancel download") }
                    } else if (s.ramImage.verified) {
                        Text("Image verified · SHA-256 matches", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = { s.ramImage.path?.let(::share) }, modifier = Modifier.fillMaxWidth()) { Text("Share image to laptop") }
                        TextButton(onClick = { saveImage.launch(RamImageRepository.FILE_NAME) }, modifier = Modifier.fillMaxWidth()) { Text("Save image to phone") }
                    } else {
                        Button(enabled = !busy, onClick = { Controller.mutate("ram_image", host = host) }, modifier = Modifier.fillMaxWidth()) { Text("Download verified RAM image · 12.5 MiB") }
                    }
                    s.ramImage.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { copy(RamImageRepository.URL) }, modifier = Modifier.fillMaxWidth()) { Text("Copy download link for Windows") }
                    TextButton(onClick = { openExternal(Intent.ACTION_VIEW, Uri.parse("https://github.com/Oein/Deco-X50-Firmware-Unpack/releases/tag/deco-x50-v1-r37324.2")) }, modifier = Modifier.fillMaxWidth()) { Text("Image release and hardware details ↗") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(serverReady, { serverReady = it }, enabled = !busy)
                        Text("The TFTP server is running with this exact image", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                RamStep("2", "Check this Deco", ram.canStart) {
                    if (ram.canStart) {
                        Text("Bootloader, environment writer and flash geometry checked. A verified boot environment backup is saved on your phone.")
                    } else {
                        Text("The app reads the hardware and bootloader, checks the one-time boot plan, and saves a private backup. Preparation keeps the router running.")
                        if (rootAvailable) Button(enabled = !busy && ram.canPrepare && validTftpServer(server) && statusError == null, onClick = {
                            Controller.mutate("ram_prepare", JSONObject().put("server_ip", server), host)
                        }, modifier = Modifier.fillMaxWidth()) { Text("Check compatibility and back up") }
                        else Button(onClick = connect, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Sign in to root SSH first") }
                    }
                }
            }
            if (ram.canStart) item {
                RamStep("3", "Start OpenWrt") {
                    Text("This Deco will leave the stock mesh and boot the RAM image. Connections through it will stop.")
                    Button(enabled = !busy && serverReady && statusError == null, onClick = { confirm = "start" }, modifier = Modifier.fillMaxWidth()) { Text("Boot OpenWrt in RAM") }
                    if (!serverReady) Text("Confirm that the laptop TFTP server is ready in step 1.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ram.started || ram.openWrtVerified) item {
            RamStep("3", if (ram.openWrtVerified) "OpenWrt is running" else "Connect to OpenWrt", ram.openWrtVerified) {
                if (ram.openWrtVerified) {
                    val info = ram.data?.optJSONObject("openwrt")
                    Text("Verified over SSH · ${info?.optString("kernel").orEmpty()}", color = MaterialTheme.colorScheme.primary)
                    Text(info?.optString("board").orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { Controller.page("ram_terminal") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open OpenWrt terminal") }
                    OutlinedButton(onClick = { openExternal(Intent.ACTION_VIEW, Uri.parse("http://192.168.1.1")) }, modifier = Modifier.fillMaxWidth()) { Text("Open LuCI ↗") }
                } else {
                    Text("Watch the TFTP transfer on Windows. Then join X50-OpenWrt Wi-Fi on this phone and verify the running system.")
                    Text("The original RAM image has an open Wi-Fi network and an empty root password. This verification uses a separate SSH session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { openExternal(Settings.ACTION_WIFI_SETTINGS) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open Wi-Fi settings") }
                }
                val connection = s.connections["${RamEndpoint(host, ram.operationId).terminalKey}:22"]
                if (connection != null && (connection.busy || connection.phase == ConnectionPhase.FAILED)) ConnectionIndicator(connection, compact = false, cancel = { Controller.cancelConnection(RamEndpoint(host, ram.operationId).terminalKey) })
                Button(enabled = !busy, onClick = { request("ram_check_openwrt") }, modifier = Modifier.fillMaxWidth()) { Text(if (ram.openWrtVerified) "Reconnect and verify OpenWrt" else "Verify OpenWrt over SSH") }
                Text("LAN is 192.168.1.1. Use a top or middle jack for LAN. If DHCP is unavailable, keep the laptop at $server / 255.255.255.0.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ram.pending) item {
            Panel {
                Text("Return to stock", style = MaterialTheme.typography.titleMedium)
                Text(if (ram.canStart) "You can cancel this preparation before changing the boot policy." else "The one-time boot restores the stock boot policy before TFTP. After a power cycle, reconnect to the stock Wi-Fi and check the original Deco address.", style = MaterialTheme.typography.bodySmall)
                if (!ram.canStart) TextButton(onClick = connect, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Stock SSH sign in") }
                if (!ram.canStart) OutlinedButton(enabled = !busy, onClick = { request("ram_check_stock") }, modifier = Modifier.fillMaxWidth()) { Text("Check stock state at $host") }
                if (ram.canRestore) TextButton(enabled = !busy, onClick = { confirm = "restore" }, modifier = Modifier.fillMaxWidth()) { Text(if (ram.canStart) "Cancel preparation" else "Restore stock boot policy") }
            }
        }
        item { Text("RAM boot temporarily updates the boot environment. It keeps the installed firmware. Permanent installation is a separate step after hardware and recovery checks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.height(12.dp)) }
    }
    if (confirm.isNotBlank()) {
        val start = confirm == "start"
        AlertDialog(onDismissRequest = { confirm = "" }, title = { Text(if (start) "Boot this Deco into OpenWrt?" else if (ram.canStart) "Cancel RAM preparation?" else "Restore stock boot policy?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TargetBadge(node)
                Text(if (start) "The app will write a one-time boot command and reboot this Deco. Keep its power stable while the boot environment is saved; this hardware has a single environment copy." else if (ram.canStart) "Close this prepared operation. The app checks the saved boot policy first." else "Connect to the stock firmware at $host first. The app will only restore its own recorded boot policy after checking the router and backup. It will keep the router running.")
                if (start) Text("Windows TFTP server: ${ram.serverIp}", fontFamily = FontFamily.Monospace)
                if (start) Text("If the file is unavailable, TFTP keeps waiting until it arrives or you power-cycle the Deco.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = {
                Controller.mutate(if (start) "ram_start" else "ram_restore", JSONObject().put("operation_id", ram.operationId).put("confirmed", true), host)
                confirm = ""
            }) { Text(if (start) "Boot OpenWrt" else if (ram.canStart) "Cancel preparation" else "Restore policy") } },
            dismissButton = { TextButton(onClick = { confirm = "" }) { Text("Back") } })
    }
}
