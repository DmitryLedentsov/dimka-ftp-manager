package com.dimka.ftpmanager

import com.dimka.ftpmanager.data.FileZillaImporter
import com.dimka.ftpmanager.data.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FileZillaImporterTest {
    private fun parse(xml: String): FileZillaImporter.Parsed =
        FileZillaImporter.parse(ByteArrayInputStream(xml.trimIndent().toByteArray(Charsets.UTF_8)))

    @Test fun mergesDuplicateSitesUpdatesCredentialsAndPreservesCustomRoot() {
        val data = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <FileZilla3>
              <Servers>
                <Server>
                  <Host>Example.COM</Host><Port>21</Port><Protocol>0</Protocol>
                  <User>developer</User><Pass encoding="base64">bmV3LXBhc3M=</Pass>
                  <Logontype>1</Logontype><Name>From FileZilla</Name>
                </Server>
                <Server>
                  <Host>example.com</Host><Port>21</Port><Protocol>0</Protocol>
                  <User>developer</User><Logontype>2</Logontype><Name>Duplicate with no password</Name>
                </Server>
                <Folder>Other
                  <Server>
                    <Host>git.example.net</Host><Port>2222</Port><Protocol>1</Protocol>
                    <User>ops</User><Logontype>2</Logontype><Name>Secure storage</Name>
                  </Server>
                </Folder>
                <Server>
                  <Host>www.example.org</Host><Port>21</Port><Protocol>4</Protocol>
                  <User>dev</User><Pass encoding="base64">cGFzcw==</Pass><Name>Explicit TLS</Name>
                </Server>
                <Server>
                  <Host>www.example.org</Host><Port>443</Port><Protocol>999</Protocol>
                  <User>dev</User><Name>Unsupported</Name>
                </Server>
              </Servers>
            </FileZilla3>
            """
        )
        assertEquals(5, data.entriesRead)
        assertEquals(1, data.duplicateEntries)
        assertEquals(0, data.conflictingPasswords)
        assertEquals(1, data.skipped)
        assertEquals(3, data.sites.size)
        assertEquals("FTPS", data.sites.last().protocol)

        val existing = listOf(ServerProfile(
            id = "existing-server-id", name = "My custom name", protocol = "FTP",
            host = "example.com", port = 21, username = "developer",
            password = "old-pass", root = "/public_html"
        ))
        val plan = FileZillaImporter.plan(existing, data)
        assertEquals(2, plan.added)
        assertEquals(1, plan.updated)
        assertEquals(0, plan.unchanged)
        assertEquals(1, plan.passwordsNeeded)
        val existingAfter = plan.profiles.single { it.id == "existing-server-id" }
        assertEquals("My custom name", existingAfter.name)
        assertEquals("/public_html", existingAfter.root)
        assertEquals("new-pass", existingAfter.password)

        val again = FileZillaImporter.plan(plan.profiles, data)
        assertEquals(0, again.added)
        assertEquals(0, again.updated)
        assertEquals(3, again.unchanged)
    }

    @Test fun missingPasswordDoesNotClearExisting() {
        val imported = parse("""
          <FileZilla3><Servers><Server>
          <Host>myhost.local</Host><Port>21</Port><Protocol>0</Protocol>
          <User>dev</User><Pass encoding="base64" />
          <Logontype>1</Logontype><Name>myhost</Name>
          </Server></Servers></FileZilla3>
        """)
        val old = ServerProfile(name = "custom", host = "myhost.local", username = "dev", password = "saved")
        val plan = FileZillaImporter.plan(listOf(old), imported)
        assertEquals(0, plan.updated)
        assertEquals(1, plan.unchanged)
        assertEquals("saved", plan.profiles.single().password)
    }

    @Test fun conflictingPasswordsAreReportedAndImplicitFtpsIsSupported() {
        val data = parse("""
          <FileZilla3><Servers>
            <Server><Host>host.example</Host><Port>21</Port><Protocol>0</Protocol>
              <User>user</User><Pass>old</Pass><Name>First</Name></Server>
            <Server><Host>host.example</Host><Port>21</Port><Protocol>0</Protocol>
              <User>user</User><Pass>new</Pass><Name>Duplicate</Name></Server>
            <Server><Host>tls.example</Host><Port>990</Port><Protocol>3</Protocol>
              <User>user</User><Pass>secret</Pass><Name>Implicit FTPS</Name></Server>
          </Servers></FileZilla3>
        """)
        assertEquals(1, data.duplicateEntries)
        assertEquals(1, data.conflictingPasswords)
        assertEquals(2, data.sites.size)
        assertEquals("new", data.sites.first().password)
        assertEquals("FTPS_IMPLICIT", data.sites.last().protocol)
        assertEquals(1, FileZillaImporter.plan(emptyList(), data).conflictingPasswords)
    }

    @Test fun rejectsFilesThatAreNotFileZillaExport() {
        val error = runCatching { parse("<root><Servers /></root>") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
