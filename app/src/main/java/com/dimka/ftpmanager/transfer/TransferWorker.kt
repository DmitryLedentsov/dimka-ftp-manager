package com.dimka.ftpmanager.transfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dimka.ftpmanager.data.ImportFilter
import com.dimka.ftpmanager.data.SettingsStore
import com.dimka.ftpmanager.data.SyncPaths
import com.dimka.ftpmanager.data.SyncJobDraft
import com.dimka.ftpmanager.data.SyncDirection
import com.dimka.ftpmanager.data.FileDifference
import com.dimka.ftpmanager.github.GitHubClient
import com.dimka.ftpmanager.github.GitHubOAuth
import com.dimka.ftpmanager.remote.RemoteClient
import com.dimka.ftpmanager.remote.cleanRemotePath
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class TransferWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val TAG = "dimka-transfer"
        const val ACTION = "action"
        const val SERVER = "server_id"
        const val REMOTE = "remote_path"
        const val URI = "local_uri"
        const val REPO = "repository"
        const val BRANCH = "branch"
        const val SELECTED = "selected_files"
        const val IGNORE = "ignore_rules"
        const val DOWNLOAD_FILE = "download_file"
        const val DOWNLOAD_ZIP = "download_zip"
        const val UPLOAD_FILE = "upload_file"
        const val UPLOAD_ZIP = "upload_zip"
        const val DEPLOY = "github_deploy"
        const val IMPORT = "github_import"
        const val MAPPING_ID = "mapping_id"
        const val SYNC_PLAN = "sync_plan_id"
        const val SYNC_GITHUB_TO_FTP = "sync_github_to_ftp"
        const val SYNC_FTP_TO_GITHUB = "sync_ftp_to_github"

        fun request(vararg pairs: Pair<String, String>): Data {
            val builder = Data.Builder()
            pairs.forEach { (name, value) -> builder.putString(name, value) }
            return builder.build()
        }
    }

    private fun checkpoint() {
        if (isStopped) throw CancellationException("Transfer interrupted")
    }

    private fun report(now: Int, total: Int, label: String) {
        checkpoint()
        setProgressAsync(workDataOf("current" to now, "total" to total, "label" to label))
    }

    private fun foreground(): ForegroundInfo {
        val notifications = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "dimka_transfers"
        notifications.createNotificationChannel(NotificationChannel(channel, "Передача файлов", NotificationManager.IMPORTANCE_LOW))
        val message = when (inputData.getString(ACTION)) {
            IMPORT -> "Первоначальный импорт в GitHub"
            SYNC_GITHUB_TO_FTP -> "Синхронизация GitHub → FTP"
            SYNC_FTP_TO_GITHUB -> "Синхронизация FTP → GitHub"
            DEPLOY -> "Деплой GitHub → FTP"
            DOWNLOAD_ZIP -> "Скачивание папки в ZIP"
            UPLOAD_ZIP -> "Загрузка ZIP на сервер"
            else -> "Передача файлов"
        }
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("DimkaFtpManager")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(id.hashCode() and 0x7fffffff, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val temp = File(applicationContext.cacheDir, "job-$id").apply { mkdirs() }
        try {
            setForeground(foreground())
            val action = inputData.getString(ACTION) ?: error("Missing action")
            val serverId = inputData.getString(SERVER) ?: error("Missing server")
            val settings = SettingsStore(applicationContext)
            val server = settings.servers().firstOrNull { it.id == serverId } ?: error("Server not found")
            val path = inputData.getString(REMOTE)?.let(::cleanRemotePath) ?: server.root
            check(com.dimka.ftpmanager.remote.isWithinRoot(path, server.root)) { "Path outside configured server root" }
            RemoteClient.connect(applicationContext, server).use { session ->
                val result = when (action) {
                    DOWNLOAD_FILE -> {
                        report(0, 1, "Скачивание файла")
                        FileOperations.downloadOne(applicationContext, session, path, temp).toString()
                    }
                    DOWNLOAD_ZIP -> FileOperations.downloadZip(applicationContext, session, path, temp,
                        { current, total -> report(current, total, "Архивирование") }, ::checkpoint).toString()
                    UPLOAD_FILE -> {
                        val uri = Uri.parse(inputData.getString(URI) ?: error("Missing local file"))
                        val source = File(temp, "chosen-file")
                        FileOperations.copyUri(applicationContext, uri, source)
                        val target = if (path.endsWith('/')) path.trimEnd('/') else path
                        val relative = if (inputData.getString("replace") == "true") target.substringAfterLast('/')
                            else inputData.getString("file_name")?.takeIf { it.isNotBlank() } ?: "uploaded.bin"
                        val folder = if (inputData.getString("replace") == "true") target.substringBeforeLast('/', "/").ifBlank { "/" } else target
                        report(0, 1, "Бэкап и загрузка")
                        FileOperations.uploadWithBackup(applicationContext, session, folder,
                            listOf(relative to source), temp, { a, b -> report(a, b, "Загрузка файла") }, ::checkpoint)?.toString()
                            ?: "Uploaded $relative (new file)"
                    }
                    UPLOAD_ZIP -> {
                        val uri = Uri.parse(inputData.getString(URI) ?: error("Missing archive"))
                        val unpacked = FileOperations.unpack(applicationContext, uri, File(temp, "unpacked"))
                        val mapped = FileOperations.mapZipPaths(unpacked, path)
                        FileOperations.uploadWithBackup(applicationContext, session, path, mapped,
                            temp, { a, b -> report(a, b, "Загрузка ZIP") }, ::checkpoint)?.toString()
                            ?: "Uploaded ${mapped.size} new files"
                    }
                    SYNC_GITHUB_TO_FTP, SYNC_FTP_TO_GITHUB -> {
                        val mappingId = inputData.getString(MAPPING_ID) ?: error("Missing mapping")
                        val planId = inputData.getString(SYNC_PLAN) ?: error("Missing sync plan")
                        require(Regex("[0-9a-fA-F-]{36}").matches(planId)) { "Invalid sync plan id" }
                        val draftFile = File(applicationContext.filesDir, "sync-plans/" + planId + ".json")
                        require(draftFile.isFile) { "Selected-file plan not found; run Diff Check again" }
                        val draft = SyncJobDraft.fromJson(draftFile.readText())
                        val mapping = settings.mappings().firstOrNull { it.id == mappingId }
                            ?: error("Mapping not found")
                        check(mapping.ready && mapping.serverId == serverId &&
                            draft.comparison.mapping == mapping) {
                            "Mapping changed since Diff Check; check again"
                        }
                        check(com.dimka.ftpmanager.remote.isWithinRoot(mapping.ftpPath, server.root)) {
                            "Mapped FTP path outside site root"
                        }
                        val direction = if (action == SYNC_GITHUB_TO_FTP)
                            SyncDirection.GITHUB_TO_FTP else SyncDirection.FTP_TO_GITHUB
                        check(draft.direction == direction) { "Wrong direction in selection" }
                        val chosen = draft.comparison.files.filter { it.relative in draft.selection }
                        check(chosen.isNotEmpty() && chosen.size == draft.selection.size) {
                            "Selected file list is invalid"
                        }
                        check(chosen.size <= SyncPaths.MAX_FILES) { "Too many files in selection" }
                        check(chosen.none { it.difference == FileDifference.IDENTICAL ||
                            it.difference == FileDifference.TOO_LARGE }) {
                            "Selection includes identical or unsupported files"
                        }
                        // A fresh comparison before ANY mutations detects drift since user review.
                        val fresh = SyncComparisonEngine.compare(applicationContext, mapping, server,
                            checkpoint = ::checkpoint)
                        val actual = fresh.files.associateBy { it.relative }
                        chosen.forEach { source ->
                            val now = actual[source.relative]
                                ?: error("File disappeared after Diff Check: " + source.relative)
                            check(now.githubSha == source.githubSha && now.ftpSha == source.ftpSha) {
                                "File changed since Diff Check: " + source.relative +
                                    ". Sync cancelled; recheck before retry."
                            }
                        }
                        val github = GitHubClient(GitHubOAuth.accessToken(applicationContext))
                        val stage = File(temp, "mapped-sync").apply { mkdirs() }
                        if (direction == SyncDirection.GITHUB_TO_FTP) {
                            check(chosen.all { it.githubSha.isNotBlank() }) {
                                "No GitHub source for one or more selected files"
                            }
                            val staged = chosen.mapIndexed { index, file ->
                                checkpoint()
                                val local = File(stage, "git-$index")
                                github.download(mapping.repository, mapping.branch, file.githubPath, local)
                                check(local.length() <= SyncComparisonEngine.MAX_FILE_BYTES &&
                                    SyncComparisonEngine.gitBlobHash(local) == file.githubSha) {
                                    "GitHub file changed while downloading: " + file.githubPath
                                }
                                report(index + 1, chosen.size * 2, "Скачивание из GitHub")
                                val relativeTarget = if (mapping.ftpDirectory) file.relative
                                    else mapping.ftpPath.substringAfterLast('/')
                                relativeTarget to local
                            }
                            val folder = if (mapping.ftpDirectory) mapping.ftpPath
                                else mapping.ftpPath.substringBeforeLast('/', "/").ifBlank { "/" }
                            val expected = chosen.associate { file ->
                                (if (mapping.ftpDirectory) file.relative
                                    else mapping.ftpPath.substringAfterLast('/')) to file.ftpSha
                            }
                            val backup = FileOperations.uploadWithBackup(
                                applicationContext, session, folder, staged, temp,
                                { now, _ -> report(chosen.size + now, chosen.size * 2, "Передача на FTP") },
                                ::checkpoint, expected
                            )
                            settings.updateCheckStatus(mapping.id) {
                                it.copy(status = "UNKNOWN", syncedAt = System.currentTimeMillis(),
                                    error = "")
                            }
                            "Передано только отличающихся файлов: " + chosen.size +
                                ". Бэкап: " + (backup?.toString() ?: "не требуется")
                        } else {
                            check(chosen.all { it.ftpSha.isNotBlank() }) {
                                "No FTP source for one or more selected files"
                            }
                            val staged = chosen.mapIndexed { index, file ->
                                checkpoint()
                                val local = File(stage, "ftp-$index")
                                session.download(file.ftpPath, local)
                                check(local.length() <= SyncComparisonEngine.MAX_FILE_BYTES &&
                                    SyncComparisonEngine.gitBlobHash(local) == file.ftpSha) {
                                    "FTP file changed while downloading: " + file.ftpPath
                                }
                                report(index + 1, chosen.size * 2 + 1, "Скачивание с FTP")
                                file.githubPath to local
                            }
                            val sha = github.commitFiles(mapping.repository, mapping.branch, staged,
                                "Sync FTP → GitHub: " + mapping.name,
                                { now, _ -> report(chosen.size + now, chosen.size * 2 + 1,
                                    "Коммит в GitHub") },
                                chosen.associate { it.githubPath to it.githubSha }
                            )
                            settings.updateCheckStatus(mapping.id) {
                                it.copy(status = "UNKNOWN", syncedAt = System.currentTimeMillis(),
                                    error = "")
                            }
                            "Передано только отличающихся файлов: " + chosen.size +
                                ". GitHub commit: " + sha
                        }
                    }
                    DEPLOY -> {
                        val repo = inputData.getString(REPO) ?: error("Missing repository")
                        val branch = inputData.getString(BRANCH) ?: "master"
                        val paths = JSONArray(inputData.getString(SELECTED) ?: "[]")
                        val github = GitHubClient(GitHubOAuth.accessToken(applicationContext))
                        val stage = File(temp, "github").apply { mkdirs() }
                        val files = (0 until paths.length()).map { i ->
                            checkpoint()
                            val relative = paths.getString(i)
                            val local = File(stage, "github-$i")
                            github.download(repo, branch, relative, local)
                            report(i + 1, paths.length() * 2, "Получение из GitHub")
                            relative to local
                        }
                        check(files.isNotEmpty()) { "No files selected" }
                        FileOperations.uploadWithBackup(applicationContext, session, server.root, files,
                            temp, { a, b -> report(paths.length() + a, paths.length() + b, "Деплой") }, ::checkpoint)?.toString()
                            ?: "Deployed ${files.size} new files"
                    }
                    IMPORT -> {
                        val repo = inputData.getString(REPO) ?: error("Missing repository")
                        val branch = inputData.getString(BRANCH) ?: "master"
                        val ignore = inputData.getString(IGNORE) ?: settings.repo().ignore
                        val scan = FileOperations.scan(session, path, ImportFilter(ignore), ::checkpoint)
                        val github = GitHubClient(GitHubOAuth.accessToken(applicationContext))
                        val stage = File(temp, "import").apply { mkdirs() }
                        val files = scan.files.mapIndexed { i, remote ->
                            checkpoint()
                            require(remote.size <= 20L * 1024 * 1024) { "File exceeds 20 MiB GitHub limit: ${remote.relative}" }
                            val local = File(stage, "source-$i")
                            session.download(remote.path, local)
                            report(i + 1, scan.files.size * 2 + 1, "Скачивание сайта")
                            remote.relative to local
                        }
                        val sha = github.commitImport(repo, branch, files, ignore) { now, _ ->
                            report(scan.files.size + now, scan.files.size * 2 + 1, "Коммит в GitHub")
                        }
                        "Commit: $sha"
                    }
                    else -> error("Unknown operation")
                }
                Result.success(workDataOf("result" to result, "label" to "Готово"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to (e.message ?: e.javaClass.simpleName)))
        } finally {
            inputData.getString(SYNC_PLAN)?.takeIf { Regex("[0-9a-fA-F-]{36}").matches(it) }?.let {
                File(applicationContext.filesDir, "sync-plans/" + it + ".json").delete()
            }
            temp.deleteRecursively()
        }
    }
}
