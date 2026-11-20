package com.dimka.ftpmanager.transfer

import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Valid UTF-8 text may differ only in EOL encoding: LF, CRLF and malformed CRCRLF.
 * Repeated CRs immediately followed by LF constitute ONE logical newline.
 * Other bytes (including spaces, BOM, and terminal newline presence) are significant.
 */
object LineEndingEquivalence {
    private const val LF = 10
    private const val CR = 13
    private val crRunBeforeLf = Regex("\\r+\\n")

    fun normalizeLineEndings(text: String): String =
        text.replace(crRunBeforeLf, "\n").replace('\r', '\n')

    /** Reject binary and malformed UTF-8 before declaring line-ending equivalence. */
    fun isUtf8TextWithLineBreak(file: File): Boolean {
        if (!file.isFile || file.length() > SyncComparisonEngine.MAX_FILE_BYTES) return false
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            var hasBreak = false
            InputStreamReader(file.inputStream().buffered(), decoder).use { reader ->
                val chars = CharArray(8192)
                while (true) {
                    val n = reader.read(chars)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val c = chars[i]
                        if (c == '\r' || c == '\n') hasBreak = true
                        if ((c.code < 32 && c != '\r' && c != '\n' &&
                            c != '\t' && c != '\u000c') || c == '\u007f') return false
                    }
                }
            }
            hasBreak
        } catch (_: Exception) { false }
    }

    private fun hashWithBlobHeader(size: Long): MessageDigest =
        MessageDigest.getInstance("SHA-1").apply {
            update(("blob " + size + "\u0000").toByteArray(Charsets.UTF_8))
        }

    private fun toHex(hash: MessageDigest) =
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Visits unchanged raw byte ranges and logical EOL tokens.
     * Pending CRs cross read-buffer boundaries safely.
     */
    private fun scan(
        file: File,
        onData: (ByteArray, Int, Int) -> Unit,
        onBreak: () -> Unit
    ) {
        var pendingCR = 0
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                var start = 0
                for (i in 0 until n) {
                    val b = bytes[i].toInt() and 0xff
                    if (b == CR || b == LF) {
                        if (i > start) onData(bytes, start, i - start)
                        if (b == CR) pendingCR++
                        else {
                            // LF and any immediately preceding CR run form one newline.
                            pendingCR = 0
                            onBreak()
                        }
                        start = i + 1
                    } else if (pendingCR > 0) {
                        // CR not followed by LF: each such CR is a newline.
                        repeat(pendingCR) { onBreak() }
                        pendingCR = 0
                    }
                }
                if (start < n) onData(bytes, start, n - start)
            }
        }
        repeat(pendingCR) { onBreak() }
    }

    /**
     * Recompute Git blob hashes for a hypothetical LF/CRLF/CRCRLF version of FTP
     * using two passes and constant memory, without downloading the GitHub file.
     */
    fun matchesGitBlob(ftpFile: File, githubBlobSha: String): Boolean {
        if (githubBlobSha.isBlank() || !isUtf8TextWithLineBreak(ftpFile)) return false
        var otherBytes = 0L
        var breaks = 0L
        scan(ftpFile, { _, _, count -> otherBytes += count }, { breaks++ })
        if (breaks == 0L) return false
        val variants = arrayOf(
            byteArrayOf(LF.toByte()),
            byteArrayOf(CR.toByte(), LF.toByte()),
            byteArrayOf(CR.toByte(), CR.toByte(), LF.toByte())
        )
        val hashes = variants.map { hashWithBlobHeader(otherBytes + breaks * it.size) }
        scan(ftpFile,
            { buffer, offset, count -> hashes.forEach { it.update(buffer, offset, count) } },
            { variants.indices.forEach { i -> hashes[i].update(variants[i]) } }
        )
        return hashes.any { toHex(it).equals(githubBlobSha, ignoreCase = true) }
    }

    /** For rare mixed-line-ending GitHub content; small files, verified separately. */
    fun sameNormalizedContent(first: File, second: File): Boolean {
        fun readCanonical(file: File): String? {
            if (!isUtf8TextWithLineBreak(file) || file.length() > TextDiff.MAX_DIFF_SIZE)
                return null
            return try {
                val decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.readBytes())).toString()
                normalizeLineEndings(decoded)
            } catch (_: Exception) { null }
        }
        val left = readCanonical(first) ?: return false
        return left == readCanonical(second)
    }
}
