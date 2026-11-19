package com.dimka.ftpmanager

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dimka.ftpmanager.data.ImportFilter
import com.dimka.ftpmanager.data.FileZillaImporter
import com.dimka.ftpmanager.data.ImportSummary
import com.dimka.ftpmanager.data.RemoteEntry
import com.dimka.ftpmanager.data.RepoEntry
import com.dimka.ftpmanager.data.RepoSettings
import com.dimka.ftpmanager.data.ServerProfile
import com.dimka.ftpmanager.data.SettingsStore
import com.dimka.ftpmanager.data.FullConfigBackup
import com.dimka.ftpmanager.github.GitHubClient
import com.dimka.ftpmanager.github.GitHubOAuth
import com.dimka.ftpmanager.remote.RemoteClient
import com.dimka.ftpmanager.remote.cleanRemotePath
import com.dimka.ftpmanager.remote.isWithinRoot
import com.dimka.ftpmanager.transfer.FileOperations
import com.dimka.ftpmanager.transfer.TransferWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    var oauthSession by mutableStateOf(settings.oauth())
        private set
    var deviceAuthorization by mutableStateOf<GitHubOAuth.DeviceCode?>(null)
        private set
    var oauthBusy by mutableStateOf(false)
        private set
    var oauthStatus by mutableStateOf("")
        private set
    var availableRepositories by mutableStateOf<List<String>>(emptyList())
        private set
    private var oauthJob: Job? = null
    val hasGithubCredential get() = oauthSession != null || repo.token.isNotBlank()
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
    var fileZillaPlan by mutableStateOf<FileZillaImporter.Plan?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Sensitive JSON stays in private ViewModel state until the user confirms import. */
    var pendingConfigImport by mutableStateOf<FullConfigBackup?>(null)
        private set

    val server get() = servers.firstOrNull { it.id == selectedServerId }

    init { if (selectedServerId != null) refreshFiles() }

    fun dismissMessage() { message = null }
    fun showMessage(value: String) { message = value }

    fun exportFullConfig(uri: Uri) {
        if (isLoading) return
        viewModelScope.launch {
            isLoading = true
            try {
                withContext(Dispatchers.IO) {
                    val exported = settings.exportFullConfig()
                    val provider = getApplication<Application>().contentResolver
                    provider.openOutputStream(uri, "wt")?.use { out ->
                        out.write(exported.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("Не удалось открыть файл для записи")
                }
                message = "Полный экспорт готов. JSON содержит ВСЕ пароли и токены открытым текстом. Храни файл приватно."
            } catch (e: Exception) {
                message = "Экспорт: " + (e.message ?: "неизвестная ошибка")
            } finally {
                isLoading = false
            }
        }
    }

    fun previewFullConfigImport(uri: Uri) {
        if (isLoading) return
        pendingConfigImport = null
        viewModelScope.launch {
            isLoading = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("Не удалось открыть JSON")
                    val content = input.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            check(output.size() + read <= FullConfigBackup.MAX_JSON_BYTES) {
                                "Файл больше 16 МиБ"
                            }
                            output.write(buffer, 0, read)
                        }
                        output.toString("UTF-8")
                    }
                    FullConfigBackup.parse(content)
                }
                pendingConfigImport = parsed
            } catch (e: Exception) {
                message = "Импорт: " + (e.message ?: "ошибка чтения конфигурации")
            } finally {
                isLoading = false
            }
        }
    }

    fun cancelFullConfigImport() { pendingConfigImport = null }

    fun confirmFullConfigImport(onImported: () -> Unit) {
        val backup = pendingConfigImport ?: return
        if (isLoading) return
        viewModelScope.launch {
            isLoading = true
            try {
                // Avoid a running GitHub device flow replacing the imported session.
                cancelGithubLogin()
                withContext(Dispatchers.IO) { settings.importFullConfig(backup) }
                val oldId = selectedServerId
                servers = settings.servers()
                repo = settings.repo()
                oauthSession = settings.oauth()
                selectedServerId = oldId?.takeIf { id -> servers.any { it.id == id } }
                    ?: servers.firstOrNull()?.id
                folder = server?.root?.let(::cleanRemotePath) ?: "/"
                entries = emptyList()
                selectedEntry = null
                repositoryEntries = emptyList()
                selectedRepoPaths = emptySet()
                availableRepositories = emptyList()
                oauthStatus = if (oauthSession != null)
                    "GitHub подключён: @" + oauthSession!!.login
                else "Авторизация GitHub восстановлена из PAT или отсутствует"
                fileZillaPlan = null
                importSummary = null
                pendingConfigImport = null
                onImported()
                if (server != null) refreshFiles()
                message = "Конфигурация восстановлена: " + backup.serverCount +
                    " хостов и " + backup.mappingCount + " синхронизаций."
            } catch (e: Exception) {
                message = "Ошибка импорта: " + (e.message ?: "не удалось восстановить")
            } finally {
                isLoading = false
            }
        }
    }

    /** Parses the selected XML locally. The preview has no effect until confirmed. */
    fun previewFileZilla(uri: Uri) {
        viewModelScope.launch {
            isLoading = true
            fileZillaPlan = null
            try {
                val plan = withContext(Dispatchers.IO) {
                    val source = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("Не удалось открыть XML")
                    val parsed = FileZillaImporter.parse(source)
                    FileZillaImporter.plan(servers, parsed)
                }
                fileZillaPlan = plan
            } catch (e: Exception) {
                message = "Импорт FileZilla: " + (e.message ?: "ошибка чтения XML")
            } finally {
                isLoading = false
            }
        }
    }

    fun confirmFileZillaImport() {
        val plan = fileZillaPlan ?: return
        try {
            settings.saveServers(plan.profiles)
            servers = plan.profiles
            if (selectedServerId == null && servers.isNotEmpty()) {
                selectedServerId = servers.first().id
                folder = servers.first().root
                entries = emptyList()
                selectedEntry = null
            }
            fileZillaPlan = null
            message = "FileZilla: добавлено ${plan.added}, обновлено ${plan.updated}, без изменений ${plan.unchanged}"
        } catch (e: Exception) {
            message = "Не удалось сохранить импорт: " + (e.message ?: "ошибка")
        }
    }

    fun cancelFileZillaImport() {
        fileZillaPlan = null
    }

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

    fun startGithubLogin() {
        if (oauthBusy) return
        oauthJob = viewModelScope.launch {
            oauthBusy = true
            oauthStatus = "Запрашиваем код у GitHub…"
            deviceAuthorization = null
            try {
                val authorization = withContext(Dispatchers.IO) { GitHubOAuth.requestDeviceCode() }
                deviceAuthorization = authorization
                oauthStatus = "Открой GitHub, введи код и подтверди доступ"
                val deadline = System.currentTimeMillis() + authorization.expiresIn * 1000L
                var interval = authorization.interval
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    val response = withContext(Dispatchers.IO) { GitHubOAuth.poll(authorization.code) }
                    when (val errorCode = response.optString("error")) {
                        "" -> {
                            val token = response.optString("access_token")
                            if (token.isBlank()) throw IllegalStateException("GitHub не вернул токен")
                            val login = withContext(Dispatchers.IO) { GitHubOAuth.login(token) }
                            val session = GitHubOAuth.sessionFrom(response, login)
                            withContext(Dispatchers.IO) { settings.saveOAuth(session) }
                            oauthSession = session
                            oauthStatus = "GitHub подключён: @" + login
                            loadRepositories()
                            return@launch
                        }
                        "authorization_pending" -> Unit
                        "slow_down" -> interval = maxOf(interval + 5L, response.optLong("interval", interval + 5L))
                        "expired_token", "token_expired" -> throw IllegalStateException("Код GitHub истёк. Запусти вход снова.")
                        "access_denied" -> throw IllegalStateException("Авторизация отклонена в GitHub")
                        else -> throw IllegalStateException(response.optString("error_description", errorCode))
                    }
                }
                throw IllegalStateException("Код GitHub истёк. Запусти вход снова.")
            } catch (_: CancellationException) {
                oauthStatus = "Авторизация отменена"
            } catch (e: Exception) {
                oauthStatus = e.message ?: "Не удалось подключить GitHub"
            } finally {
                deviceAuthorization = null
                oauthBusy = false
            }
        }
    }

    fun cancelGithubLogin() {
        oauthJob?.cancel()
        deviceAuthorization = null
        oauthBusy = false
        oauthStatus = "Авторизация отменена"
    }

    fun logoutGithub() {
        cancelGithubLogin()
        settings.clearOAuth()
        oauthSession = null
        availableRepositories = emptyList()
        oauthStatus = if (repo.token.isNotBlank()) "OAuth отключён. Доступен ручной PAT." else "GitHub отключён"
    }

    fun loadRepositories() {
        if (!hasGithubCredential) { oauthStatus = "Сначала подключи GitHub"; return }
        viewModelScope.launch {
            isLoading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    GitHubClient(GitHubOAuth.accessToken(getApplication())).repositories()
                }
                availableRepositories = result
                if (result.isEmpty()) oauthStatus = "Репозитории не найдены"
            } catch (e: Exception) {
                oauthStatus = "Не удалось загрузить репозитории: " + (e.message ?: "ошибка")
            } finally {
                isLoading = false
            }
        }
    }

    fun chooseRepository(name: String) {
        viewModelScope.launch {
            isLoading = true
            try {
                val branch = withContext(Dispatchers.IO) {
                    GitHubClient(GitHubOAuth.accessToken(getApplication())).verify(name)
                }
                saveRepo(repo.copy(repository = name, branch = branch))
                oauthStatus = "Выбран репозиторий " + name
            } catch (e: Exception) {
                oauthStatus = "Не удалось выбрать репозиторий: " + (e.message ?: "ошибка")
            } finally {
                isLoading = false
            }
        }
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
                    GitHubClient(GitHubOAuth.accessToken(getApplication())).tree(current.repository, current.branch)
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
        if (action in setOf(TransferWorker.IMPORT, TransferWorker.DEPLOY) && !hasGithubCredential) {
            message = "Сначала войди в GitHub или укажи PAT в настройках"; return
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
