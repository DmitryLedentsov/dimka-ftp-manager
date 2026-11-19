package com.dimka.ftpmanager

import com.dimka.ftpmanager.data.CheckStatus
import com.dimka.ftpmanager.data.FolderCompareMode
import com.dimka.ftpmanager.data.FullConfigBackup
import com.dimka.ftpmanager.data.RepoSettings
import com.dimka.ftpmanager.data.ServerProfile
import com.dimka.ftpmanager.data.SyncMapping
import com.dimka.ftpmanager.github.GitHubOAuth
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM org.json implementation is provided by testImplementation. Never use real credentials. */
class FullConfigBackupTest {
    @Test fun backupRoundTripIncludesAllCredentialsAndSyncModes() {
        val host = ServerProfile(
            id = "example-host", name = "Example", protocol = "SFTP",
            host = "127.0.0.1", port = 22,
            username = "test-user", password = "FTP-SECRET-DO-NOT-USE", root = "/site"
        )
        val mapping = SyncMapping(
            id = "mapping-one", name = "Sync template", repository = "demo/site", branch = "main",
            githubPath = "template", githubPicked = true, serverId = host.id,
            ftpPath = "/site/template", ftpPicked = true,
            compareMode = FolderCompareMode.COMMON_AND_GITHUB
        )
        val oauth = GitHubOAuth.Session(
            "OAUTH-ACCESS-SECRET", "OAUTH-REFRESH-SECRET",
            12345L, 65432L, "test-login"
        )
        val backup = FullConfigBackup(
            serversJson = JSONArray().put(host.toJson()).toString(),
            mappingsJson = JSONArray().put(mapping.toJson()).toString(),
            statusesJson = JSONArray().put(CheckStatus(mapping.id,
                "CHANGED", 100L, 99L, 4, 2).toJson()).toString(),
            repoJson = RepoSettings("PAT-SECRET", "demo/site", "main", "*.log").toJson().toString(),
            oauthJson = oauth.toJson().toString(),
            fingerprints = mapOf("127.0.0.1:22" to "PINNED-KEY-FINGERPRINT"),
            exportedAt = 123L
        )
        val json = backup.toJson()
        assertTrue(json.contains("FTP-SECRET-DO-NOT-USE"))
        assertTrue(json.contains("OAUTH-REFRESH-SECRET"))
        assertTrue(json.contains("PAT-SECRET"))
        val restored = FullConfigBackup.parse(json)
        assertEquals(1, restored.serverCount)
        assertEquals(1, restored.mappingCount)
        assertEquals(1, restored.checksCount)
        assertTrue(restored.hasOAuth)
        assertTrue(restored.hasPat)
        assertEquals("FTP-SECRET-DO-NOT-USE",
            ServerProfile.fromJson(JSONArray(restored.serversJson).getJSONObject(0)).password)
        assertEquals(FolderCompareMode.COMMON_AND_GITHUB,
            SyncMapping.fromJson(JSONArray(restored.mappingsJson).getJSONObject(0)).compareMode)
        assertEquals("OAUTH-REFRESH-SECRET",
            GitHubOAuth.Session.fromJson(JSONObject(restored.oauthJson!!))!!.refreshToken)
        assertEquals("PINNED-KEY-FINGERPRINT", restored.fingerprints["127.0.0.1:22"])
    }

    @Test fun invalidAndIncompleteBackupsAreRejected() {
        assertTrue(runCatching { FullConfigBackup.parse("{}") }.isFailure)
        val empty = FullConfigBackup(
            "[]", "[]", "[]", RepoSettings().toJson().toString(), null, emptyMap()
        )
        val parsed = FullConfigBackup.parse(empty.toJson())
        assertEquals(0, parsed.serverCount)
        assertFalse(parsed.hasOAuth)
        assertFalse(parsed.hasPat)
        val modified = JSONObject(empty.toJson()).put("version", 100)
        assertTrue(runCatching { FullConfigBackup.parse(modified.toString()) }.isFailure)
    }
}
