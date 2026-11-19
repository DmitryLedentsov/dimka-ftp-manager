package com.dimka.ftpmanager.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.dimka.ftpmanager.data.ImportFilter
import com.dimka.ftpmanager.remote.RemoteSession
import com.dimka.ftpmanager.remote.cleanRemotePath
import com.dimka.ftpmanager.remote.joinRemote
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FileOperations {
    data class RemoteFile(val path: String, val relative: String, val size: Long)
    data class Scanned(val files: List<RemoteFile>, val ignored: Int)
    private const val MAX_FILES = 30_000
    private const val MAX_ZIP_UNPACKED = 512L * 1024 * 1024

    /** Ignore directories before traversal, rather than downloading media and filtering later. */
    fun scan(
        session: RemoteSession, root: String, filter: ImportFilter? = null,
        checkpoint: () -> Unit = {}
    ): Scanned {
        val normalizedRoot = cleanRemotePath(root)
        val files = ArrayList<RemoteFile>()
        var skipped = 0
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(normalizedRoot to "")
        var directories = 0
        while (pending.isNotEmpty()) {
            checkpoint()
            val (path, relativeBase) = pending.removeLast()
            if (++directories > MAX_FILES) error("Too many directories")
            for (entry in session.list(path)) {
                checkpoint()
                val relative = if (relativeBase.isEmpty()) entry.name else "$relativeBase/${entry.name}"
                if (filter?.excluded(relative, entry.directory) == true) { skipped++; continue }
                if (entry.directory) pending.add(entry.path to relative)
                else files.add(RemoteFile(entry.path, relative, entry.size))
                if (files.size > MAX_FILES) error("Too many files; choose a smaller directory")
            }
        }
        return Scanned(files.sortedBy { it.relative }, skipped)
    }

    fun filename(name: String): String = name.substringAfterLast('/').replace(Regex("[^a-zA-Zа-яА-Я0-9_. -]"), "_").ifBlank { "export" }

    fun output(context: Context, dir: String, name: String, mime: String, writer: (java.io.OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename(name))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DimkaFtpManager/$dir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create a file in Downloads")
        try {
            context.contentResolver.openOutputStream(uri)?.use(writer) ?: error("Cannot open Downloads file")
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    fun downloadOne(context: Context, session: RemoteSession, remote: String, tempDir: File): Uri {
        val temp = File.createTempFile("ftp-download-", ".tmp", tempDir)
        try {
            session.download(remote, temp)
            val ext = filename(remote)
            return output(context, "Exports", ext, "application/octet-stream") { stream ->
                temp.inputStream().use { it.copyTo(stream) }
            }
        } finally { temp.delete() }
    }

    fun downloadZip(
        context: Context, session: RemoteSession, folder: String, tempDir: File,
        progress: (Int, Int) -> Unit, checkpoint: () -> Unit
    ): Uri {
        val files = scan(session, folder, checkpoint = checkpoint).files
        val root = filename(folder.trimEnd('/'))
        val timestamp = System.currentTimeMillis()
        return output(context, "Exports", "$root-$timestamp.zip", "application/zip") { stream ->
            ZipOutputStream(stream.buffered()).use { zip ->
                val temp = File(tempDir, "entry-${System.nanoTime()}")
                try {
                    files.forEachIndexed { i, entry ->
                        checkpoint()
                        session.download(entry.path, temp)
                        zip.putNextEntry(ZipEntry("$root/${entry.relative}"))
                        temp.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        progress(i + 1, files.size)
                    }
                } finally { temp.delete() }
            }
        }
    }

    /** Only safe relative paths, extracted into the app's private cache. */
    fun unpack(context: Context, uri: Uri, cache: File): List<Pair<String, File>> {
        cache.mkdirs()
        val files = ArrayList<Pair<String, File>>()
        var unpacked = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val path = entry.name.replace('\\', '/')
                    val parts = path.split('/').filter { it.isNotEmpty() }
                    require(!path.startsWith('/') && !path.contains('\u0000') &&
                        parts.isNotEmpty() && parts.none { it == ".." || it == "." } &&
                        !Regex("^[A-Za-z]:").containsMatchIn(path)) { "Unsafe ZIP path: $path" }
                    if (!entry.isDirectory) {
                        check(files.size < MAX_FILES) { "Too many ZIP entries" }
                        val local = File(cache, "entry-${files.size}")
                        FileOutputStream(local).use { out ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n == -1) break
                                unpacked += n
                                check(unpacked <= MAX_ZIP_UNPACKED) { "ZIP expanded size exceeds 512 MiB" }
                                out.write(buffer, 0, n)
                            }
                        }
                        files.add(parts.joinToString("/") to local)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Cannot open selected ZIP")
        check(files.isNotEmpty()) { "ZIP contains no files" }
        return files
    }

    /** Only strip the one wrapping folder when it equals the destination name. */
    fun mapZipPaths(files: List<Pair<String, File>>, remoteFolder: String): List<Pair<String, File>> {
        val name = filename(remoteFolder.trimEnd('/'))
        val commonTop = files.map { it.first.substringBefore('/') }.distinct().singleOrNull()
        val strip = commonTop == name && files.all { it.first.contains('/') }
        val mapped = files.map { (path, file) ->
            val relative = if (strip) path.substringAfter('/') else path
            relative to file
        }
        check(mapped.map { it.first }.distinct().size == mapped.size) { "Duplicate paths after ZIP mapping" }
        return mapped
    }

    /** Backup existing files before writing any replacements. No destructive sync/delete. */
    fun uploadWithBackup(
        context: Context, session: RemoteSession, destination: String,
        files: List<Pair<String, File>>, tempDir: File,
        progress: (Int, Int) -> Unit, checkpoint: () -> Unit
    ): Uri? {
        val target = cleanRemotePath(destination)
        val backups = ArrayList<Pair<String, String>>()
        files.forEach { (relative, _) ->
            checkpoint()
            val segments = relative.split('/')
            require(segments.isNotEmpty() && segments.none { it.isBlank() || it == "." || it == ".." })
            val remote = segments.fold(target) { path, part -> joinRemote(path, part) }
            if (session.existsFile(remote)) backups.add(relative to remote)
        }
        val backup = if (backups.isNotEmpty()) {
            output(context, "Backups", "backup-${System.currentTimeMillis()}.zip", "application/zip") { stream ->
                ZipOutputStream(stream.buffered()).use { zip ->
                    val temp = File(tempDir, "old-${System.nanoTime()}")
                    try {
                        for ((relative, remote) in backups) {
                            checkpoint()
                            session.download(remote, temp)
                            zip.putNextEntry(ZipEntry(relative))
                            temp.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    } finally { temp.delete() }
                }
            }
        } else null
        files.forEachIndexed { index, (relative, file) ->
            checkpoint()
            val remote = relative.split('/').fold(target) { path, part -> joinRemote(path, part) }
            session.upload(file, remote)
            progress(index + 1, files.size)
        }
        return backup
    }

    fun copyUri(context: Context, uri: Uri, output: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            output.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("Cannot access selected local file")
    }
}
