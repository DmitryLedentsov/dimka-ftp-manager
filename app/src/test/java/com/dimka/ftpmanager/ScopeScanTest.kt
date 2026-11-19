package com.dimka.ftpmanager

import com.dimka.ftpmanager.data.ComparisonScope
import com.dimka.ftpmanager.data.RemoteEntry
import com.dimka.ftpmanager.remote.RemoteSession
import com.dimka.ftpmanager.transfer.FileOperations
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeScanTest {
    @Test fun excludedDirectoriesAreNeverListedAndNestedIncludesStillWork() {
        val visited = mutableListOf<String>()
        val remote = object : RemoteSession {
            override fun list(path: String): List<RemoteEntry> {
                visited.add(path)
                fun dir(name: String) = RemoteEntry(name, "$path/$name", true)
                fun file(name: String) = RemoteEntry(name, "$path/$name", false, 128)
                return when (path) {
                    "/site" -> listOf(dir("mg-plugins"), dir("mg-templates"),
                        dir("uploads"), file("index.php"))
                    "/site/mg-plugins" -> listOf(dir("imshop"), dir("other"))
                    "/site/mg-plugins/imshop" -> listOf(file("imshop.php"))
                    "/site/mg-plugins/other" -> listOf(file("unused.php"))
                    "/site/mg-templates" -> listOf(file("template.php"))
                    "/site/uploads" -> listOf(file("photo.webp"))
                    else -> emptyList()
                }
            }
            override fun download(path: String, local: File) = error("No download expected")
            override fun upload(local: File, path: String) = error("No upload expected")
            override fun ensureDirectory(path: String) = Unit
            override fun existsFile(path: String) = false
            override fun close() = Unit
        }
        val selected = ComparisonScope().withSelection("", true, false)
            .withSelection("mg-plugins/imshop", true, true)
            .withSelection("index.php", false, true)
        val result = FileOperations.scan(remote, "/site", scope = selected)
        assertEquals(listOf("index.php", "mg-plugins/imshop/imshop.php"),
            result.files.map { it.relative })
        assertTrue(visited.contains("/site"))
        assertTrue(visited.contains("/site/mg-plugins"))
        assertTrue(visited.contains("/site/mg-plugins/imshop"))
        assertFalse(visited.contains("/site/mg-plugins/other"))
        assertFalse(visited.contains("/site/mg-templates"))
        assertFalse(visited.contains("/site/uploads"))
    }
}
