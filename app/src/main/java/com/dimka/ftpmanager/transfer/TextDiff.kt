package com.dimka.ftpmanager.transfer

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Small readable unified line diff. Old = FTP, new = GitHub.
 * The same view is valid in both directions; transfer direction is chosen separately.
 */
object TextDiff {
    data class Line(val marker: String, val content: String, val oldLine: Int, val newLine: Int)
    data class Preview(val lines: List<Line>, val explanation: String = "", val added: Int = 0,
        val removed: Int = 0, val note: String = "")

    const val MAX_DIFF_SIZE = 256 * 1024
    const val MAX_DIFF_LINES = 1400

    private fun read(file: File?): List<String> {
        if (file == null) return emptyList()
        require(file.length() <= MAX_DIFF_SIZE) { "Файл больше 256 КиБ. Доступно только сравнение хешей." }
        val data = file.readBytes()
        require(data.none { it == 0.toByte() }) { "Бинарный файл: построчный diff недоступен." }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()
        // GitHub and FTP may store identical code with different line endings.
        // Compare logical lines, but keep exact byte hashes for sync conflict checks.
        val lines = LineEndingEquivalence.normalizeLineEndings(text).split("\n")
        require(lines.size <= MAX_DIFF_LINES) { "Слишком много строк для diff (лимит 1400)." }
        return lines
    }

    fun preview(github: File?, ftp: File?): Preview {
        val old = read(ftp)
        val newer = read(github)
        val compared = compare(old, newer)
        // Any raw-byte difference that disappears after EOL normalization
        // is ignored by sync comparison, but transferred files still use exact hashes.
        if (ftp != null && github != null && compared.added == 0 && compared.removed == 0 &&
            !ftp.readBytes().contentEquals(github.readBytes())) {
            return compared.copy(
                note = "Текст совпадает построчно: отличаются только переносы строк " +
                    "(LF/CRLF/CRCRLF). В Diff Check такие файлы считаются одинаковыми."
            )
        }
        return compared
    }

    fun compare(old: List<String>, newer: List<String>): Preview {
        require(old.size <= MAX_DIFF_LINES && newer.size <= MAX_DIFF_LINES) { "Diff is too large" }
        val n = old.size
        val m = newer.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (old[i] == newer[j]) 1 + lcs[i + 1][j + 1]
                    else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val all = ArrayList<Line>()
        var i = 0
        var j = 0
        var added = 0
        var removed = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && old[i] == newer[j] -> {
                    all.add(Line(" ", old[i], i + 1, j + 1)); i++; j++
                }
                j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                    all.add(Line("+", newer[j], 0, j + 1)); added++; j++
                }
                else -> {
                    all.add(Line("-", old[i], i + 1, 0)); removed++; i++
                }
            }
        }
        // Keep only context around real edits, with an explicit collapsed hunk separator.
        val visible = BooleanArray(all.size)
        all.forEachIndexed { index, line ->
            if (line.marker != " ") {
                for (pos in (index - 3).coerceAtLeast(0)..(index + 3).coerceAtMost(all.lastIndex)) {
                    visible[pos] = true
                }
            }
        }
        val shown = ArrayList<Line>()
        var gap = false
        for (index in all.indices) {
            if (visible[index]) {
                if (gap) shown.add(Line("…", "Неизменённые строки скрыты", 0, 0))
                shown.add(all[index])
                gap = false
            } else gap = true
        }
        if (shown.isEmpty() && all.isNotEmpty()) {
            shown.add(Line(" ", "Файлы совпадают", 0, 0))
        }
        return Preview(shown, added = added, removed = removed)
    }
}
