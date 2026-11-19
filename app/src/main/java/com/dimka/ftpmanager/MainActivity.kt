package com.dimka.ftpmanager

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dimka.ftpmanager.data.RepoSettings
import com.dimka.ftpmanager.data.ServerProfile
import com.dimka.ftpmanager.github.GitHubOAuth
import com.dimka.ftpmanager.remote.cleanRemotePath
import com.dimka.ftpmanager.transfer.TransferWorker
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()
    private val syncVm by viewModels<SyncViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF79C7FF),
                    secondary = Color(0xFF9BE0C5),
                    background = Color(0xFF0E1525),
                    surface = Color(0xFF18243A)
                )
            ) { MainScreen(vm, syncVm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel, syncVm: SyncViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("FTP", "GitHub", "Задания", "Настройки")
    val icons = listOf(Icons.Default.Folder, Icons.Default.Code, Icons.Default.History, Icons.Default.Settings)
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DimkaFtpManager", fontWeight = FontWeight.SemiBold)
                    Text("$tabLabel • без облачного backend", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icons[index], contentDescription = title) },
                        label = { Text(title, maxLines = 1, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> FtpScreen(vm)
                1 -> SyncMappingsScreen(syncVm)
                2 -> JobsScreen()
                3 -> SettingsScreen(vm)
            }
        }
    }
    vm.message?.let { msg ->
        AlertDialog(onDismissRequest = vm::dismissMessage, title = { Text("DimkaFtpManager") },
            text = { Text(msg) }, confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } })
    }
    vm.fileZillaPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = vm::cancelFileZillaImport,
            title = { Text("Импорт сайтов FileZilla") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Найдено записей: ${plan.entriesRead}")
                    Text("Добавить: ${plan.added} • Обновить: ${plan.updated} • Без изменений: ${plan.unchanged}")
                    if (plan.duplicateEntries > 0) {
                        Text("Повторов одного подключения в XML: ${plan.duplicateEntries} (объединены)")
                    }
                    if (plan.conflictingPasswords > 0) {
                        Text("В XML указаны разные пароли для ${plan.conflictingPasswords} одинаковых подключений. Использован последний непустой пароль — проверь доступ после импорта.",
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (plan.skipped > 0) {
                        Text("Пропущено неподдерживаемых или некорректных записей: ${plan.skipped}")
                    }
                    if (plan.passwordsNeeded > 0) {
                        Text("Подключений без сохранённого пароля: ${plan.passwordsNeeded}. Пароль можно указать вручную.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (plan.examples.isNotEmpty()) {
                        Text("Примеры изменений:", fontWeight = FontWeight.SemiBold)
                        plan.examples.forEach { Text("• " + it, fontSize = 12.sp) }
                    }
                    Text("Совпадение определяется по протоколу, хосту, порту и логину. Названия, ID, корневые папки и пароли, которых нет в XML, сохраняются.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(
                    enabled = plan.added + plan.updated > 0,
                    onClick = vm::confirmFileZillaImport
                ) { Text("Применить") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelFileZillaImport) { Text("Отмена") }
            }
        )
    }
    vm.importSummary?.let { summary ->
        AlertDialog(onDismissRequest = vm::clearImportSummary,
            title = { Text("Первый импорт в GitHub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("К отправке: ${summary.files} файлов (${humanSize(summary.bytes)}). Исключено путей: ${summary.skipped}.")
                    Text("Примеры:", fontWeight = FontWeight.SemiBold)
                    summary.examples.forEach { Text(it, fontSize = 12.sp) }
                    Text("ВНИМАНИЕ: импорт может перезаписать одноимённые файлы в GitHub. Проверь .gitignore и отсутствие секретов.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(enabled = summary.files > 0, onClick = {
                    vm.enqueue(TransferWorker.IMPORT, vm.server?.root ?: "/")
                    vm.clearImportSummary()
                    tab = 2
                }) { Text("Импортировать") }
            },
            dismissButton = { TextButton(onClick = vm::clearImportSummary) { Text("Отмена") } }
        )
    }
}

private val tabLabel: String get() = "Локальный Android-клиент"

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f КиБ", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f МиБ", bytes / 1024.0 / 1024.0)
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ServerChooser(vm: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Подключение", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.size(8.dp))
                Text(vm.server?.let { "${it.name} · ${it.protocol}" } ?: "Добавь сервер в настройках")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                vm.servers.forEach { profile ->
                    DropdownMenuItem(text = { Text("${profile.name} (${profile.protocol})") }, onClick = {
                        expanded = false
                        vm.selectServer(profile.id)
                    })
                }
            }
        }
    }
}

@Composable
private fun FtpScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var pickerAction by remember { mutableStateOf(TransferWorker.UPLOAD_FILE) }
    var pickerRemote by remember { mutableStateOf("/") }
    var pickerReplace by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "uploaded.bin"
            vm.enqueue(pickerAction, pickerRemote, uri, pickerReplace, name)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("FTP-файлы", "Файлы сервера, ZIP и локальные загрузки")
        ServerChooser(vm)
        if (vm.server == null) {
            Text("Добавь FTP/FTPS/SFTP-сервер во вкладке «Настройки»")
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = vm::goUp, enabled = cleanRemotePath(vm.folder) != cleanRemotePath(vm.server!!.root)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
            }
            Text(vm.folder, modifier = Modifier.weight(1f), fontSize = 12.sp, maxLines = 2)
            IconButton(onClick = vm::refreshFiles) { Icon(Icons.Default.Refresh, "Обновить") }
        }
        if (vm.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(vm.entries, key = { it.path }) { entry ->
                val selected = entry.path == vm.selectedEntry?.path
                OutlinedCard(
                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectEntry(entry) }
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = { vm.selectEntry(entry) })
                        Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.Code, null,
                            tint = if (entry.directory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!entry.directory) Text(humanSize(entry.size), fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (entry.directory) IconButton(onClick = { vm.openFolder(entry.path) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Открыть папку")
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        val picked = vm.selectedEntry
        Text(when {
            picked == null -> "Выбрана текущая папка"
            picked.directory -> "Папка: ${picked.name}"
            else -> "Файл: ${picked.name}"
        }, maxLines = 1, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f), enabled = picked != null && !picked.directory, onClick = {
                vm.enqueue(TransferWorker.DOWNLOAD_FILE, picked!!.path)
            }) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.size(6.dp)); Text("Скачать") }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                vm.enqueue(TransferWorker.DOWNLOAD_ZIP, if (picked?.directory == true) picked.path else vm.folder)
            }) { Icon(Icons.Default.Archive, null); Spacer(Modifier.size(6.dp)); Text("ZIP") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                pickerAction = TransferWorker.UPLOAD_FILE
                pickerReplace = picked != null && !picked.directory
                pickerRemote = if (pickerReplace) picked!!.path else if (picked?.directory == true) picked.path else vm.folder
                picker.launch(arrayOf("*/*"))
            }) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.size(6.dp)); Text(if (picked != null && !picked.directory) "Заменить" else "Файл сюда") }
            Button(modifier = Modifier.weight(1f), onClick = {
                pickerAction = TransferWorker.UPLOAD_ZIP
                pickerReplace = false
                pickerRemote = if (picked?.directory == true) picked.path else vm.folder
                picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
            }) { Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.size(6.dp)); Text("ZIP сюда") }
        }
        Text("Архив распакуется на телефоне; совпадающие файлы заменятся, остальные останутся. Старые версии → Downloads/DimkaFtpManager/Backups.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JobsScreen() {
    val context = LocalContext.current
    val manager = remember { WorkManager.getInstance(context) }
    val work by manager.getWorkInfosByTagLiveData(TransferWorker.TAG).observeAsState(emptyList())
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Задания", "Передачи выполняются в WorkManager; здесь ошибки и результаты")
        if (work.isEmpty()) Text("Пока нет операций")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(work.sortedByDescending { it.id.toString() }, key = { it.id.toString() }) { info ->
                val now = info.progress.getInt("current", 0)
                val total = info.progress.getInt("total", 0)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(info.progress.getString("label") ?: "Передача файлов", fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            Text(info.state.name, fontSize = 11.sp)
                        }
                        if (total > 0 && !info.state.isFinished) {
                            LinearProgressIndicator(progress = { now.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                            Text("$now / $total", fontSize = 11.sp)
                        }
                        info.outputData.getString("result")?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
                        info.outputData.getString("error")?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                        if (!info.state.isFinished) TextButton(onClick = { manager.cancelWorkById(info.id) }) { Text("Остановить") }
                    }
                }
            }
        }
        Text("Если операция прервалась, можно повторить её из вкладки FTP/GitHub. Резервная копия создаётся перед заменой файлов.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val fileZillaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.previewFileZilla(uri)
    }
    var repoMenuExpanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draftToken by remember(vm.repo.token) { mutableStateOf(vm.repo.token) }
    var draftRepo by remember(vm.repo.repository) { mutableStateOf(vm.repo.repository) }
    var draftBranch by remember(vm.repo.branch) { mutableStateOf(vm.repo.branch) }
    var draftIgnore by remember(vm.repo.ignore) { mutableStateOf(vm.repo.ignore) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Настройки", "Все доступы и пути задаются на телефоне")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FTP / FTPS / SFTP", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { adding = true; editing = null }) { Icon(Icons.Default.Add, null); Text("Сервер") }
        }
        OutlinedButton(
            onClick = { fileZillaPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.isLoading
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Импорт сайтов из FileZilla (XML)")
        }
        Text("Подключения из XML обновят совпадающие серверы и добавят новые. Сначала покажем результат, затем попросим подтвердить.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.servers.forEach { profile ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, null)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                        Text("${profile.protocol} · ${profile.host}:${profile.port}", fontSize = 12.sp)
                        Text(profile.root, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { editing = profile; adding = true }) { Text("Изм.") }
                    IconButton(onClick = { vm.deleteServer(profile.id) }) { Icon(Icons.Default.Delete, "Удалить") }
                }
            }
        }
        HorizontalDivider()

        Text("GitHub", style = MaterialTheme.typography.titleMedium)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    vm.oauthSession?.let { "Подключено: @" + it.login } ?: "Вход через GitHub пока не выполнен",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Вход через браузер, без сервера и Client Secret. GitHub запросит OAuth-доступ repo ко всем доступным репозиториям. Для доступа только к отдельным репозиториям используй PAT ниже.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (vm.oauthSession == null) {
                    Button(onClick = vm::startGithubLogin, enabled = !vm.oauthBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (vm.oauthBusy) "Ожидание GitHub…" else "Войти через GitHub")
                    }
                } else {
                    OutlinedButton(onClick = vm::logoutGithub, modifier = Modifier.fillMaxWidth()) {
                        Text("Выйти из GitHub")
                    }
                }
                vm.deviceAuthorization?.let { auth ->
                    Text("Код подтверждения: " + auth.userCode, style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.secondary)
                    Text("Вставь код на странице GitHub и подтверди разрешения.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("GitHub device code", auth.userCode))
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubOAuth.DEVICE_URL)))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Скопировать код и открыть GitHub") }
                    TextButton(onClick = vm::cancelGithubLogin) { Text("Отменить вход") }
                }
                if (vm.oauthStatus.isNotBlank()) Text(vm.oauthStatus, fontSize = 12.sp)
                if (vm.hasGithubCredential) {
                    OutlinedButton(onClick = vm::loadRepositories, enabled = !vm.isLoading,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Загрузить мои репозитории")
                    }
                    if (vm.availableRepositories.isNotEmpty()) {
                        Box {
                            OutlinedButton(onClick = { repoMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Выбрать из списка (" + vm.availableRepositories.size + ")")
                            }
                            DropdownMenu(expanded = repoMenuExpanded, onDismissRequest = { repoMenuExpanded = false }) {
                                vm.availableRepositories.forEach { fullName ->
                                    DropdownMenuItem(text = { Text(fullName) }, onClick = {
                                        repoMenuExpanded = false
                                        vm.chooseRepository(fullName)
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
        OutlinedTextField(value = draftRepo, onValueChange = { draftRepo = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("Репозиторий (owner/name)") }, singleLine = true)
        OutlinedTextField(value = draftBranch, onValueChange = { draftBranch = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("Ветка") }, singleLine = true)
        OutlinedTextField(value = draftToken, onValueChange = { draftToken = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("PAT (альтернатива OAuth)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Text("PAT используется, если не выполнен вход через GitHub. Токены хранятся зашифрованными в Android Keystore.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Правила исключения при FTP → GitHub", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(value = draftIgnore, onValueChange = { draftIgnore = it },
            modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 12,
            label = { Text(".gitignore (простые маски)") })
        Button(onClick = { vm.saveRepo(RepoSettings(draftToken.trim(), draftRepo.trim(), draftBranch.trim(), draftIgnore)) },
            modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Check, null); Spacer(Modifier.size(8.dp)); Text("Сохранить настройки") }
        Text("FTP без шифрования. Если доступно, лучше выбрать FTPS или SFTP. Архивы: Downloads/DimkaFtpManager/Exports и Backups.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (adding) {
        ServerDialog(existing = editing, onCancel = { adding = false },
            onSave = { vm.upsertServer(it); adding = false },
            onTest = vm::testServer)
    }
}

@Composable
private fun ServerDialog(
    existing: ServerProfile?, onCancel: () -> Unit,
    onSave: (ServerProfile) -> Unit, onTest: (ServerProfile) -> Unit
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var protocol by remember(existing?.id) { mutableStateOf(existing?.protocol ?: "FTP") }
    var host by remember(existing?.id) { mutableStateOf(existing?.host ?: "") }
    var port by remember(existing?.id) { mutableStateOf((existing?.port ?: 21).toString()) }
    var username by remember(existing?.id) { mutableStateOf(existing?.username ?: "") }
    var password by remember(existing?.id) { mutableStateOf(existing?.password ?: "") }
    var root by remember(existing?.id) { mutableStateOf(existing?.root ?: "/") }
    val valid = name.isNotBlank() && host.isNotBlank() && username.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } == true)
    fun profile() = ServerProfile(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(),
        protocol = protocol, host = host.trim(), port = port.toInt(), username = username.trim(),
        password = password, root = cleanRemotePath(root))
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(if (existing == null) "Новое подключение" else "Изменить сервер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("FTP", "FTPS", "FTPS_IMPLICIT", "SFTP").forEach { kind ->
                        FilterChip(selected = protocol == kind, onClick = {
                            val previous = protocol
                            protocol = kind
                            val previousDefault = when (previous) {
                                "SFTP" -> "22"
                                "FTPS_IMPLICIT" -> "990"
                                else -> "21"
                            }
                            if (port == previousDefault) port = if (kind == "SFTP") "22" else "21"
                        }, label = { Text(kind) })
                    }
                }
                FilterChip(
                    selected = protocol == "FTPS_IMPLICIT",
                    onClick = {
                        val previousDefault = when (protocol) {
                            "SFTP" -> "22"
                            "FTPS_IMPLICIT" -> "990"
                            else -> "21"
                        }
                        if (port == previousDefault) port = "990"
                        protocol = "FTPS_IMPLICIT"
                    },
                    label = { Text("FTPS implicit (порт 990)") }
                )
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("Хост / IP") }, singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("Порт") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(root, { root = it }, label = { Text("Корень сайта (например /public_html)") }, singleLine = true)
                Text("SFTP сейчас поддерживает вход по паролю. SSH-ключи — позже.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onSave(profile()) }) { Text("Сохранить") } },
        dismissButton = {
            Row {
                TextButton(enabled = valid, onClick = { onTest(profile()) }) { Text("Проверить") }
                TextButton(onClick = onCancel) { Text("Отмена") }
            }
        }
    )
}
