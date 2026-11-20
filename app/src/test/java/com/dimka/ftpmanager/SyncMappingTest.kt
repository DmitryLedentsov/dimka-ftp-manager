package com.dimka.ftpmanager

import com.dimka.ftpmanager.data.ComparisonScope
import com.dimka.ftpmanager.data.BranchFileSelections
import com.dimka.ftpmanager.data.ScopeRule
import com.dimka.ftpmanager.data.SyncMapping
import com.dimka.ftpmanager.data.FolderCompareMode
import com.dimka.ftpmanager.data.SyncFileSelection
import com.dimka.ftpmanager.data.ComparedFile
import com.dimka.ftpmanager.data.FileDifference
import com.dimka.ftpmanager.data.SyncPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMappingTest {
    @Test fun selectedCheckboxesAreRememberedSeparatelyForEachGitHubBranch() {
        val allChanges = setOf("plugins/a.php", "plugins/b.php", "new.js")
        val initial = BranchFileSelections()
        assertEquals(allChanges, initial.visible("master", allChanges))
        val master = initial.remember("master", allChanges, setOf("plugins/a.php"))
        assertEquals(setOf("plugins/a.php"), master.visible("master", allChanges))
        assertEquals(allChanges, master.visible("dev", allChanges))
        val dev = master.remember("dev", allChanges, setOf("plugins/b.php", "new.js"))
        assertEquals(setOf("plugins/a.php"), dev.visible("master", allChanges))
        assertEquals(setOf("plugins/b.php", "new.js"), dev.visible("dev", allChanges))
        assertEquals(emptySet<String>(), dev.visible("master", setOf("plugins/b.php")))
        assertEquals(setOf("plugins/a.php"),
            dev.visible("master", setOf("plugins/a.php", "not-selected.php")))
    }

    @Test fun savedCheckboxPreferencesSurviveRecheckAndReappearWhenFileChangesAgain() {
        val first = BranchFileSelections().remember("master",
            setOf("a.php", "b.php"), setOf("a.php"))
        val second = first.remember("master", setOf("b.php", "new.php"), setOf("new.php"))
        assertEquals(setOf("a.php", "new.php"),
            second.visible("master", setOf("a.php", "b.php", "new.php")))
        val cleared = second.remember("master", setOf("a.php", "b.php", "new.php"), emptySet())
        assertTrue(cleared.visible("master", setOf("a.php", "b.php")).isEmpty())
        assertTrue(runCatching {
            cleared.remember("master", setOf("a.php"), setOf("missing.php"))
        }.isFailure)
    }

    @Test fun changingBranchKeepsScopeAndPersistsAllBranchCheckboxes() {
        val selected = BranchFileSelections()
            .remember("master", setOf("a.php", "b.php"), setOf("a.php"))
            .remember("feature/new", setOf("a.php", "new.php"), setOf("new.php"))
        val scope = ComparisonScope().withSelection("", true, false)
            .withSelection("mg-plugins", true, true)
        val original = SyncMapping(
            repository = "owner/emka", branch = "master",
            githubPath = "", githubPicked = true, serverId = "ftp",
            ftpPath = "/", ftpPicked = true, scope = scope,
            fileSelections = selected
        )
        val switched = original.copy(branch = "feature/new")
        assertEquals(scope, switched.scope)
        assertEquals(selected, switched.fileSelections)
        assertEquals(original, SyncMapping.fromJson(original.toJson()))
        assertEquals(switched, SyncMapping.fromJson(switched.toJson()))
        assertEquals(setOf("new.php"),
            switched.fileSelections.visible(switched.branch, setOf("a.php", "new.php")))
        val legacy = original.toJson().apply { remove("fileSelections") }
        assertEquals(BranchFileSelections(), SyncMapping.fromJson(legacy).fileSelections)
    }


    @Test fun scopeRulesIncludeExcludeAndOverrideNestedFolders() {
        val original = ComparisonScope()
        assertTrue(original.includesFile("uploads/picture.png"))
        val withoutUploads = original.withSelection("uploads", true, false)
        assertFalse(withoutUploads.includesFile("uploads/picture.png"))
        assertFalse(withoutUploads.shouldTraverseDirectory("uploads"))
        assertTrue(withoutUploads.shouldTraverseDirectory("mg-templates"))
        val nested = withoutUploads.withSelection("uploads/keep.php", false, true)
        assertTrue(nested.shouldTraverseDirectory("uploads"))
        assertTrue(nested.includesFile("uploads/keep.php"))
        assertFalse(nested.includesFile("uploads/other.php"))

        val onlySome = ComparisonScope()
            .withSelection("", true, false)
            .withSelection("mg-plugins/imshop", true, true)
            .withSelection("mg-core/lib/MG.php", false, true)
        assertFalse(onlySome.includesFile("index.php"))
        assertTrue(onlySome.includesFile("mg-plugins/imshop/imshop.php"))
        assertTrue(onlySome.includesFile("mg-core/lib/MG.php"))
        assertTrue(onlySome.shouldTraverseDirectory("mg-core"))
        assertFalse(onlySome.shouldTraverseDirectory("uploads"))

        val resetSubtree = onlySome.withSelection("mg-plugins", true, false)
        assertFalse(resetSubtree.includesFile("mg-plugins/imshop/imshop.php"))
        val all = onlySome.withSelection("", true, true)
        assertTrue(all.all)
        assertTrue(all.includesFile("uploads/picture.png"))
    }

    @Test fun selectedScopesRoundTripAndLegacyMappingsDefaultToAll() {
        val custom = ComparisonScope().withSelection("", true, false)
            .withSelection("mg-plugins/imshop", true, true)
            .withSelection("mg-templates/emka/index.php", false, true)
        val mapping = SyncMapping(scope = custom)
        assertEquals(mapping, SyncMapping.fromJson(mapping.toJson()))
        val legacyJson = mapping.toJson()
        legacyJson.remove("scope")
        assertTrue(SyncMapping.fromJson(legacyJson).scope.all)
        assertTrue(runCatching { ScopeRule("../outside", false, true) }.isFailure)
        assertTrue(runCatching { ScopeRule("/etc/passwd", false, true) }.isFailure)
    }


    @Test fun folderModesSelectTheRightSideOfTheUnion() {
        val git = setOf("same.php", "git/new.php", "assets/banner.webp")
        val ftp = setOf("same.php", "ftp/old.php", "cache/image.png")
        assertEquals(
            listOf("assets/banner.webp", "cache/image.png", "ftp/old.php", "git/new.php", "same.php"),
            FolderCompareMode.ALL.paths(git, ftp)
        )
        assertEquals(listOf("same.php"), FolderCompareMode.COMMON_ONLY.paths(git, ftp))
        assertEquals(listOf("assets/banner.webp", "git/new.php", "same.php"),
            FolderCompareMode.COMMON_AND_GITHUB.paths(git, ftp))
        assertTrue(FolderCompareMode.COMMON_ONLY.paths(git, emptySet()).isEmpty())
        assertTrue(FolderCompareMode.COMMON_AND_GITHUB.paths(emptySet(), ftp).isEmpty())
    }

    @Test fun invertSelectionIsOneShotAndSkipsIdenticalOrOversized() {
        val files = listOf(
            ComparedFile("a.php", "a.php", "/a.php", FileDifference.CHANGED),
            ComparedFile("b.php", "b.php", "/b.php", FileDifference.ONLY_GITHUB),
            ComparedFile("c.png", "c.png", "/c.png", FileDifference.ONLY_FTP),
            ComparedFile("same.php", "same.php", "/same.php", FileDifference.IDENTICAL),
            ComparedFile("huge.bin", "huge.bin", "/huge.bin", FileDifference.TOO_LARGE)
        )
        val all = SyncFileSelection.selectable(files)
        assertEquals(setOf("a.php", "b.php", "c.png"), all)
        assertTrue(SyncFileSelection.invert(files, all).isEmpty())
        assertEquals(all, SyncFileSelection.invert(files, emptySet()))
        assertEquals(setOf("b.php", "c.png"),
            SyncFileSelection.invert(files, setOf("a.php", "same.php")))
    }

    @Test fun modesDefaultToAllAndDoNotChangeFileMappings() {
        val mapping = SyncMapping()
        assertEquals(FolderCompareMode.ALL, mapping.compareMode)
        assertEquals(listOf("@file"),
            FolderCompareMode.COMMON_AND_GITHUB.paths(setOf("@file"), emptySet()))
    }


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
