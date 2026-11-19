package com.dimka.ftpmanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dimka.ftpmanager.data.SyncDirection
import com.dimka.ftpmanager.data.SyncMapping

@Composable
fun SyncMappingsScreen(vm: SyncViewModel) {
    var rename by remember { mutableStateOf<SyncMapping?>(null) }
    var delete by remember { mutableStateOf<SyncMapping?>(null) }
    var editedName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Синхронизации", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Сохранённые соответствия GitHub ↔ FTP",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = vm::addMapping) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Добавить")
            }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.mappings.isEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Пока нет соответствий", fontWeight = FontWeight.SemiBold)
                    Text("Создай задачу, выбери файл или папку на GitHub и FTP, затем запускай перенос в нужную сторону.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = vm::addMapping) { Text("Новое соответствие") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.mappings, key = { it.id }) { mapping ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(mapping.name, modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = {
                                    editedName = mapping.name
                                    rename = mapping
                                }) { Text("Имя") }
                                IconButton(onClick = { delete = mapping }) {
                                    Icon(Icons.Default.Delete, "Удалить соответствие")
                                }
                            }
                            EndpointBlock(
                                title = "GitHub",
                                location = if (mapping.githubPicked)
                                    mapping.repository + " · " + mapping.branch else "Репозиторий не выбран",
                                path = if (mapping.githubPicked)
                                    (if (mapping.githubDirectory) "📁 " else "📄 ") +
                                        mapping.githubPath.ifBlank { "/" } else "Выбрать файл или папку",
                                onChoose = { vm.beginGithub(mapping.id) }
                            )
                            EndpointBlock(
                                title = "FTP / FTPS / SFTP",
                                location = vm.servers.firstOrNull { it.id == mapping.serverId }
                                    ?.let { it.name + " · " + it.protocol } ?: "Хост не выбран",
                                path = if (mapping.ftpPicked)
                                    (if (mapping.ftpDirectory) "📁 " else "📄 ") + mapping.ftpPath
                                    else "Выбрать файл или папку",
                                onChoose = { vm.beginFtp(mapping.id) }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { vm.prepareSync(mapping.id, SyncDirection.GITHUB_TO_FTP) },
                                    enabled = mapping.ready && !vm.loading,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.ArrowDownward, null)
                                    Text("GitHub → FTP", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { vm.prepareSync(mapping.id, SyncDirection.FTP_TO_GITHUB) },
                                    enabled = mapping.ready && !vm.loading,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.ArrowUpward, null)
                                    Text("FTP → GitHub", fontSize = 12.sp)
                                }
                            }
                            Text("Только добавление/замена файлов. Удалений нет. FTP-перезапись с бэкапом.",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    rename?.let { mapping ->
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Название соответствия") },
            text = { OutlinedTextField(value = editedName, onValueChange = { editedName = it },
                singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Название") }) },
            confirmButton = { Button(enabled = editedName.isNotBlank(), onClick = {
                vm.renameMapping(mapping.id, editedName); rename = null
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Отмена") } }
        )
    }
    delete?.let { mapping ->
        AlertDialog(onDismissRequest = { delete = null },
            title = { Text("Удалить соответствие?") },
            text = { Text(mapping.name + ". Файлы на GitHub и FTP не затрагиваются.") },
            confirmButton = { Button(onClick = { vm.deleteMapping(mapping.id); delete = null }) {
                Text("Удалить")
            } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Отмена") } })
    }
    if (vm.picker != null) MappingPickerDialog(vm)
    vm.preview?.let { state ->
        AlertDialog(
            onDismissRequest = vm::cancelPreview,
            title = { Text(if (state.direction == SyncDirection.GITHUB_TO_FTP)
                "GitHub → FTP" else "FTP → GitHub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Передать файлов: " + state.count, fontWeight = FontWeight.SemiBold)
                    Text("Добавить: " + state.willAdd + " · Перезаписать: " + state.willReplace)
                    Text("Предварительный список путей:", fontSize = 12.sp)
                    state.sample.forEach { Text(it, fontSize = 11.sp) }
                    Text("Это сравнение наличия путей, а не содержимого. За время между просмотром и запуском файлы могли измениться. Отсутствующие в источнике файлы не удаляются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = vm::confirmSync) { Text("Начать перенос") } },
            dismissButton = { TextButton(onClick = vm::cancelPreview) { Text("Отмена") } }
        )
    }
    vm.message?.let { msg ->
        AlertDialog(onDismissRequest = vm::dismissMessage,
            title = { Text("DimkaFtpManager") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } })
    }
}

@Composable
private fun EndpointBlock(title: String, location: String, path: String, onChoose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(location, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(path, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            OutlinedButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                Text("Выбрать файл / папку")
            }
        }
    }
}

@Composable
private fun MappingPickerDialog(vm: SyncViewModel) {
    val state = vm.picker ?: return
    val title = when (state.stage) {
        "repos" -> "GitHub · репозиторий"
        "git" -> "GitHub · файл или папка"
        "servers" -> "FTP · подключение"
        else -> "FTP · файл или папка"
    }
    AlertDialog(
        onDismissRequest = vm::cancelPicker,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (state.stage) {
                    "repos" -> {
                        Text("Выбери репозиторий. Ветка будет выбрана по умолчанию.",
                            style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.heightIn(max = 350.dp)) {
                            items(vm.githubRepos) { repo ->
                                Text(repo, Modifier.fillMaxWidth().clickable { vm.chooseRepository(repo) }
                                    .padding(vertical = 11.dp))
                                HorizontalDivider()
                            }
                        }
                    }
                    "git" -> {
                        Text(state.repository + " · " + state.branch, fontSize = 12.sp)
                        Text("Путь: /" + state.folder, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = vm::githubUp, enabled = state.folder.isNotBlank()) {
                                Icon(Icons.Default.ArrowBack, null); Text("Назад")
                            }
                            TextButton(onClick = { vm.selectGithub(state.folder, true) },
                                enabled = !vm.loading && state.branch.isNotBlank()) {
                                Text("Выбрать эту папку")
                            }
                        }
                        LazyColumn(Modifier.heightIn(max = 350.dp)) {
                            items(vm.githubItems(), key = { it.path }) { entry ->
                                BrowserRow(entry, onOpen = { vm.enterGithub(entry.path) },
                                    onChoose = { vm.selectGithub(entry.path, entry.directory) })
                            }
                        }
                    }
                    "servers" -> {
                        Text("Выбери FTP/FTPS/SFTP-хост из сохранённых подключений.",
                            style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.heightIn(max = 350.dp)) {
                            items(vm.servers, key = { it.id }) { item ->
                                Column(Modifier.fillMaxWidth().clickable { vm.chooseServer(item.id) }
                                    .padding(vertical = 10.dp)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold)
                                    Text(item.host + ":" + item.port + " · " + item.protocol,
                                        fontSize = 11.sp)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    "ftp" -> {
                        Text(state.folder, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            TextButton(onClick = vm::remoteUp) {
                                Icon(Icons.Default.ArrowBack, null); Text("Назад")
                            }
                            TextButton(onClick = vm::refreshRemote) {
                                Icon(Icons.Default.Refresh, null); Text("Обновить")
                            }
                        }
                        OutlinedButton(onClick = { vm.selectFtp(state.folder, true) },
                            enabled = !vm.loading, modifier = Modifier.fillMaxWidth()) {
                            Text("Выбрать эту папку")
                        }
                        LazyColumn(Modifier.heightIn(max = 350.dp)) {
                            items(vm.remoteEntries, key = { it.path }) { entry ->
                                BrowserRow(entry, onOpen = { vm.enterRemote(entry.path) },
                                    onChoose = { vm.selectFtp(entry.path, entry.directory) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::cancelPicker) { Text("Закрыть") } }
    )
}

@Composable
private fun BrowserRow(entry: MappingBrowserItem, onOpen: () -> Unit, onChoose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null)
        Column(Modifier.weight(1f).padding(start = 7.dp)) {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            if (entry.directory) {
                TextButton(onClick = onOpen) { Text("Открыть") }
            }
        }
        TextButton(onClick = onChoose) { Text("Выбрать") }
    }
    HorizontalDivider()
}
