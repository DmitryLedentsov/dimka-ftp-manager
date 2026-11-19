package com.dimka.ftpmanager.transfer

import android.content.Context
import com.dimka.ftpmanager.data.ComparedFile
import com.dimka.ftpmanager.data.FileDifference
import com.dimka.ftpmanager.data.MappingComparison
import com.dimka.ftpmanager.data.SyncMapping
import com.dimka.ftpmanager.data.SyncPaths
import com.dimka.ftpmanager.data.ServerProfile
import com.dimka.ftpmanager.github.GitHubClient
import com.dimka.ftpmanager.github.GitHubOAuth
import com.dimka.ftpmanager.remote.RemoteClient
import com.dimka.ftpmanager.remote.isWithinRoot
import java.io.File
import java.security.MessageDigest

/** Compare real contents, not just filenames, timestamps or reported sizes. */
object SyncComparisonEngine {
    const val MAX_FILE_BYTES = 20L * 1024 * 1024

    fun gitBlobHash(file: File): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(("blob " + file.length() + "\u0000").toByteArray(Charsets.UTF_8))
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n > 0) sha1.update(buffer, 0, n)
            }
        }
        return sha1.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun compare(
        context: Context,
        mapping: SyncMapping,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        checkpoint: () -> Unit = {}
    ): MappingComparison {
        require(mapping.ready) { "Сначала выбери оба пути" }
        val server = com.dimka.ftpmanager.data.SettingsStore(context).servers()
            .firstOrNull { it.id == mapping.serverId } ?: error("FTP-хост больше не существует")
        return compare(context, mapping, server, onProgress, checkpoint)
    }

    fun compare(
        context: Context, mapping: SyncMapping, server: ServerProfile,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        checkpoint: () -> Unit = {}
    ): MappingComparison {
        require(mapping.ready && mapping.serverId == server.id) { "Некорректное соответствие" }
        require(isWithinRoot(mapping.ftpPath, server.root)) { "FTP-путь за пределами сайта" }
        val client = GitHubClient(GitHubOAuth.accessToken(context))
        val gitFiles = linkedMapOf<String, com.dimka.ftpmanager.data.RepoEntry>()
        for (entry in client.tree(mapping.repository, mapping.branch)) {
            val relative = SyncPaths.relative(entry.path, mapping.githubPath, mapping.githubDirectory)
            if (relative != null) gitFiles[if (mapping.githubDirectory) relative else "@file"] = entry
        }
        val ftpFiles = linkedMapOf<String, FileOperations.RemoteFile>()
        RemoteClient.connect(context, server).use { ftp ->
            if (mapping.ftpDirectory) {
                FileOperations.scan(ftp, mapping.ftpPath, checkpoint = checkpoint).files.forEach {
                    ftpFiles[it.relative] = it
                }
            } else if (ftp.existsFile(mapping.ftpPath)) {
                ftpFiles["@file"] = FileOperations.RemoteFile(mapping.ftpPath,
                    mapping.ftpPath.substringAfterLast('/'), 0L)
            }
            val keys = (gitFiles.keys + ftpFiles.keys).distinct().sorted()
            check(keys.size <= SyncPaths.MAX_FILES) {
                "В папках " + keys.size + " файлов. Выбери папку поменьше (лимит " + SyncPaths.MAX_FILES + ")."
            }
            val temp = File(context.cacheDir, "sync-check-" + java.util.UUID.randomUUID())
            temp.mkdirs()
            try {
                val result = ArrayList<ComparedFile>(keys.size)
                keys.forEachIndexed { index, key ->
                    checkpoint()
                    val github = gitFiles[key]
                    val remote = ftpFiles[key]
                    val relative = if (mapping.githubDirectory) key
                        else mapping.githubPath.substringAfterLast('/').ifBlank { mapping.ftpPath.substringAfterLast('/') }
                    val remotePath = if (mapping.ftpDirectory)
                        SyncPaths.ftpDestination(mapping, relative) else mapping.ftpPath
                    val githubPath = if (mapping.githubDirectory)
                        SyncPaths.githubDestination(mapping, relative) else mapping.githubPath
                    var ftpSha = ""
                    var actualFtpSize = remote?.size ?: -1L
                    val tooLarge = (github != null && github.size > MAX_FILE_BYTES) ||
                        (remote != null && remote.size > MAX_FILE_BYTES)
                    if (remote != null && !tooLarge) {
                        val local = File(temp, "remote")
                        try {
                            ftp.download(remote.path, local)
                            actualFtpSize = local.length()
                            if (actualFtpSize <= MAX_FILE_BYTES) ftpSha = gitBlobHash(local)
                        } finally { local.delete() }
                    }
                    val difference = when {
                        tooLarge || actualFtpSize > MAX_FILE_BYTES -> FileDifference.TOO_LARGE
                        github == null -> FileDifference.ONLY_FTP
                        remote == null -> FileDifference.ONLY_GITHUB
                        github.sha.isNotBlank() && github.sha == ftpSha -> FileDifference.IDENTICAL
                        else -> FileDifference.CHANGED
                    }
                    result.add(ComparedFile(relative, githubPath, remotePath, difference,
                        github?.sha.orEmpty(), ftpSha, github?.size ?: -1, actualFtpSize))
                    onProgress(index + 1, keys.size)
                }
                return MappingComparison(mapping, System.currentTimeMillis(), result)
            } finally {
                temp.deleteRecursively()
            }
        }
    }

    /** Diff of one selected file; never decode binary data as text. */
    fun diff(context: Context, comparison: MappingComparison, file: ComparedFile): TextDiff.Preview {
        val server = com.dimka.ftpmanager.data.SettingsStore(context).servers()
            .firstOrNull { it.id == comparison.mapping.serverId } ?: error("FTP-хост не найден")
        val folder = File(context.cacheDir, "sync-diff-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        try {
            val github = if (file.githubSha.isNotBlank()) {
                File(folder, "github").also {
                    GitHubClient(GitHubOAuth.accessToken(context)).download(
                        comparison.mapping.repository, comparison.mapping.branch, file.githubPath, it)
                }
            } else null
            val remote = if (file.ftpSha.isNotBlank()) {
                File(folder, "ftp").also {
                    RemoteClient.connect(context, server).use { ftp -> ftp.download(file.ftpPath, it) }
                }
            } else null
            if (github != null) {
                check(gitBlobHash(github) == file.githubSha) {
                    "GitHub-файл изменился после проверки. Нажми Diff Check заново."
                }
            }
            if (remote != null) {
                check(gitBlobHash(remote) == file.ftpSha) {
                    "FTP-файл изменился после проверки. Нажми Diff Check заново."
                }
            }
            return TextDiff.preview(github, remote)
        } finally {
            folder.deleteRecursively()
        }
    }
}
