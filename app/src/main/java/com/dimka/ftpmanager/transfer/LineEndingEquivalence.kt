package com.dimka.ftpmanager.transfer

import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Treat CRLF, LF and lone CR as equivalent *only for valid UTF-8 text*.
 * Other whitespace, encodings, BOMs, trailing newlines and actual content remain significant.
 *
 * Fast path computes both canonical LF and CRLF Git blob hashes from the FTP file
 * using constant memory. No GitHub download is required for typical LF-vs-CRLF changes.
 */
object LineEndingEquivalence {
    private const val LF: Int = 10
    private const val CR: Int = 13

    /** Do not classify arbitrary binary data as text. */
    fun isUtf8TextWithLineBreak(file: File): Boolean {
        if (!file.isFile || file.length() > SyncComparisonEngine.MAX_FILE_BYTES) return false
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            var hasNewline = false
            InputStreamReader(file.inputStream().buffered(), decoder).use { reader ->
                val chars = CharArray(8192)
                while (true) {
                    val n = reader.read(chars)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val c = chars[i]
                        if (c == '\r' || c == '\n') hasNewline = true
                        if (c.code < 32 && c != '\r' && c != '\n' &&
                            c != '\t' && c != '\u000c') return false
                        if (c == '\u007f') return false
                    }
                }
            }
            hasNewline
        } catch (_: Exception) {
            false
        }
    }

    private fun newHash(size: Long): MessageDigest = MessageDigest.getInstance("SHA-1").apply {
        update(("blob " + size + "\u0000").toByteArray(Charsets.UTF_8))
    }

    private fun hex(hash: MessageDigest): String =
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Calculate two Git blob hashes as though all existing EOLs were LF or CRLF.
     * Counting takes one pass; hashing both variants takes a second pass.
     * No whole-file allocations or temporary files.
     */
    fun matchesGitBlob(ftpFile: File, githubBlobSha: String): Boolean {
        if (githubBlobSha.isBlank() || !isUtf8TextWithLineBreak(ftpFile)) return false

        var breakCount = 0L
        var crlfCount = 0L
        var afterCR = false
        ftpFile.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                for (i in 0 until n) {
                    when (buffer[i].toInt() and 0xff) {
                        CR -> { breakCount++; afterCR = true }
                        LF -> {
                            if (afterCR) crlfCount++ else breakCount++
                            afterCR = false
                        }
                        else -> afterCR = false
                    }
                }
            }
        }
        if (breakCount == 0L) return false
        val lfSize = ftpFile.length() - crlfCount
        val lfHash = newHash(lfSize)
        val crlfHash = newHash(lfSize + breakCount)
        val lfByte = byteArrayOf(LF.toByte())
        val crlfBytes = byteArrayOf(CR.toByte(), LF.toByte())
        afterCR = false

        ftpFile.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                var segmentStart = 0
                for (i in 0 until n) {
                    val byte = buffer[i].toInt() and 0xff
                    if (byte == CR || byte == LF) {
                        if (i > segmentStart) {
                            lfHash.update(buffer, segmentStart, i - segmentStart)
                            crlfHash.update(buffer, segmentStart, i - segmentStart)
                        }
                        if (byte == CR || !afterCR) {
                            lfHash.update(lfByte)
                            crlfHash.update(crlfBytes)
                        }
                        segmentStart = i + 1
                        afterCR = byte == CR
                    } else {
                        afterCR = false
                    }
                }
                if (n > segmentStart) {
                    lfHash.update(buffer, segmentStart, n - segmentStart)
                    crlfHash.update(buffer, segmentStart, n - segmentStart)
                }
            }
        }
        return hex(lfHash).equals(githubBlobSha, ignoreCase = true) ||
            hex(crlfHash).equals(githubBlobSha, ignoreCase = true)
    }

    /** Rare fallback for repos containing mixed EOL styles, limited to small text files. */
    fun sameNormalizedContent(first: File, second: File): Boolean {
        fun canonical(file: File): String? {
            if (!isUtf8TextWithLineBreak(file) || file.length() > TextDiff.MAX_DIFF_SIZE) return null
            return try {
                val decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.readBytes())).toString()
                decoded.replace("\r\n", "\n").replace('\r', '\n')
            } catch (_: Exception) { null }
        }
        val left = canonical(first) ?: return false
        return left == canonical(second)
    }
}
