package com.dimka.ftpmanager

import com.dimka.ftpmanager.data.SyncMapping
import com.dimka.ftpmanager.data.SyncPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMappingTest {
    @Test fun directorySelectionPreservesRelativeStructure() {
        assertEquals("imshop.php", SyncPaths.relative("mg-plugins/imshop/imshop.php", "mg-plugins/imshop", true))
        assertEquals("nested/config.php", SyncPaths.relative("mg-plugins/imshop/nested/config.php", "mg-plugins/imshop", true))
        assertNull(SyncPaths.relative("mg-plugins/imshop-other/a.php", "mg-plugins/imshop", true))
        assertNull(SyncPaths.relative("mg-plugins/imshop", "mg-plugins/imshop", true))
        assertEquals("mg-plugins/a.php", SyncPaths.relative("mg-plugins/a.php", "", true))
    }

    @Test fun oneFileMapsExactlyToOtherFileRegardlessOfName() {
        val mapping = SyncMapping(
            repository = "someone/emka", branch = "master",
            githubPath = "src/imshop.php", githubDirectory = false, githubPicked = true,
            serverId = "server1", ftpPath = "/htdocs/mg-plugins/imshop/imshop-new.php",
            ftpDirectory = false, ftpPicked = true
        )
        assertTrue(mapping.ready)
        assertEquals("imshop.php", SyncPaths.relative("src/imshop.php", mapping.githubPath, false))
        assertNull(SyncPaths.relative("src/imshop-old.php", mapping.githubPath, false))
        assertEquals("/htdocs/mg-plugins/imshop/imshop-new.php",
            SyncPaths.ftpDestination(mapping, "imshop.php"))
        assertEquals("src/imshop.php", SyncPaths.githubDestination(mapping, "imshop-new.php"))
    }

    @Test fun folderMappingsAllowDifferentPrefixAndNeverEscapeRoot() {
        val mapping = SyncMapping(
            repository = "someone/emka", branch = "main",
            githubPath = "mg-templates/emka", githubPicked = true,
            serverId = "server1", ftpPath = "/public_html/templates/live", ftpPicked = true
        )
        assertTrue(mapping.ready)
        assertEquals("/public_html/templates/live/css/main.css",
            SyncPaths.ftpDestination(mapping, "css/main.css"))
        assertEquals("mg-templates/emka/css/main.css",
            SyncPaths.githubDestination(mapping, "css/main.css"))
        assertNull(SyncPaths.relative("other/../password", "", true))
        assertFalse(mapping.copy(ftpDirectory = false).ready)
        assertFalse(mapping.copy(githubPicked = false).ready)
    }
}
