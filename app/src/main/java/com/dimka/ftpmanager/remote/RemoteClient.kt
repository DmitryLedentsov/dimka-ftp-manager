package com.dimka.ftpmanager.remote

import android.content.Context
import android.util.Base64
import com.dimka.ftpmanager.data.RemoteEntry
import com.dimka.ftpmanager.data.ServerProfile
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

fun cleanRemotePath(path: String): String {
    val segments = path.replace('\\', '/').split('/')
    val stack = mutableListOf<String>()
    for (part in segments) {
        when (part) {
            "", "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else error("Path outside server root")
            else -> stack.add(part)
        }
    }
    return "/" + stack.joinToString("/")
}

fun joinRemote(parent: String, child: String): String {
    require(child.isNotEmpty() && !child.startsWith('/') && child != ".." && '/' !in child && '\\' !in child)
    return cleanRemotePath(parent.trimEnd('/') + "/" + child)
}

fun isWithinRoot(path: String, root: String): Boolean {
    val p = cleanRemotePath(path)
    val r = cleanRemotePath(root)
    return r == "/" || p == r || p.startsWith("$r/")
}

interface RemoteSession : AutoCloseable {
    fun list(path: String): List<RemoteEntry>
    fun download(path: String, local: File)
    fun upload(local: File, path: String)
    fun ensureDirectory(path: String)
    fun existsFile(path: String): Boolean
}

object RemoteClient {
    fun connect(context: Context, server: ServerProfile): RemoteSession = when (server.protocol.uppercase(Locale.US)) {
        "FTP", "FTPS" -> ftp(server)
        "SFTP" -> sftp(context.applicationContext, server)
        else -> error("Unsupported protocol: ${server.protocol}")
    }

    private fun ftp(server: ServerProfile): RemoteSession {
        val client: FTPClient = if (server.protocol.uppercase(Locale.US) == "FTPS") FTPSClient(false).apply {
            setEndpointCheckingEnabled(true)
        } else FTPClient()
        client.controlEncoding = "UTF-8"
        client.connectTimeout = 15_000
        client.defaultTimeout = 20_000
        client.dataTimeout = 60_000
        try {
            client.connect(server.host.trim(), server.port)
            check(FTPReply.isPositiveCompletion(client.replyCode)) { "FTP connection refused: ${client.replyString}" }
            check(client.login(server.username, server.password)) { "Invalid FTP credentials: ${client.replyString}" }
            if (client is FTPSClient) { client.execPBSZ(0); client.execPROT("P") }
            client.enterLocalPassiveMode()
            check(client.setFileType(FTP.BINARY_FILE_TYPE)) { "Could not set binary mode" }
            return object : RemoteSession {
                override fun list(path: String): List<RemoteEntry> {
                    val files = client.listFiles(cleanRemotePath(path))
                    if (!FTPReply.isPositiveCompletion(client.replyCode)) error("FTP list failed: ${client.replyString}")
                    return files.filter { it.name != "." && it.name != ".." && !it.isSymbolicLink }
                        .map { RemoteEntry(it.name, joinRemote(path, it.name), it.isDirectory, it.size) }
                        .sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
                }
                override fun download(path: String, local: File) {
                    local.parentFile?.mkdirs()
                    FileOutputStream(local).use { stream ->
                        check(client.retrieveFile(cleanRemotePath(path), stream)) { "FTP download failed: ${client.replyString}" }
                    }
                }
                override fun upload(local: File, path: String) {
                    ensureDirectory(cleanRemotePath(path).substringBeforeLast('/', "/"))
                    FileInputStream(local).use { stream ->
                        check(client.storeFile(cleanRemotePath(path), stream)) { "FTP upload failed: ${client.replyString}" }
                    }
                }
                override fun ensureDirectory(path: String) {
                    var current = ""
                    for (part in cleanRemotePath(path).split('/').filter { it.isNotBlank() }) {
                        current += "/$part"
                        if (!client.changeWorkingDirectory(current)) {
                            check(client.makeDirectory(current) || client.changeWorkingDirectory(current)) {
                                "FTP mkdir failed $current: ${client.replyString}"
                            }
                        }
                    }
                }
                override fun existsFile(path: String): Boolean {
                    val normalized = cleanRemotePath(path)
                    val parent = normalized.substringBeforeLast('/', "/").ifBlank { "/" }
                    val name = normalized.substringAfterLast('/')
                    return list(parent).any { it.name == name && !it.directory }
                }
                override fun close() {
                    runCatching { client.logout() }
                    runCatching { client.disconnect() }
                }
            }
        } catch (e: Exception) {
            runCatching { if (client.isConnected) client.disconnect() }
            throw e
        }
    }

    private fun sftp(context: Context, server: ServerProfile): RemoteSession {
        val client = SSHClient()
        // TOFU: pin the first observed public key; reject subsequent key changes.
        val hostId = "${server.host}:${server.port}"
        val fingerprints = context.getSharedPreferences("sftp_fingerprints", Context.MODE_PRIVATE)
        client.addHostKeyVerifier { _, _, key ->
            val fingerprint = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key.encoded), Base64.NO_WRAP
            )
            val pinned = fingerprints.getString(hostId, null)
            when {
                pinned == null -> fingerprints.edit().putString(hostId, fingerprint).commit()
                else -> pinned == fingerprint
            }
        }
        try {
            client.connect(server.host.trim(), server.port)
            client.authPassword(server.username, server.password)
            val sftp = client.newSFTPClient()
            return object : RemoteSession {
                override fun list(path: String): List<RemoteEntry> = sftp.ls(cleanRemotePath(path))
                    .filter { it.name != "." && it.name != ".." && (it.isDirectory || it.isRegularFile) }
                    .map { RemoteEntry(it.name, joinRemote(path, it.name), it.isDirectory, it.attributes.size) }
                    .sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
                override fun download(path: String, local: File) {
                    local.parentFile?.mkdirs()
                    sftp.get(cleanRemotePath(path), local.absolutePath)
                }
                override fun upload(local: File, path: String) {
                    ensureDirectory(cleanRemotePath(path).substringBeforeLast('/', "/"))
                    sftp.put(local.absolutePath, cleanRemotePath(path))
                }
                override fun ensureDirectory(path: String) {
                    var current = ""
                    for (part in cleanRemotePath(path).split('/').filter { it.isNotBlank() }) {
                        current += "/$part"
                        if (runCatching { sftp.stat(current) }.isFailure) sftp.mkdir(current)
                    }
                }
                override fun existsFile(path: String): Boolean = runCatching {
                    val p = cleanRemotePath(path)
                    val parent = p.substringBeforeLast('/', "/").ifBlank { "/" }
                    list(parent).any { it.name == p.substringAfterLast('/') && !it.directory }
                }.getOrDefault(false)
                override fun close() {
                    runCatching { sftp.close() }
                    runCatching { client.disconnect() }
                    runCatching { client.close() }
                }
            }
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            runCatching { client.close() }
            throw e
        }
    }
}
