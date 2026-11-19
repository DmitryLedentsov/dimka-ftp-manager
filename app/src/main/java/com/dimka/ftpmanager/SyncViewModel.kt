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
import com.dimka.ftpmanager.github.GitHubClient
import com.dimka.ftpmanager.github.GitHubOAuth
import com.dimka.ftpmanager.remote.RemoteClient
import com.dimka.ftpmanager.remote.cleanRemotePath
import com.dimka.ftpmanager.remote.isWithinRoot
import com.dimka.ftpmanager.transfer.FileOperations
import com.dimka.ftpmanager.transfer.TransferWorker
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

    fun prepareSync(id: String, direction: SyncDirection) {
        val mapping = mappings.firstOrNull { it.id == id } ?: return
        if (!mapping.ready) { message = "Выбери файл ↔ файл или папку ↔ папку"; return }
        val server = store.servers().firstOrNull { it.id == mapping.serverId }
        if (server == null || !isWithinRoot(mapping.ftpPath, server.root)) {
            message = "FTP-сервер удалён или корень изменился"; return
        }
        loading = true
        preview = null
        viewModelScope.launch {
            try {
                preview = withContext(Dispatchers.IO) {
                    val git = GitHubClient(GitHubOAuth.accessToken(getApplication()))
                    val tree = git.tree(mapping.repository, mapping.branch)
                    val gitPaths = tree.map { it.path }.toSet()
                    val examples = mutableListOf<String>()
                    var overwrite = 0
                    val count: Int
                    if (direction == SyncDirection.GITHUB_TO_FTP) {
                        val source = tree.mapNotNull { item ->
                            SyncPaths.relative(item.path, mapping.githubPath, mapping.githubDirectory)
                                ?.let { it to item.path }
                        }
                        check(source.isNotEmpty()) { "Нет файлов по пути в GitHub" }
                        check(source.size <= SyncPaths.MAX_FILES) { "Слишком много файлов, выбери подпапку" }
                        RemoteClient.connect(getApplication(), server).use { ftp ->
                            for ((relative, _) in source) {
                                val target = SyncPaths.ftpDestination(mapping, relative)
                                val exists = runCatching { ftp.existsFile(target) }.getOrDefault(false)
                                if (exists) overwrite++
                                if (examples.size < 10) examples.add((if (exists) "Заменить: " else "Добавить: ") + target)
                            }
                        }
                        count = source.size
                    } else {
                        val source = RemoteClient.connect(getApplication(), server).use { ftp ->
                            if (mapping.ftpDirectory) FileOperations.scan(ftp, mapping.ftpPath).files.map { it.relative }
                            else {
                                check(ftp.existsFile(mapping.ftpPath)) { "FTP-файл не найден" }
                                listOf(mapping.ftpPath.substringAfterLast('/'))
                            }
                        }
                        check(source.isNotEmpty()) { "FTP-папка пуста" }
                        check(source.size <= SyncPaths.MAX_FILES) { "Слишком много файлов, выбери подпапку" }
                        for (rel in source) {
                            val target = SyncPaths.githubDestination(mapping, rel)
                            val exists = target in gitPaths
                            if (exists) overwrite++
                            if (examples.size < 10) examples.add((if (exists) "Заменить: " else "Добавить: ") + target)
                        }
                        count = source.size
                    }
                    SyncPreview(mapping.id, direction, count, overwrite, count - overwrite, examples)
                }
            } catch (e: Exception) { message = "Предпросмотр: " + e.message }
            finally { loading = false }
        }
    }

    fun confirmSync() {
        val current = preview ?: return
        preview = null
        val mapping = mappings.firstOrNull { it.id == current.mappingId }
        if (mapping == null || !mapping.ready) { message = "Соответствие изменилось"; return }
        val action = if (current.direction == SyncDirection.GITHUB_TO_FTP)
            TransferWorker.SYNC_GITHUB_TO_FTP else TransferWorker.SYNC_FTP_TO_GITHUB
        try {
            val request = OneTimeWorkRequestBuilder<TransferWorker>()
                .setInputData(TransferWorker.request(
                    TransferWorker.ACTION to action,
                    TransferWorker.SERVER to mapping.serverId,
                    TransferWorker.REMOTE to mapping.ftpPath,
                    TransferWorker.MAPPING_ID to mapping.id
                )).addTag(TransferWorker.TAG).build()
            WorkManager.getInstance(getApplication()).enqueue(request)
            message = "Задание запущено. Прогресс смотри на вкладке «Задания»."
        } catch (e: Exception) { message = "Не удалось запустить: " + e.message }
    }
}
