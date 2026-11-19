package com.dimka.ftpmanager

import com.dimka.ftpmanager.transfer.SyncComparisonEngine
import com.dimka.ftpmanager.transfer.TextDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiffCheckTest {
    @Test fun githubBlobShaMatchesGitSpecification() {
        val file = File.createTempFile("dimka-test-", ".txt")
        try {
            file.writeText("hello\n")
            assertEquals("ce013625030ba8dba906f756967f9e9ca394464a",
                SyncComparisonEngine.gitBlobHash(file))
            file.writeText("changed\n")
            assertTrue(SyncComparisonEngine.gitBlobHash(file) !=
                "ce013625030ba8dba906f756967f9e9ca394464a")
        } finally { file.delete() }
    }

    @Test fun lineDiffReportsOnlyActualEdits() {
        val diff = TextDiff.compare(listOf("header", "before", "footer"),
            listOf("header", "after", "footer"))
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertTrue(diff.lines.any { it.marker == "-" && it.content == "before" })
        assertTrue(diff.lines.any { it.marker == "+" && it.content == "after" })
    }

    @Test fun sameLinesProduceNoChanges() {
        val diff = TextDiff.compare(listOf("unchanged", "ok"), listOf("unchanged", "ok"))
        assertEquals(0, diff.added)
        assertEquals(0, diff.removed)
    }

    @Test fun crlfVersusLfDoesNotProduceWholeFileEdits() {
        val git = File.createTempFile("dimka-crlf-git-", ".php")
        val ftp = File.createTempFile("dimka-crlf-ftp-", ".php")
        try {
            val rows = (1..481).map { "<?php // line $it" }
            git.writeText(rows.joinToString("\r\n") + "\r\n")
            ftp.writeText(rows.joinToString("\n") + "\n")
            val result = TextDiff.preview(git, ftp)
            assertEquals(0, result.added)
            assertEquals(0, result.removed)
            assertTrue(result.note.contains("CRLF/LF"))
            assertTrue(SyncComparisonEngine.gitBlobHash(git) !=
                SyncComparisonEngine.gitBlobHash(ftp))
        } finally {
            git.delete()
            ftp.delete()
        }
    }

    @Test fun mixedLineEndingsStillHighlightOnlyRealChanges() {
        val git = File.createTempFile("dimka-mixed-git-", ".txt")
        val ftp = File.createTempFile("dimka-mixed-ftp-", ".txt")
        try {
            git.writeText("header\r\nchanged\r\nfooter\r\n")
            ftp.writeText("header\noriginal\nfooter\n")
            val result = TextDiff.preview(git, ftp)
            assertEquals(1, result.added)
            assertEquals(1, result.removed)
            assertTrue(result.lines.any { it.marker == "+" && it.content == "changed" })
            assertTrue(result.lines.any { it.marker == "-" && it.content == "original" })
            assertTrue(result.note.isEmpty())
        } finally {
            git.delete()
            ftp.delete()
        }
    }


    @Test fun eolOnlyDiffShouldBeConsideredIdenticalForSync() {
        val git = File.createTempFile("dimka-eol-git-", ".php")
        val ftp = File.createTempFile("dimka-eol-ftp-", ".php")
        try {
            val rows = (1..481).map { "<?php // code line $it" }
            git.writeText(rows.joinToString("\r\n") + "\r\n")
            ftp.writeText(rows.joinToString("\n") + "\n")
            assertTrue(
                com.dimka.ftpmanager.transfer.LineEndingEquivalence.matchesGitBlob(
                    ftp, SyncComparisonEngine.gitBlobHash(git))
            )
            assertTrue(
                com.dimka.ftpmanager.transfer.LineEndingEquivalence.matchesGitBlob(
                    git, SyncComparisonEngine.gitBlobHash(ftp))
            )
        } finally { git.delete(); ftp.delete() }
    }

    @Test fun nonEolEditsStillAppearAndTrailingNewlineMatters() {
        val git = File.createTempFile("dimka-eol-git-", ".txt")
        val ftp = File.createTempFile("dimka-eol-ftp-", ".txt")
        try {
            git.writeText("header\r\nchanged\r\nfooter\r\n")
            ftp.writeText("header\noriginal\nfooter\n")
            assertTrue(
                !com.dimka.ftpmanager.transfer.LineEndingEquivalence.matchesGitBlob(
                    ftp, SyncComparisonEngine.gitBlobHash(git))
            )
            assertTrue(
                !com.dimka.ftpmanager.transfer.LineEndingEquivalence.sameNormalizedContent(
                    ftp, git)
            )
            git.writeText("header\r\noriginal\r\nfooter\r\n")
            assertTrue(
                com.dimka.ftpmanager.transfer.LineEndingEquivalence.matchesGitBlob(
                    ftp, SyncComparisonEngine.gitBlobHash(git))
            )
            git.writeText("header\r\noriginal\r\nfooter")
            assertTrue(
                !com.dimka.ftpmanager.transfer.LineEndingEquivalence.matchesGitBlob(
                    ftp, SyncComparisonEngine.gitBlobHash(git))
            )
        } finally { git.delete(); ftp.delete() }
    }

    @Test fun mixedGitLineEndingsUseContentFallbackAndDontHideWhitespaceChanges() {
        val git = File.createTempFile("dimka-mixed-git-", ".txt")
        val ftp = File.createTempFile("dimka-mixed-ftp-", ".txt")
        try {
            git.writeText("header\r\nbody\nfooter\r\n")
            ftp.writeText("header\nbody\nfooter\n")
            assertTrue(
                !com.dimka.ftpmanager.transfer.LineEndingEquivalence.matchesGitBlob(
                    ftp, SyncComparisonEngine.gitBlobHash(git))
            )
            assertTrue(
                com.dimka.ftpmanager.transfer.LineEndingEquivalence.sameNormalizedContent(
                    ftp, git)
            )
            git.writeText("header\r\nbody \nfooter\r\n")
            assertTrue(
                !com.dimka.ftpmanager.transfer.LineEndingEquivalence.sameNormalizedContent(
                    ftp, git)
            )
        } finally { git.delete(); ftp.delete() }
    }

    @Test fun binaryAndInvalidUtf8AreNotNormalized() {
        val git = File.createTempFile("dimka-bin-git-", ".bin")
        val ftp = File.createTempFile("dimka-bin-ftp-", ".bin")
        try {
            git.writeBytes(byteArrayOf(1, 0, 10, 2))
            ftp.writeBytes(byteArrayOf(1, 0, 13, 10, 2))
            assertTrue(!com.dimka.ftpmanager.transfer.LineEndingEquivalence.matchesGitBlob(
                ftp, SyncComparisonEngine.gitBlobHash(git)))
            ftp.writeBytes(byteArrayOf(0xC3.toByte(), 0x28, 10))
            assertTrue(!com.dimka.ftpmanager.transfer.LineEndingEquivalence.matchesGitBlob(
                ftp, SyncComparisonEngine.gitBlobHash(git)))
        } finally { git.delete(); ftp.delete() }
    }

    @Test fun binaryFilesCannotBeRenderedAsText() {
        val file = File.createTempFile("dimka-bin-", ".bin")
        try {
            file.writeBytes(byteArrayOf(0, 1, 2))
            assertTrue(runCatching { TextDiff.preview(file, file) }.isFailure)
        } finally { file.delete() }
    }
}
