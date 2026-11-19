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

    @Test fun binaryFilesCannotBeRenderedAsText() {
        val file = File.createTempFile("dimka-bin-", ".bin")
        try {
            file.writeBytes(byteArrayOf(0, 1, 2))
            assertTrue(runCatching { TextDiff.preview(file, file) }.isFailure)
        } finally { file.delete() }
    }
}
