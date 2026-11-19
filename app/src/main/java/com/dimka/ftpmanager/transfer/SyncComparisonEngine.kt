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
            if (relative != null && (!mapping.githubDirectory || mapping.scope.includesFile(relative)))
                gitFiles[if (mapping.githubDirectory) relative else "@file"] = entry
        }
        val ftpFiles = linkedMapOf<String, FileOperations.RemoteFile>()
        RemoteClient.connect(context, server).use { ftp ->
            if (mapping.ftpDirectory) {
                FileOperations.scan(ftp, mapping.ftpPath,
                    checkpoint = checkpoint, scope = mapping.scope).files.forEach {
                    ftpFiles[it.relative] = it
                }
            } else {
                val parent = mapping.ftpPath.substringBeforeLast('/', "/").ifBlank { "/" }
                val entry = ftp.list(parent).firstOrNull {
                    !it.directory && it.path == mapping.ftpPath
                }
                if (entry != null) {
                    ftpFiles["@file"] = FileOperations.RemoteFile(entry.path, entry.name, entry.size)
                }
            }
            val keys = if (mapping.githubDirectory)
                mapping.compareMode.paths(gitFiles.keys.toSet(), ftpFiles.keys.toSet())
            else (gitFiles.keys + ftpFiles.keys).distinct().sorted()
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
                    var sameExceptLineEndings = false
                    var actualFtpSize = remote?.size ?: -1L
                    val tooLarge = (github != null && github.size > MAX_FILE_BYTES) ||
                        (remote != null && remote.size > MAX_FILE_BYTES)
                    if (remote != null && !tooLarge) {
                        val local = File(temp, "remote")
                        try {
                            ftp.download(remote.path, local)
                            actualFtpSize = local.length()
                            if (actualFtpSize <= MAX_FILE_BYTES) {
                                ftpSha = gitBlobHash(local)
                                if (github != null && github.sha.isNotBlank() &&
                                    ftpSha != github.sha) {
                                    // Prefer hashing canonical LF / CRLF locally: zero extra
                                    // GitHub downloads for the common FTP-LF/GitHub-CRLF case.
                                    sameExceptLineEndings =
                                        LineEndingEquivalence.matchesGitBlob(local, github.sha)
                                    if (!sameExceptLineEndings &&
                                        github.size in 0..TextDiff.MAX_DIFF_SIZE.toLong() &&
                                        actualFtpSize <= TextDiff.MAX_DIFF_SIZE &&
                                        LineEndingEquivalence.isUtf8TextWithLineBreak(local)) {
                                        // Mixed newline styles on GitHub: fetch only small
                                        // candidates, and verify the same Git tree snapshot.
                                        val gitLocal = File(temp, "github-eol")
                                        try {
                                            client.download(mapping.repository, mapping.branch,
                                                githubPath, gitLocal)
                                            check(gitBlobHash(gitLocal) == github.sha) {
                                                "GitHub-файл изменился во время проверки: " +
                                                    githubPath + ". Запусти Diff Check ещё раз."
                                            }
                                            sameExceptLineEndings =
                                                LineEndingEquivalence.sameNormalizedContent(
                                                    local, gitLocal)
                                        } finally { gitLocal.delete() }
                                    }
                                }
                            }
                        } finally { local.delete() }
                    }
                    val difference = when {
                        tooLarge || actualFtpSize > MAX_FILE_BYTES -> FileDifference.TOO_LARGE
                        github == null -> FileDifference.ONLY_FTP
                        remote == null -> FileDifference.ONLY_GITHUB
                        github.sha.isNotBlank() && (github.sha == ftpSha ||
                            sameExceptLineEndings) -> FileDifference.IDENTICAL
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
