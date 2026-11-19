package com.dimka.ftpmanager

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dimka.ftpmanager.data.RepoEntry
import com.dimka.ftpmanager.data.ServerProfile
import com.dimka.ftpmanager.data.SettingsStore
import com.dimka.ftpmanager.data.SyncDirection
import com.dimka.ftpmanager.data.SyncMapping
import com.dimka.ftpmanager.data.SyncPaths
import com.dimka.ftpmanager.data.SyncPreview
import com.dimka.ftpmanager.data.MappingComparison
import com.dimka.ftpmanager.data.ComparedFile
import com.dimka.ftpmanager.data.FileDifference
import com.dimka.ftpmanager.data.CheckStatus
import com.dimka.ftpmanager.data.SyncJobDraft
import com.dimka.ftpmanager.github.GitHubClient
import com.dimka.ftpmanager.github.GitHubOAuth
import com.dimka.ftpmanager.remote.RemoteClient
import com.dimka.ftpmanager.remote.cleanRemotePath
import com.dimka.ftpmanager.remote.isWithinRoot
import com.dimka.ftpmanager.transfer.FileOperations
import com.dimka.ftpmanager.transfer.TransferWorker
import com.dimka.ftpmanager.transfer.SyncComparisonEngine
import com.dimka.ftpmanager.transfer.TextDiff
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MappingBrowserItem(val name: String, val path: String, val directory: Boolean)
data class MappingPicker(
    val mappingId: String,
    val stage: String,
    val repository: String = "",
    val branch: String = "",
    val serverId: String = "",
    val folder: String = ""
)

class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    var mappings by mutableStateOf(store.mappings())
        private set
    var servers by mutableStateOf(store.servers())
        private set
    var picker by mutableStateOf<MappingPicker?>(null)
        private set
    var githubRepos by mutableStateOf<List<String>>(emptyList())
        private set
    var githubTree by mutableStateOf<List<RepoEntry>>(emptyList())
        private set
    var remoteEntries by mutableStateOf<List<MappingBrowserItem>>(emptyList())
        private set
    var preview by mutableStateOf<SyncPreview?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    var activeId by mutableStateOf<String?>(null)
        private set
    var statuses by mutableStateOf(store.checkStatuses())
        private set
    var comparison by mutableStateOf<MappingComparison?>(null)
        private set
    var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        private set
    var checkingId by mutableStateOf<String?>(null)
        private set
    var checkAllRunning by mutableStateOf(false)
        private set
    var checkProgress by mutableStateOf("")
        private set
    var diffFile by mutableStateOf<ComparedFile?>(null)
        private set
    var diffResult by mutableStateOf<TextDiff.Preview?>(null)
        private set
    var diffBusy by mutableStateOf(false)
        private set

    fun openMapping(id: String) {
        statuses = store.checkStatuses()
        activeId = id
        comparison = null
        selectedPaths = emptySet()
    }
    fun closeMapping() {
        activeId = null
        comparison = null
        selectedPaths = emptySet()
        diffFile = null
        diffResult = null
    }
    fun refreshStatuses() { statuses = store.checkStatuses() }
    fun closeDiff() { diffFile = null; diffResult = null }

    private fun recordCheck(id: String, result: MappingComparison) {
        val old = store.checkStatuses()[id] ?: CheckStatus(id)
        val state = if (result.blocked > 0) "WARNING" else if (result.synchronized) "MATCH" else "CHANGED"
        val newStatus = old.copy(status = state, checkedAt = result.checkedAt,
            matching = result.matching, changed = result.changes, error = "")
        store.updateCheckStatus(id) { newStatus }
        statuses = store.checkStatuses()
        if (activeId == id) {
            comparison = result
            selectedPaths = result.files.filter {
                it.difference == FileDifference.CHANGED ||
                    it.difference == FileDifference.ONLY_GITHUB ||
                    it.difference == FileDifference.ONLY_FTP
            }.map { it.relative }.toSet()
        }
    }
    private fun checkError(id: String, error: Exception) {
        store.updateCheckStatus(id) {
            it.copy(status = "ERROR", error = error.message ?: error.javaClass.simpleName)
        }
        statuses = store.checkStatuses()
        if (activeId == id) {
            comparison = null
            selectedPaths = emptySet()
        }
    }
    fun checkMapping(id: String) {
        if (loading || checkAllRunning) return
        val mapping = mappings.firstOrNull { it.id == id } ?: return
        if (!mapping.ready) { message = "Сначала настрой оба пути"; return }
        loading = true
        checkingId = id
        checkProgress = "Сканирование GitHub и FTP…"
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SyncComparisonEngine.compare(getApplication(), mapping, { done, total ->
                        // State update stays on the main thread; IO callback runs on Default/IO.
                        checkProgress = "Проверено " + done + " / " + total
                    })
                }
                if (mappings.any { it == mapping }) recordCheck(id, result)
                else message = "Соответствие изменилось во время проверки, повтори."
            } catch (e: Exception) {
                checkError(id, e)
                message = "Проверка: " + e.message
            } finally {
                checkingId = null
                loading = false
                checkProgress = ""
            }
        }
    }
    fun checkAll() {
        if (loading || checkAllRunning) return
        checkAllRunning = true
        loading = true
        val batch = mappings.filter { it.ready }.toList()
        viewModelScope.launch {
            try {
                for ((index, mapping) in batch.withIndex()) {
                    checkingId = mapping.id
                    checkProgress = "Задача " + (index + 1) + " / " + batch.size + ": " + mapping.name
                    try {
                        val result = withContext(Dispatchers.IO) {
                            SyncComparisonEngine.compare(getApplication(), mapping)
                        }
                        if (mappings.any { it == mapping }) recordCheck(mapping.id, result)
                    } catch (e: Exception) {
                        checkError(mapping.id, e)
                    }
                }
            } finally {
                checkingId = null
                loading = false
                checkAllRunning = false
                checkProgress = ""
            }
        }
    }
    fun toggleFile(relative: String) {
        selectedPaths = if (relative in selectedPaths) selectedPaths - relative else selectedPaths + relative
    }
    fun setAllFiles(value: Boolean) {
        val check = comparison ?: return
        selectedPaths = if (value) check.files.filter {
            it.difference in setOf(FileDifference.CHANGED,
                FileDifference.ONLY_GITHUB, FileDifference.ONLY_FTP)
        }.map { it.relative }.toSet() else emptySet()
    }
    fun openDiff(file: ComparedFile) {
        val checked = comparison ?: return
        diffFile = file
        diffResult = null
        diffBusy = true
        viewModelScope.launch {
            try {
                diffResult = withContext(Dispatchers.IO) {
                    SyncComparisonEngine.diff(getApplication(), checked, file)
                }
            } catch (e: Exception) {
                diffResult = TextDiff.Preview(emptyList(),
                    e.message ?: "Не удалось построить diff")
            } finally {
                diffBusy = false
            }
        }
    }

    fun dismissMessage() { message = null }
    fun cancelPicker() { picker = null }
    fun cancelPreview() { preview = null }
    private fun save(value: List<SyncMapping>) {
        try {
            store.saveMappings(value)
            mappings = value
        } catch (e: Exception) { message = "Не удалось сохранить: " + e.message }
    }
    private fun replace(id: String, fn: (SyncMapping) -> SyncMapping) =
        save(mappings.map { if (it.id == id) fn(it) else it })
    fun addMapping() = save(mappings + SyncMapping(name = "Соответствие " + (mappings.size + 1)))
    fun renameMapping(id: String, name: String) {
        if (name.isBlank()) { message = "Укажи название"; return }
        replace(id) { it.copy(name = name.trim()) }
    }
    fun deleteMapping(id: String) = save(mappings.filterNot { it.id == id })

    fun beginGithub(id: String) {
        picker = MappingPicker(id, "repos")
        githubRepos = emptyList()
        loading = true
        viewModelScope.launch {
            try {
                githubRepos = withContext(Dispatchers.IO) {
                    GitHubClient(GitHubOAuth.accessToken(getApplication())).repositories()
                }
            } catch (e: Exception) { message = "GitHub: " + e.message }
            finally { loading = false }
        }
    }

    fun chooseRepository(repo: String) {
        val current = picker ?: return
        picker = current.copy(stage = "git", repository = repo, folder = "")
        githubTree = emptyList()
        loading = true
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val client = GitHubClient(GitHubOAuth.accessToken(getApplication()))
                    val branch = client.verify(repo)
                    branch to client.tree(repo, branch)
                }
                if (picker?.mappingId == current.mappingId && picker?.repository == repo) {
                    githubTree = data.second
                    picker = picker?.copy(branch = data.first)
                }
            } catch (e: Exception) { message = "GitHub: " + e.message }
            finally { loading = false }
        }
    }

    fun githubItems(): List<MappingBrowserItem> {
        val current = picker?.takeIf { it.stage == "git" } ?: return emptyList()
        val base = current.folder.trim('/')
        val children = linkedMapOf<String, MappingBrowserItem>()
        for (item in githubTree) {
            val rel = SyncPaths.relative(item.path, base, true) ?: continue
            val name = rel.substringBefore('/')
            val dir = '/' in rel
            val path = if (base.isBlank()) name else "$base/$name"
            if (children[name] == null || dir) children[name] = MappingBrowserItem(name, path, dir)
        }
        return children.values.sortedWith(compareByDescending<MappingBrowserItem> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun enterGithub(path: String) {
        picker = picker?.takeIf { it.stage == "git" }?.copy(folder = path.trim('/'))
    }
    fun githubUp() {
        val current = picker?.takeIf { it.stage == "git" } ?: return
        picker = current.copy(folder = current.folder.substringBeforeLast('/', ""))
    }
    fun selectGithub(path: String, directory: Boolean) {
        val current = picker?.takeIf { it.stage == "git" && it.branch.isNotBlank() } ?: return
        replace(current.mappingId) { old ->
            val mismatch = old.ftpPicked && old.ftpDirectory != directory
            old.copy(repository = current.repository, branch = current.branch,
                githubPath = path.trim('/'), githubDirectory = directory, githubPicked = true,
                ftpPicked = if (mismatch) false else old.ftpPicked,
                ftpPath = if (mismatch) "" else old.ftpPath)
        }
        picker = null
    }

    fun beginFtp(id: String) {
        servers = store.servers()
        picker = MappingPicker(id, "servers")
    }
    fun chooseServer(id: String) {
        val server = servers.firstOrNull { it.id == id } ?: return
        picker = picker?.copy(stage = "ftp", serverId = id, folder = cleanRemotePath(server.root))
        refreshRemote()
    }
    fun refreshRemote() {
        val current = picker?.takeIf { it.stage == "ftp" } ?: return
        val server = servers.firstOrNull { it.id == current.serverId } ?: return
        loading = true
        remoteEntries = emptyList()
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RemoteClient.connect(getApplication(), server).use { connection ->
                        connection.list(current.folder).map {
                            MappingBrowserItem(it.name, it.path, it.directory)
                        }
                    }
                }
                if (picker?.serverId == current.serverId && picker?.folder == current.folder)
                    remoteEntries = result
            } catch (e: Exception) { message = "FTP: " + e.message }
            finally { loading = false }
        }
    }
    fun enterRemote(path: String) {
        val current = picker?.takeIf { it.stage == "ftp" } ?: return
        val server = servers.firstOrNull { it.id == current.serverId } ?: return
        if (!isWithinRoot(path, server.root)) { message = "Путь за пределами корня"; return }
        picker = current.copy(folder = cleanRemotePath(path))
        refreshRemote()
    }
    fun remoteUp() {
        val current = picker?.takeIf { it.stage == "ftp" } ?: return
        val server = servers.firstOrNull { it.id == current.serverId } ?: return
        val parent = current.folder.substringBeforeLast('/', "/").ifBlank { "/" }
        if (isWithinRoot(parent, server.root)) enterRemote(parent)
    }
    fun selectFtp(path: String, directory: Boolean) {
        val current = picker?.takeIf { it.stage == "ftp" } ?: return
        val server = servers.firstOrNull { it.id == current.serverId } ?: return
        if (!isWithinRoot(path, server.root)) { message = "Путь за пределами корня"; return }
        replace(current.mappingId) { old ->
            val mismatch = old.githubPicked && old.githubDirectory != directory
            old.copy(serverId = current.serverId, ftpPath = cleanRemotePath(path),
                ftpDirectory = directory, ftpPicked = true,
                githubPicked = if (mismatch) false else old.githubPicked,
                githubPath = if (mismatch) "" else old.githubPath)
        }
        picker = null
    }

    private fun eligible(check: MappingComparison, direction: SyncDirection): List<ComparedFile> =
        check.files.filter { file ->
            file.relative in selectedPaths &&
                file.difference != FileDifference.IDENTICAL &&
                file.difference != FileDifference.TOO_LARGE &&
                if (direction == SyncDirection.GITHUB_TO_FTP) file.githubSha.isNotBlank()
                else file.ftpSha.isNotBlank()
        }

    fun prepareSync(id: String, direction: SyncDirection) {
        val mapping = mappings.firstOrNull { it.id == id } ?: return
        val checked = comparison
        if (checked == null || checked.mapping != mapping) {
            message = "Сначала нажми «Проверить», чтобы сравнить реальные версии файлов"
            return
        }
        val files = eligible(checked, direction)
        if (files.isEmpty()) {
            message = "Нет выбранных изменённых файлов для этого направления"
            return
        }
        val replace = files.count {
            if (direction == SyncDirection.GITHUB_TO_FTP) it.ftpSha.isNotBlank()
            else it.githubSha.isNotBlank()
        }
        preview = SyncPreview(mapping.id, direction, files.size, replace,
            files.size - replace, files.take(10).map { it.relative })
    }

    fun confirmSync() {
        val current = preview ?: return
        val checked = comparison ?: return
        val mapping = mappings.firstOrNull { it.id == current.mappingId }
        preview = null
        if (mapping == null || checked.mapping != mapping) {
            message = "Соответствие изменилось. Выполни проверку снова."
            return
        }
        val files = eligible(checked, current.direction)
        if (files.size != current.count) {
            message = "Список выбранных файлов изменился. Проверь ещё раз."
            return
        }
        val action = if (current.direction == SyncDirection.GITHUB_TO_FTP)
            TransferWorker.SYNC_GITHUB_TO_FTP else TransferWorker.SYNC_FTP_TO_GITHUB
        val id = UUID.randomUUID().toString()
        val plans = File(getApplication<Application>().filesDir, "sync-plans").apply { mkdirs() }
        val target = File(plans, id + ".json")
        try {
            target.writeText(SyncJobDraft(checked, current.direction,
                files.map { it.relative }.toSet()).toJson())
            val request = OneTimeWorkRequestBuilder<TransferWorker>()
                .setInputData(TransferWorker.request(
                    TransferWorker.ACTION to action,
                    TransferWorker.SERVER to mapping.serverId,
                    TransferWorker.REMOTE to mapping.ftpPath,
                    TransferWorker.MAPPING_ID to mapping.id,
                    TransferWorker.SYNC_PLAN to id
                )).addTag(TransferWorker.TAG).build()
            WorkManager.getInstance(getApplication()).enqueue(request)
            message = "Передача запущена. Ход выполнения — вкладка «Задания»."
        } catch (e: Exception) {
            target.delete()
            message = "Не удалось поставить задачу: " + e.message
        }
    }
}
