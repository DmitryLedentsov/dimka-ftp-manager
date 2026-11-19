package com.dimka.ftpmanager

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dimka.ftpmanager.data.ImportFilter
import com.dimka.ftpmanager.data.ImportSummary
import com.dimka.ftpmanager.data.RemoteEntry
import com.dimka.ftpmanager.data.RepoEntry
import com.dimka.ftpmanager.data.RepoSettings
import com.dimka.ftpmanager.data.ServerProfile
import com.dimka.ftpmanager.data.SettingsStore
import com.dimka.ftpmanager.github.GitHubClient
import com.dimka.ftpmanager.remote.RemoteClient
import com.dimka.ftpmanager.remote.cleanRemotePath
import com.dimka.ftpmanager.remote.isWithinRoot
import com.dimka.ftpmanager.transfer.FileOperations
import com.dimka.ftpmanager.transfer.TransferWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    var servers by mutableStateOf(settings.servers())
        private set
    var repo by mutableStateOf(settings.repo())
        private set
    var selectedServerId by mutableStateOf(servers.firstOrNull()?.id)
        private set
    var folder by mutableStateOf(servers.firstOrNull()?.root ?: "/")
        private set
    var entries by mutableStateOf<List<RemoteEntry>>(emptyList())
        private set
    var selectedEntry by mutableStateOf<RemoteEntry?>(null)
        private set
    var repositoryEntries by mutableStateOf<List<RepoEntry>>(emptyList())
        private set
    var selectedRepoPaths by mutableStateOf<Set<String>>(emptySet())
        private set
    var importSummary by mutableStateOf<ImportSummary?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    val server get() = servers.firstOrNull { it.id == selectedServerId }

    init { if (selectedServerId != null) refreshFiles() }

    fun dismissMessage() { message = null }
    fun showMessage(value: String) { message = value }

    fun upsertServer(profile: ServerProfile) {
        val updated = servers.filterNot { it.id == profile.id } + profile
        settings.saveServers(updated)
        servers = updated
        selectServer(profile.id)
    }
    fun deleteServer(id: String) {
        val updated = servers.filterNot { it.id == id }
        settings.saveServers(updated)
        servers = updated
        selectServer(updated.firstOrNull()?.id)
    }
    fun saveRepo(newRepo: RepoSettings) {
        val changed = newRepo.repository != repo.repository || newRepo.branch != repo.branch
        settings.saveRepo(newRepo)
        repo = newRepo
        if (changed) { repositoryEntries = emptyList(); selectedRepoPaths = emptySet() }
        message = "GitHub-настройки сохранены"
    }
    fun selectServer(id: String?) {
        selectedServerId = id
        folder = server?.root?.let(::cleanRemotePath) ?: "/"
        selectedEntry = null
        entries = emptyList()
        if (server != null) refreshFiles()
    }
    fun selectEntry(item: RemoteEntry?) { selectedEntry = item }
    fun openFolder(path: String) {
        val current = server ?: return
        if (!isWithinRoot(path, current.root)) { message = "Выход за корневую папку запрещён"; return }
        folder = cleanRemotePath(path)
        selectedEntry = null
        refreshFiles()
    }
    fun goUp() {
        val current = server ?: return
        val parent = folder.substringBeforeLast('/', "/").ifBlank { "/" }
        if (isWithinRoot(parent, current.root)) openFolder(parent)
    }

    fun refreshFiles() {
        val current = server ?: return
        val path = folder
        viewModelScope.launch {
            isLoading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    RemoteClient.connect(getApplication(), current).use { it.list(path) }
                }
                if (selectedServerId == current.id && folder == path) entries = result
            } catch (e: Exception) { message = e.message ?: "Ошибка подключения" }
            finally { isLoading = false }
        }
    }
    fun testServer(profile: ServerProfile) {
        viewModelScope.launch {
            isLoading = true
            try {
                withContext(Dispatchers.IO) {
                    RemoteClient.connect(getApplication(), profile).use { it.list(profile.root) }
                }
                message = "Соединение установлено"
            } catch (e: Exception) { message = "Подключение не удалось: ${e.message}" }
            finally { isLoading = false }
        }
    }
    fun loadRepo() {
        val current = repo
        if (current.repository.isBlank()) { message = "Сначала настрой GitHub"; return }
        viewModelScope.launch {
            isLoading = true
            try {
                repositoryEntries = withContext(Dispatchers.IO) {
                    GitHubClient(current.token).tree(current.repository, current.branch)
                }
                selectedRepoPaths = emptySet()
            } catch (e: Exception) { message = "GitHub: ${e.message}" }
            finally { isLoading = false }
        }
    }
    fun toggleRepoPath(path: String) {
        selectedRepoPaths = if (path in selectedRepoPaths) selectedRepoPaths - path else selectedRepoPaths + path
    }
    fun prepareImport() {
        val current = server ?: return
        importSummary = null
        viewModelScope.launch {
            isLoading = true
            try {
                importSummary = withContext(Dispatchers.IO) {
                    val scan = RemoteClient.connect(getApplication(), current).use {
                        FileOperations.scan(it, current.root, ImportFilter(repo.ignore))
                    }
                    ImportSummary(scan.files.size, scan.files.sumOf { it.size }, scan.files.take(8).map { it.relative }, scan.ignored)
                }
            } catch (e: Exception) { message = "Сканирование: ${e.message}" }
            finally { isLoading = false }
        }
    }
    fun clearImportSummary() { importSummary = null }

    fun enqueue(
        action: String, remote: String = folder, uri: Uri? = null,
        replace: Boolean = false, localName: String = ""
    ) {
        val current = server ?: run { message = "Сначала выбери сервер"; return }
        val pairs = mutableListOf(
            TransferWorker.ACTION to action,
            TransferWorker.SERVER to current.id,
            TransferWorker.REMOTE to remote,
            TransferWorker.REPO to repo.repository,
            TransferWorker.BRANCH to repo.branch,
            TransferWorker.IGNORE to repo.ignore,
            "replace" to replace.toString(),
            "file_name" to localName
        )
        if (uri != null) pairs.add(TransferWorker.URI to uri.toString())
        if (action == TransferWorker.DEPLOY) {
            if (selectedRepoPaths.isEmpty()) { message = "Выбери файлы GitHub"; return }
            if (selectedRepoPaths.size > 80) { message = "За один деплой выбери не более 80 файлов"; return }
            pairs.add(TransferWorker.SELECTED to JSONArray(selectedRepoPaths.sorted()).toString())
        }
        if (action in setOf(TransferWorker.IMPORT, TransferWorker.DEPLOY) && repo.token.isBlank()) {
            message = "GitHub Token нужно указать в настройках"; return
        }
        try {
            val request = OneTimeWorkRequestBuilder<TransferWorker>()
                .setInputData(TransferWorker.request(*pairs.toTypedArray()))
                .addTag(TransferWorker.TAG).build()
            WorkManager.getInstance(getApplication()).enqueue(request)
            message = "Задание запущено — статус на вкладке «Задания»"
        } catch (e: Exception) { message = e.message ?: "Не удалось создать задание" }
    }
}
