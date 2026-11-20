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
import com.dimka.ftpmanager.data.ComparisonScope
import com.dimka.ftpmanager.data.BranchFileSelections
import com.dimka.ftpmanager.data.FolderCompareMode
import com.dimka.ftpmanager.data.SyncFileSelection
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

data class ScopeLocation(
    val relative: String = "",
    val onGithub: Boolean = true,
    val onFtp: Boolean = true
)

data class ScopePicker(
    val mappingId: String,
    val location: ScopeLocation = ScopeLocation(),
    val history: List<ScopeLocation> = emptyList()
)

data class ScopeBrowserItem(
    val relative: String,
    val name: String,
    val directory: Boolean,
    val onGithub: Boolean,
    val onFtp: Boolean
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
    var branchPickerId by mutableStateOf<String?>(null)
        private set
    var githubBranches by mutableStateOf<List<String>>(emptyList())
        private set
    var branchPickerBusy by mutableStateOf(false)
        private set
    var branchPickerError by mutableStateOf("")
        private set
    var remoteEntries by mutableStateOf<List<MappingBrowserItem>>(emptyList())
        private set
    var scopePicker by mutableStateOf<ScopePicker?>(null)
        private set
    var scopeEntries by mutableStateOf<List<ScopeBrowserItem>>(emptyList())
        private set
    var scopeLoading by mutableStateOf(false)
        private set
    var scopeError by mutableStateOf("")
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


    /** Scope is configured relative to a mapping, not a global repository/host pair. */
    fun openScopePicker(id: String) {
        val mapping = mappings.firstOrNull { it.id == id } ?: return
        if (!mapping.ready || !mapping.githubDirectory || !mapping.ftpDirectory) {
            message = "Сначала настрой соответствие двух папок"
            return
        }
        if (loading || checkAllRunning) return
        scopePicker = ScopePicker(id)
        scopeEntries = emptyList()
        refreshScopeFolder()
    }

    fun closeScopePicker() {
        scopePicker = null
        scopeEntries = emptyList()
        scopeError = ""
    }

    fun refreshScopeFolder() {
        val state = scopePicker ?: return
        if (scopeLoading) return
        val mapping = mappings.firstOrNull { it.id == state.mappingId } ?: return
        val location = state.location
        scopeLoading = true
        scopeError = ""
        scopeEntries = emptyList()
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val gitEntries = if (location.onGithub) {
                        val github = GitHubClient(GitHubOAuth.accessToken(getApplication()))
                        val path = listOf(mapping.githubPath.trim('/'), location.relative)
                            .filter { it.isNotBlank() }.joinToString("/")
                        github.listFolder(mapping.repository, mapping.branch, path)
                    } else emptyList()

                    val ftpEntries = if (location.onFtp) {
                        val server = store.servers().firstOrNull { it.id == mapping.serverId }
                            ?: error("FTP-сервер не найден")
                        val path = if (location.relative.isBlank()) mapping.ftpPath
                            else SyncPaths.ftpDestination(mapping, location.relative)
                        RemoteClient.connect(getApplication(), server).use { ftp ->
                            ftp.list(path)
                        }
                    } else emptyList()

                    val combined = linkedMapOf<String, ScopeBrowserItem>()
                    for (item in gitEntries) {
                        if (item.name.isBlank() || item.name == "." || item.name == ".." ||
                            '/' in item.name || '\\' in item.name) continue
                        val relative = listOf(location.relative, item.name)
                            .filter { it.isNotEmpty() }.joinToString("/")
                        combined[item.name] = ScopeBrowserItem(
                            relative, item.name, item.directory, true, false
                        )
                    }
                    for (item in ftpEntries) {
                        if (item.name.isBlank() || item.name == "." || item.name == ".." ||
                            '/' in item.name || '\\' in item.name) continue
                        val relative = listOf(location.relative, item.name)
                            .filter { it.isNotEmpty() }.joinToString("/")
                        val previous = combined[item.name]
                        combined[item.name] = ScopeBrowserItem(
                            relative, item.name, item.directory || (previous?.directory == true),
                            previous?.onGithub == true, true
                        )
                    }
                    combined.values.sortedWith(compareByDescending<ScopeBrowserItem> {
                        it.directory
                    }.thenBy { it.name.lowercase() })
                }
                if (scopePicker == state && mappings.any { it == mapping }) {
                    scopeEntries = entries
                }
            } catch (e: Exception) {
                if (scopePicker == state) scopeError =
                    "Не удалось загрузить каталог: " + (e.message ?: "ошибка")
            } finally {
                scopeLoading = false
            }
        }
    }

    fun enterScopeFolder(item: ScopeBrowserItem) {
        val current = scopePicker ?: return
        if (scopeLoading || !item.directory) return
        scopePicker = current.copy(
            location = ScopeLocation(item.relative, item.onGithub, item.onFtp),
            history = current.history + current.location
        )
        refreshScopeFolder()
    }

    fun scopeUp() {
        val current = scopePicker ?: return
        if (scopeLoading || current.history.isEmpty()) return
        scopePicker = current.copy(
            location = current.history.last(),
            history = current.history.dropLast(1)
        )
        refreshScopeFolder()
    }

    fun selectScope(id: String, relative: String, directory: Boolean, included: Boolean) {
        if (loading || scopeLoading || checkAllRunning) return
        replace(id) {
            if (!it.githubDirectory || !it.ftpDirectory) it
            else it.copy(scope = it.scope.withSelection(relative, directory, included))
        }
    }

    fun selectEverythingInScope(id: String, selected: Boolean) {
        if (loading || scopeLoading || checkAllRunning) return
        replace(id) {
            if (!it.githubDirectory || !it.ftpDirectory) it
            else it.copy(scope = ComparisonScope().withSelection("", true, selected))
        }
    }

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
        val state = when {
            result.files.isEmpty() -> "EMPTY"
            result.blocked > 0 -> "WARNING"
            result.synchronized -> "MATCH"
            else -> "CHANGED"
        }
        val newStatus = old.copy(status = state, checkedAt = result.checkedAt,
            matching = result.matching, changed = result.changes, error = "")
        store.updateCheckStatus(id) { newStatus }
        statuses = store.checkStatuses()
        if (activeId == id) {
            comparison = result
            selectedPaths = result.mapping.fileSelections.visible(
                result.mapping.branch, SyncFileSelection.selectable(result.files)
            )
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
                        if (done == total || done % 5 == 0) {
                            viewModelScope.launch { checkProgress = "Проверено " + done + " / " + total }
                        }
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
    /**
     * Persist checkbox choices without invalidating content hashes. Only metadata
     * in the checked snapshot changes; a transfer still uses the exact checked
     * file hashes and rechecks them in TransferWorker before writing anything.
     */
    private fun rememberFileSelection(nextSelection: Set<String>) {
        if (loading || checkAllRunning) return
        val checked = comparison ?: return
        val mapping = mappings.firstOrNull { it.id == checked.mapping.id } ?: return
        if (checked.mapping != mapping) return
        val eligible = SyncFileSelection.selectable(checked.files)
        val selected = nextSelection.intersect(eligible)
        val nextMapping = mapping.copy(
            fileSelections = mapping.fileSelections.remember(mapping.branch, eligible, selected)
        )
        try {
            store.saveMappings(mappings.map { if (it.id == mapping.id) nextMapping else it })
            mappings = mappings.map { if (it.id == mapping.id) nextMapping else it }
            comparison = checked.copy(mapping = nextMapping)
            selectedPaths = selected
            preview = null
        } catch (e: Exception) {
            message = "Не удалось сохранить выбор файлов: " + e.message
        }
    }

    fun toggleFile(relative: String) {
        val check = comparison ?: return
        if (relative !in SyncFileSelection.selectable(check.files)) return
        rememberFileSelection(
            if (relative in selectedPaths) selectedPaths - relative else selectedPaths + relative
        )
    }
    fun setAllFiles(value: Boolean) {
        val check = comparison ?: return
        rememberFileSelection(if (value) SyncFileSelection.selectable(check.files) else emptySet())
    }
    fun invertSelectedFiles() {
        val check = comparison ?: return
        rememberFileSelection(SyncFileSelection.invert(check.files, selectedPaths))
    }
    fun setCompareMode(id: String, mode: FolderCompareMode) {
        if (loading || checkAllRunning) return
        val mapping = mappings.firstOrNull { it.id == id } ?: return
        if (!mapping.githubDirectory || !mapping.ftpDirectory || mapping.compareMode == mode) return
        replace(id) { it.copy(compareMode = mode) }
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

    /** Reload new host and mapping collections after a full configuration import. */
    fun reloadFromStore() {
        activeId = null
        picker = null
        branchPickerId = null
        githubBranches = emptyList()
        scopePicker = null
        scopeEntries = emptyList()
        preview = null
        comparison = null
        selectedPaths = emptySet()
        diffFile = null
        diffResult = null
        servers = store.servers()
        mappings = store.mappings()
        statuses = store.checkStatuses()
        githubRepos = emptyList()
        githubTree = emptyList()
        remoteEntries = emptyList()
    }

    fun dismissMessage() { message = null }
    fun cancelPicker() { picker = null }

    fun openBranchPicker(id: String) {
        val mapping = mappings.firstOrNull { it.id == id } ?: return
        if (!mapping.githubPicked || mapping.repository.isBlank()) return
        if (loading || checkAllRunning || branchPickerBusy) return
        branchPickerId = id
        githubBranches = emptyList()
        branchPickerError = ""
        branchPickerBusy = true
        viewModelScope.launch {
            try {
                val names = withContext(Dispatchers.IO) {
                    GitHubClient(GitHubOAuth.accessToken(getApplication()))
                        .branches(mapping.repository)
                }
                if (branchPickerId == id &&
                    mappings.any { it.id == id && it.repository == mapping.repository }) {
                    githubBranches = names
                    if (names.isEmpty()) branchPickerError = "В репозитории нет доступных веток"
                }
            } catch (e: Exception) {
                if (branchPickerId == id)
                    branchPickerError = "Не удалось получить ветки: " + (e.message ?: "ошибка")
            } finally {
                branchPickerBusy = false
            }
        }
    }

    fun closeBranchPicker() {
        branchPickerId = null
        githubBranches = emptyList()
        branchPickerError = ""
    }

    fun chooseBranch(id: String, branch: String) {
        if (loading || checkAllRunning || branchPickerBusy || branchPickerId != id ||
            branch !in githubBranches) return
        val mapping = mappings.firstOrNull { it.id == id } ?: return
        // Do NOT reset scope or per-branch file choices here.
        if (branch != mapping.branch) replace(id) { it.copy(branch = branch) }
        closeBranchPicker()
    }
    fun cancelPreview() { preview = null }
    private fun save(value: List<SyncMapping>) {
        try {
            store.saveMappings(value)
            mappings = value
        } catch (e: Exception) { message = "Не удалось сохранить: " + e.message }
    }
    private fun replace(id: String, fn: (SyncMapping) -> SyncMapping) {
        val old = mappings.firstOrNull { it.id == id } ?: return
        val next = fn(old)
        save(mappings.map { if (it.id == id) next else it })
        if (old.copy(name = next.name) != next && next in mappings) {
            store.updateCheckStatus(id) {
                it.copy(status = "UNKNOWN", checkedAt = 0L, matching = 0, changed = 0, error = "")
            }
            statuses = store.checkStatuses()
            comparison = null
            selectedPaths = emptySet()
        }
    }
    fun addMapping() {
        val next = SyncMapping(name = "Соответствие " + (mappings.size + 1))
        save(mappings + next)
        if (next in mappings) openMapping(next.id)
    }
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
                    val previous = mappings.firstOrNull { it.id == current.mappingId }
                    val branch = if (previous?.repository == repo &&
                        previous.branch.isNotBlank()) previous.branch else client.verify(repo)
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
                ftpPath = if (mismatch) "" else old.ftpPath,
                scope = if (old.githubPath != path.trim('/') ||
                    old.repository != current.repository) ComparisonScope() else old.scope,
                fileSelections = if (old.githubPath != path.trim('/') ||
                    old.repository != current.repository) BranchFileSelections()
                    else old.fileSelections)
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
                githubPath = if (mismatch) "" else old.githubPath,
                scope = if (old.ftpPath != cleanRemotePath(path) || old.serverId != current.serverId)
                    ComparisonScope() else old.scope)
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
