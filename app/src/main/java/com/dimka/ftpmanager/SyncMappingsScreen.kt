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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.livedata.observeAsState
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.dimka.ftpmanager.transfer.TransferWorker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dimka.ftpmanager.data.CheckStatus
import com.dimka.ftpmanager.data.ComparedFile
import com.dimka.ftpmanager.data.FileDifference
import com.dimka.ftpmanager.data.FolderCompareMode
import com.dimka.ftpmanager.data.SyncDirection
import com.dimka.ftpmanager.data.SyncMapping
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun folderModeName(mode: FolderCompareMode): String = when (mode) {
    FolderCompareMode.ALL -> "Все файлы"
    FolderCompareMode.COMMON_ONLY -> "Только общие"
    FolderCompareMode.COMMON_AND_GITHUB -> "Общие + новые из GitHub"
}

private fun formattedTime(value: Long): String = if (value <= 0L) "ещё не было"
    else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(value))

@Composable
fun SyncMappingsScreen(vm: SyncViewModel) {
    val context = LocalContext.current
    val transfers by remember(context) {
        WorkManager.getInstance(context).getWorkInfosByTagLiveData(TransferWorker.TAG)
    }.observeAsState(emptyList<WorkInfo>())
    LaunchedEffect(vm.activeId, transfers.count { it.state.isFinished }) {
        vm.refreshStatuses()
    }
    var rename by remember { mutableStateOf<SyncMapping?>(null) }
    var delete by remember { mutableStateOf<SyncMapping?>(null) }
    var newName by remember { mutableStateOf("") }
    val active = vm.mappings.firstOrNull { it.id == vm.activeId }

    if (active == null) {
        SyncList(vm)
    } else {
        SyncDetail(vm, active, onRename = {
            newName = active.name
            rename = active
        }, onDelete = { delete = active })
    }

    rename?.let { mapping ->
        AlertDialog(onDismissRequest = { rename = null },
            title = { Text("Название соответствия") },
            text = {
                OutlinedTextField(newName, onValueChange = { newName = it },
                    label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { Button(enabled = newName.isNotBlank(), onClick = {
                vm.renameMapping(mapping.id, newName)
                rename = null
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Отмена") } })
    }
    delete?.let { mapping ->
        AlertDialog(onDismissRequest = { delete = null },
            title = { Text("Удалить соответствие?") },
            text = { Text("Удалится только связь «" + mapping.name +
                "». Содержимое FTP и GitHub не затрагивается.") },
            confirmButton = { Button(onClick = {
                vm.deleteMapping(mapping.id)
                vm.closeMapping()
                delete = null
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Отмена") } })
    }
    if (vm.picker != null) MappingPickerDialog(vm)
    if (vm.scopePicker != null) ComparisonScopeDialog(vm)
    vm.preview?.let { state ->
        AlertDialog(onDismissRequest = vm::cancelPreview,
            title = { Text(if (state.direction == SyncDirection.GITHUB_TO_FTP)
                "GitHub → FTP" else "FTP → GitHub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("К передаче: " + state.count + " файлов", fontWeight = FontWeight.SemiBold)
                    Text("Новых: " + state.willAdd + " · Перезаписать: " + state.willReplace)
                    state.sample.forEach { Text(it, fontSize = 12.sp) }
                    Text("Только выбранные файлы, проверенные по содержимому. Перед записью версии повторно проверяются. Никаких удалений.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = vm::confirmSync) { Text("Перенести") } },
            dismissButton = { TextButton(onClick = vm::cancelPreview) { Text("Отмена") } })
    }
    vm.diffFile?.let { file ->
        AlertDialog(onDismissRequest = vm::closeDiff,
            title = { Text("Diff · " + file.relative, maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Красный — FTP · зелёный — GitHub. Сравнение не меняет файлы.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vm.diffBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val result = vm.diffResult
                    if (result != null) {
                        if (result.explanation.isNotBlank())
                            Text(result.explanation, color = MaterialTheme.colorScheme.error)
                        else {
                            Text("GitHub: +" + result.added + " · FTP: −" + result.removed, fontSize = 12.sp)
                            if (result.note.isNotBlank())
                                Text(result.note, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.secondary)
                            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                                items(result.lines) { line ->
                                    val tint = when (line.marker) {
                                        "+" -> Color(0xFF14834C)
                                        "-" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(line.marker + " " + (if (line.oldLine > 0) line.oldLine.toString() else "") +
                                        ":" + (if (line.newLine > 0) line.newLine.toString() else "") + " " + line.content,
                                        color = tint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeDiff) { Text("Закрыть") } })
    }
    vm.message?.let { msg ->
        AlertDialog(onDismissRequest = vm::dismissMessage,
            title = { Text("DimkaFtpManager") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } })
    }
}

@Composable
private fun SyncList(vm: SyncViewModel) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Синхронизации", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(vm.mappings.size.toString() + " сохранённых соответствий",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = vm::checkAll, enabled = !vm.loading && vm.mappings.any { it.ready }) {
                Icon(Icons.Default.Refresh, null); Text("Все")
            }
            Button(onClick = vm::addMapping, enabled = !vm.loading) {
                Icon(Icons.Default.Add, null); Text("Новая")
            }
        }
        if (vm.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.checkProgress.isNotBlank()) Text(vm.checkProgress, fontSize = 12.sp)
        }
        if (vm.mappings.isEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Пока нет связей", fontWeight = FontWeight.SemiBold)
                    Text("Создай соответствие GitHub ↔ FTP. Здесь будут статусы и даты последней проверки.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::addMapping) { Text("Создать") }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(vm.mappings, key = { it.id }) { mapping ->
                val check = vm.statuses[mapping.id]
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { vm.openMapping(mapping.id) },
                            verticalAlignment = Alignment.CenterVertically) {
                            SyncStatusIcon(check)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(mapping.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                Text(mapping.repository.ifBlank { "GitHub не выбран" } +
                                    " ↔ " + (vm.servers.firstOrNull { it.id == mapping.serverId }?.name
                                        ?: "FTP не выбран"), fontSize = 11.sp, maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (mapping.githubDirectory)
                                    Text("Режим: " + folderModeName(mapping.compareMode),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (mapping.githubDirectory && !mapping.scope.all)
                                    Text("Область: " + mapping.scope.rules.size + " правил выбора",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.secondary)
                                Text((if (mapping.githubDirectory) "📁 " else "📄 ") + mapping.githubPath.ifBlank { "/" } +
                                    " ↔ " + mapping.ftpPath.ifBlank { "/" }, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("›", fontSize = 24.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(statusLabel(check), fontSize = 12.sp)
                                Text("Проверка: " + formattedTime(check?.checkedAt ?: 0L),
                                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if ((check?.syncedAt ?: 0L) > 0L)
                                    Text("Перенос: " + formattedTime(check!!.syncedAt), fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { vm.checkMapping(mapping.id) },
                                enabled = mapping.ready && !vm.loading) {
                                Icon(Icons.Default.Refresh, null)
                                Text(if (vm.checkingId == mapping.id) "…" else "Проверить")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatusIcon(check: CheckStatus?) {
    when (check?.status) {
        "MATCH" -> Icon(Icons.Default.CheckCircle, "Совпадает", tint = Color(0xFF15803D))
        "CHANGED" -> Icon(Icons.Default.Warning, "Различия", tint = Color(0xFFC08415))
        "EMPTY" -> Icon(Icons.Default.HelpOutline, "Нет файлов для сравнения",
            tint = MaterialTheme.colorScheme.outline)
        "ERROR" -> Icon(Icons.Default.ErrorOutline, "Ошибка", tint = MaterialTheme.colorScheme.error)
        "WARNING" -> Icon(Icons.Default.Warning, "Есть неподдерживаемые файлы", tint = Color(0xFFC08415))
        else -> Icon(Icons.Default.HelpOutline, "Не проверено", tint = MaterialTheme.colorScheme.outline)
    }
}

private fun statusLabel(check: CheckStatus?): String = when (check?.status) {
    "MATCH" -> "✓ Совпадает · " + check.matching + " файлов"
    "CHANGED" -> "Есть различия · " + check.changed + " файлов"
    "EMPTY" -> "Нет файлов в выбранном режиме"
    "WARNING" -> "Есть различия или файлы больше лимита"
    "ERROR" -> "Ошибка: " + check.error
    else -> "Не проверено / требуется повторная проверка"
}

@Composable
private fun SyncDetail(vm: SyncViewModel, mapping: SyncMapping, onRename: () -> Unit,
                       onDelete: () -> Unit) {
    val check = vm.statuses[mapping.id]
    val snapshot = vm.comparison?.takeIf { it.mapping == mapping }
    val diffFiles = snapshot?.files?.filter { it.difference != FileDifference.IDENTICAL }.orEmpty()
    val permitted = diffFiles.filter { it.difference != FileDifference.TOO_LARGE }
    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::closeMapping) {
                    Icon(Icons.Default.ArrowBack, null); Text("Назад")
                }
                Text(mapping.name, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2)
                TextButton(onClick = onRename) { Text("Имя") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SyncStatusIcon(check)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(statusLabel(check))
                    Text("Последняя проверка: " + formattedTime(check?.checkedAt ?: 0L),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Последняя передача: " + formattedTime(check?.syncedAt ?: 0L),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            EndpointBlock("GitHub", if (mapping.githubPicked)
                mapping.repository + " · " + mapping.branch else "Репозиторий не выбран",
                if (mapping.githubPicked) (if (mapping.githubDirectory) "📁 " else "📄 ") +
                    mapping.githubPath.ifBlank { "/" } else "Файл/папка не выбраны",
                onChoose = { vm.beginGithub(mapping.id) })
        }
        item {
            EndpointBlock("FTP / FTPS / SFTP",
                vm.servers.firstOrNull { it.id == mapping.serverId }?.let {
                    it.name + " · " + it.protocol
                } ?: "Хост не выбран",
                if (mapping.ftpPicked) (if (mapping.ftpDirectory) "📁 " else "📄 ") +
                    mapping.ftpPath else "Файл/папка не выбраны",
                onChoose = { vm.beginFtp(mapping.id) })
        }
        if (mapping.githubDirectory && mapping.ftpDirectory) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Область сравнения", fontWeight = FontWeight.SemiBold)
                    Text(if (mapping.scope.all) "Все папки и файлы"
                        else "Свой выбор · " + mapping.scope.rules.size + " правил",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!mapping.scope.all) {
                        mapping.scope.rules.take(6).forEach { rule ->
                            Text((if (rule.included) "✓ " else "× ") +
                                (if (rule.path.isEmpty()) "/" else rule.path +
                                    if (rule.directory) "/" else ""),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (mapping.scope.rules.size > 6)
                            Text("…и ещё " + (mapping.scope.rules.size - 6),
                                fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { vm.openScopePicker(mapping.id) },
                        enabled = mapping.ready && !vm.loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Folder, null)
                        Text("Выбрать папки и файлы")
                    }
                    Text("Сначала выбираем, что сравнивать, потом в Diff Check отмечаем, что переносить. Невыбранные FTP-папки не сканируются.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (mapping.githubDirectory && mapping.ftpDirectory) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Режим сравнения папок", fontWeight = FontWeight.SemiBold)
                    FolderCompareMode.entries.forEach { mode ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !vm.loading) {
                            vm.setCompareMode(mapping.id, mode)
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mapping.compareMode == mode,
                                enabled = !vm.loading,
                                onClick = { vm.setCompareMode(mapping.id, mode) })
                            Text(folderModeName(mode), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text("«Все» — обе стороны. «Только общие» — пересечение. «Общие + новые из GitHub» — все файлы GitHub, включая отсутствующие на FTP, но без FTP-only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Режим сохраняется для этой связи. После переключения потребуется новая проверка.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Button(onClick = { vm.checkMapping(mapping.id) }, enabled = mapping.ready && !vm.loading,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null); Text("Diff Check · Сравнить")
            }
            if (vm.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(vm.checkProgress, fontSize = 11.sp)
            }
        }
        if (snapshot != null) {
            item {
                Text("Результат: совпадают " + snapshot.matching + " · отличаются " +
                    snapshot.changes + (if (snapshot.blocked > 0) " · превышен лимит " +
                        snapshot.blocked else ""), fontWeight = FontWeight.SemiBold)
                Text("FTP и GitHub сравниваются по содержимому. Неизменённые файлы передаваться не будут.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (permitted.isNotEmpty()) Row {
                    TextButton(onClick = { vm.setAllFiles(true) }) { Text("Выбрать все") }
                    TextButton(onClick = { vm.setAllFiles(false) }) { Text("Снять выбор") }
                    TextButton(onClick = vm::invertSelectedFiles) { Text("Инвертировать") }
                }
                if (permitted.isNotEmpty()) {
                    Text("Выбрано " + vm.selectedPaths.size + " из " + permitted.size + " файлов",
                        style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            items(diffFiles, key = { it.relative }) { file ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = file.relative in vm.selectedPaths,
                        enabled = file.difference != FileDifference.TOO_LARGE,
                        onCheckedChange = { vm.toggleFile(file.relative) })
                    Column(Modifier.weight(1f)) {
                        Text(file.relative, fontSize = 12.sp, maxLines = 2)
                        Text(when (file.difference) {
                            FileDifference.CHANGED -> "Изменён"
                            FileDifference.ONLY_GITHUB -> "Только в GitHub"
                            FileDifference.ONLY_FTP -> "Только на FTP"
                            FileDifference.TOO_LARGE -> "Файл больше 20 МиБ · пропущен"
                            else -> "Совпадает"
                        }, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { vm.openDiff(file) },
                        enabled = !vm.loading && file.difference != FileDifference.TOO_LARGE) {
                        Text("Diff")
                    }
                }
                HorizontalDivider()
            }
            if (diffFiles.isEmpty()) item {
                Text(if (snapshot.files.isEmpty()) "Нет файлов для сравнения в выбранном режиме."
                    else "✓ Все файлы совпадают. Передавать ничего не требуется.",
                    color = if (snapshot.files.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF15803D))
            }
            item {
                val transferEnabled = vm.selectedPaths.isNotEmpty() && !vm.loading
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f),
                        enabled = transferEnabled && permitted.any {
                            it.relative in vm.selectedPaths && it.githubSha.isNotBlank()
                        },
                        onClick = { vm.prepareSync(mapping.id, SyncDirection.GITHUB_TO_FTP) }) {
                        Icon(Icons.Default.ArrowDownward, null)
                        Text("GitHub → FTP", fontSize = 12.sp)
                    }
                    OutlinedButton(modifier = Modifier.weight(1f),
                        enabled = transferEnabled && permitted.any {
                            it.relative in vm.selectedPaths && it.ftpSha.isNotBlank()
                        },
                        onClick = { vm.prepareSync(mapping.id, SyncDirection.FTP_TO_GITHUB) }) {
                        Icon(Icons.Default.ArrowUpward, null)
                        Text("FTP → GitHub", fontSize = 12.sp)
                    }
                }
                Text("Только отмеченные и реально отличающиеся файлы. Отсутствующие в источнике файлы не удаляются.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else item {
            Text("Нажми Diff Check для построчного сравнения и выбора файлов к передаче.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EndpointBlock(title: String, location: String, path: String, onChoose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun ComparisonScopeDialog(vm: SyncViewModel) {
    val state = vm.scopePicker ?: return
    val mapping = vm.mappings.firstOrNull { it.id == state.mappingId } ?: return
    val current = state.location.relative
    val scope = mapping.scope
    AlertDialog(
        onDismissRequest = vm::closeScopePicker,
        title = { Text("Что сравниваем?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Файлы и папки относительно выбранной связи GitHub ↔ FTP. Дерево открывается по одному уровню, без полного обхода FTP.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Путь: /" + current, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::scopeUp,
                        enabled = !vm.scopeLoading && state.history.isNotEmpty()) {
                        Icon(Icons.Default.ArrowBack, null)
                        Text("Назад")
                    }
                    TextButton(onClick = vm::refreshScopeFolder,
                        enabled = !vm.scopeLoading) {
                        Icon(Icons.Default.Refresh, null)
                        Text("Обновить")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { vm.selectEverythingInScope(mapping.id, true) },
                        enabled = !vm.scopeLoading) { Text("Всё") }
                    TextButton(onClick = { vm.selectEverythingInScope(mapping.id, false) },
                        enabled = !vm.scopeLoading) { Text("Снять всё") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = scope.isSelected(current, true),
                        enabled = !vm.scopeLoading,
                        onCheckedChange = { vm.selectScope(mapping.id, current, true, it) }
                    )
                    Text("Эта папка целиком", fontSize = 12.sp)
                }
                if (vm.scopeLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.scopeError.isNotBlank()) {
                    Text(vm.scopeError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                if (!vm.scopeLoading && vm.scopeEntries.isEmpty() && vm.scopeError.isBlank()) {
                    Text("Папка пуста или файлов нет на обеих сторонах.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(max = 355.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(vm.scopeEntries, key = { it.relative }) { item ->
                        Row(Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = scope.isSelected(item.relative, item.directory),
                                enabled = !vm.scopeLoading,
                                onCheckedChange = {
                                    vm.selectScope(mapping.id, item.relative, item.directory, it)
                                }
                            )
                            Icon(if (item.directory) Icons.Default.Folder
                                else Icons.Default.InsertDriveFile, null)
                            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                Text(item.name, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (item.onGithub) "GitHub" else "") +
                                    (if (item.onGithub && item.onFtp) " · " else "") +
                                    (if (item.onFtp) "FTP" else ""),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.directory) TextButton(
                                enabled = !vm.scopeLoading,
                                onClick = { vm.enterScopeFolder(item) }
                            ) { Text("Открыть") }
                        }
                        HorizontalDivider()
                    }
                }
                Text(if (scope.all) "Сейчас включён весь каталог."
                    else "Настроено правил: " + scope.rules.size +
                        ". Выбор сохраняется сразу, Diff Check нужно запустить заново.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = vm::closeScopePicker) { Text("Готово") }
        }
    )
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
