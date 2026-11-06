package com.internetdeco.decocontrol

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun FilesPage(s: ScreenState, host: String, connect: () -> Unit) {
    if (host.isBlank()) { Empty("Select a Deco", "Browse files on the selected device"); return }
    val nav = remember(host) { Controller.fileNavigation.getOrPut(host) { FileNavigation(Controller.filePaths[host] ?: "/tmp") } }
    var path by remember(host) { mutableStateOf(nav.path) }
    var search by remember(host) { mutableStateOf("") }
    var searchVisible by remember(host) { mutableStateOf(false) }
    var sort by remember(host) { mutableStateOf("Name") }; var sortMenu by remember { mutableStateOf(false) }
    var editing by rememberSaveable(host) { mutableStateOf<String?>(null) }
    var upload by remember(host) { mutableStateOf<File?>(null) }
    var uploadingTo by remember(host) { mutableStateOf("") }
    var name by remember(host) { mutableStateOf("") }
    var address by remember(host) { mutableStateOf<String?>(null) }
    val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val go: (String) -> Unit = { if (nav.go(it)) { path = nav.path; search = "" } }
    BackHandler(!ime && editing == null && upload == null && address == null && (searchVisible || nav.canGoBack)) {
        if (searchVisible) { searchVisible = false; search = "" }
        else if (nav.back()) path = nav.path
    }
    val refresh = { Controller.read("files", JSONObject().put("path", path), host) }
    LaunchedEffect(host, path, s.connection(host).connected, s.nodes.firstOrNull { it.host == host }?.online) {
        Controller.filePaths[host] = path
        if (Controller.bridge.connected(host)) refresh()
    }
    val data = (s.results["$host:files"] as? JSONObject)?.takeIf { it.optString("path") == path }
    val busy = "$host:files" in s.loading
    val entries = array(data?.opt("entries")).filter { it.optString("name").contains(search, true) }.sortedWith(
        compareBy<JSONObject> { if (it.optString("type") == "dir" || it.optBoolean("directory")) 0 else 1 }
            .thenComparator { a, b -> when (sort) { "Size" -> b.optLong("size").compareTo(a.optLong("size")); "Modified" -> b.optLong("mtime").compareTo(a.optLong("mtime")); else -> a.optString("name").lowercase(Locale.ROOT).compareTo(b.optString("name").lowercase(Locale.ROOT)) } }
    )
    val online = s.nodes.firstOrNull { it.host == host }?.online == true
    val canUpload = data?.optBoolean("writable") == true && FileNavigation.writableDirectory(path)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { val destination = path; Controller.importFile(uri) { file -> uploadingTo = destination; upload = file; name = file.name } }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = path != "/", onClick = { go(FileNavigation.parent(path)) }) { Text("↑ Up") }
            Text(path, Modifier.weight(1f).clickable { address = path }.padding(8.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(enabled = online, onClick = refresh) { Text("↻", fontSize = 26.sp) }
            Box {
                IconButton(onClick = { sortMenu = true }) { Text("⋮", fontSize = 26.sp) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    DropdownMenuItem(text = { Text(if (searchVisible) "Hide search" else "Find a file") }, onClick = { searchVisible = !searchVisible; search = ""; sortMenu = false })
                    DropdownMenuItem(text = { Text("Go to directory") }, onClick = { address = path; sortMenu = false })
                    listOf("Name", "Size", "Modified").forEach { value -> DropdownMenuItem(text = { Text("${if (sort == value) "✓ " else ""}Sort by ${value.lowercase(Locale.ROOT)}") }, onClick = { sort = value; sortMenu = false }) }
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Temporary" to "/tmp", "User data" to "/tp_data/user_data", "Root" to "/")) { (label, destination) -> FilterChip(path == destination, { go(destination) }, label = { Text(label) }) }
        }
        if (searchVisible) OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Filter this directory") }, singleLine = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (busy) "Reading directory…" else "${entries.size} items · ${if (canUpload) "writable" else "read only"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(enabled = online && canUpload && s.operation?.running != true, onClick = { picker.launch(arrayOf("*/*")) }) { Text("↑ Upload") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!online) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionIndicator(s.connection(host), passwordSaved = s.nodes.firstOrNull { it.host == host }?.passwordSaved == true, cancel = { Controller.cancelConnection(host) })
            if (!s.connection(host).busy) Button(onClick = { if (Controller.hasSavedPassword(host)) Controller.connect(host) else connect() }) { Text("Connect to browse files") }
        }
        s.errors["$host:files"]?.let { message -> Column(Modifier.padding(horizontal = 16.dp)) { ConnectionProblem(message); TextButton(onClick = refresh) { Text("Retry") } } }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entries.isEmpty() && !busy && online && data != null) item { Empty(if (search.isBlank()) "Empty directory" else "No matching files", if (search.isBlank()) "Choose another directory or upload a file" else "Try another name") }
            items(entries, key = { it.optString("name") }) { item ->
                val full = path.trimEnd('/') + "/" + item.optString("name")
                val directory = item.optString("type") == "dir" || item.optBoolean("directory")
                Surface(onClick = { if (directory) go(full) else { Controller.clearResult(host, "read_file"); editing = full; Controller.read("read_file", JSONObject().put("path", full), host) } }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconGlyph(if (directory) "files" else "terminal"); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.optString("name"), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (directory) FontWeight.SemiBold else FontWeight.Normal)
                            val modified = item.optLong("mtime").takeIf { it > 0 }?.let { SimpleDateFormat("dd MMM · HH:mm", Locale.US).format(Date(it * 1_000)) }
                            Text(listOfNotNull(if (directory) "Directory" else bytes(item.optDouble("size")), if (item.optString("type") == "lnk") "Link" else null, modified).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!directory) IconButton(onClick = { Controller.read("download", JSONObject().put("path", full), host) }) { Text("↓", fontSize = 23.sp) }
                    }
                }
            }
        }
        if ("$host:download" in s.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Downloading…",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium); TextButton(onClick={Controller.cancelFileRead(host,"download")}){Text("Cancel")} } }
        s.errors["$host:download"]?.let { ConnectionProblem(it, Modifier.padding(horizontal = 16.dp)) }
        (s.results["$host:download"] as? JSONObject)?.optString("path")?.takeIf { it.isNotBlank() }?.let { local ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(File(local).name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { share(local) }) { Text("Save copy") }
                IconButton(onClick = { Controller.clearResult(host, "download") }) { Text("×") }
            }
        }
    }
    editing?.let { file -> FileEditor(host, file, { editing = null }, refresh) }
    address?.let { initial ->
        var value by remember(initial) { mutableStateOf(initial) }
        FormDialog("Go to directory", { address = null }, height = 300.dp, actions = {
            TextButton(onClick = { address = null }) { Text("Cancel") }
            Button(enabled = value.startsWith('/') && !value.contains('\u0000'), onClick = { go(value); address = null }) { Text("Open") }
        }) { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("Absolute path") }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)) }
    }
    upload?.let { local ->
        FormDialog("Upload file", { upload = null; local.delete() }, height = 420.dp, actions = {
            TextButton(onClick = { upload = null; local.delete() }) { Text("Cancel") }
            Button(enabled = name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\u0000' } && s.operation?.running != true, onClick = {
                Controller.mutate("upload", JSONObject().put("path", uploadingTo.trimEnd('/') + "/" + name).put("local", local.path), host); upload = null; Controller.page("operation")
            }) { Text("Upload") }
        }) {
            Text(host, fontFamily = FontFamily.Monospace); Text(uploadingTo, fontFamily = FontFamily.Monospace)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("File name") }, singleLine = true)
            Text("${bytes(local.length().toDouble())} · Existing files are kept.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun FileEditor(host: String, file: String, close: () -> Unit, refresh: () -> Unit) {
    val s by Controller.ui.collectAsStateWithLifecycle()
    val retained = remember(host, file) { EditorDrafts.get(host, file) }
    var original by remember(file) { mutableStateOf(retained.original) }
    var draft by remember(file) { mutableStateOf(retained.text) }
    val finish = { EditorDrafts.remove(host, file); close() }
    var discard by remember(file) { mutableStateOf(false) }
    var submitted by remember(file) { mutableStateOf(retained.saving) }
    var saveError by remember(file) { mutableStateOf<String?>(null) }
    val loaded = (s.results["$host:read_file"] as? JSONObject)?.takeIf { it.optString("path") == file }
    LaunchedEffect(loaded) { if (original == null && loaded != null) { original = loaded.optString("text"); draft = original.orEmpty(); retained.original = original; retained.text = draft } }
    LaunchedEffect(host,file){if(original==null&&loaded==null&&"$host:read_file" !in s.loading)Controller.read("read_file",JSONObject().put("path",file),host)}
    val dirty = original != null && original != draft
    val writable = FileNavigation.writable(file) && loaded?.optBoolean("writable") == true
    val saving = submitted && s.operation?.running == true
    val dismiss = { if (!saving) { if (dirty) discard = true else finish() } }
    LaunchedEffect(s.operation, submitted) {
        val op = s.operation
        if (submitted && op != null && op.host == host && op.action == "write_file" && !op.running) {
            submitted = false; retained.saving = false
            if (op.error == null && op.result?.optBoolean("saved") == true) { refresh(); finish() }
            else saveError = op.error ?: "Save was not confirmed. Your edits are still here."
        }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)).padding(10.dp), contentAlignment = Alignment.TopCenter) {
            Surface(Modifier.widthIn(max = 800.dp).fillMaxWidth().height(minOf(maxHeight, 900.dp)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(File(file).name + if (dirty) " •" else "", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(enabled = !saving, onClick = dismiss) { Text("Close") }
                    }
                    Text("$host · $file", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (saving || "$host:read_file" in s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (original == null) {
                        Text(s.errors["$host:read_file"] ?: "Reading file…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if ("$host:read_file" !in s.loading) TextButton(onClick = { Controller.read("read_file", JSONObject().put("path", file), host) }) { Text("Retry reading") }
                    } else {
                        OutlinedTextField(draft, { draft = it; retained.text = it }, Modifier.fillMaxWidth().weight(1f), readOnly = !writable || saving,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), label = { Text(if (writable) "${if (dirty) "Unsaved changes" else "Text file"}" else "Read only") })
                    }
                    saveError?.let { ConnectionProblem(it) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (writable) "Previous version is backed up." else "Download to save a copy.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(enabled = !saving, onClick = { Controller.read("download", JSONObject().put("path", file), host); if (!dirty) finish() }) { Text("Download") }
                        if (writable) Button(enabled = dirty && !saving && s.operation?.running != true && draft.toByteArray().size <= 256 * 1024, onClick = {
                            saveError = null; submitted = true; retained.saving = true; Controller.mutate("write_file", JSONObject().put("path", file).put("text", draft), host)
                        }) { Text(if (saving) "Saving…" else "Save") }
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard unsaved changes?") }, text = { Text("Your edits to ${File(file).name} have not been saved.") },
        confirmButton = { TextButton(onClick = finish) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}
