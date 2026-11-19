package com.dimka.ftpmanager.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * FileZilla 3 Site Manager XML importer. The XML is read exclusively on-device;
 * it is never transmitted to GitHub, logged, or copied into Downloads.
 */
object FileZillaImporter {
    private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

    data class Parsed(
        val sites: List<ServerProfile>,
        val entriesRead: Int,
        val duplicateEntries: Int,
        val skipped: Int,
        val passwordMissing: Int,
        val conflictingPasswords: Int
    )

    data class Plan(
        val profiles: List<ServerProfile>,
        val added: Int,
        val updated: Int,
        val unchanged: Int,
        val entriesRead: Int,
        val duplicateEntries: Int,
        val skipped: Int,
        val passwordsNeeded: Int,
        val conflictingPasswords: Int,
        val examples: List<String>
    )

    private data class Identity(val protocol: String, val host: String, val port: Int, val user: String)

    private fun identity(server: ServerProfile) = Identity(
        server.protocol.uppercase(),
        server.host.trim().lowercase(),
        server.port,
        server.username.trim()
    )

    private fun Element.child(name: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val item = nodes.item(i)
            if (item.nodeType == Node.ELEMENT_NODE && item.nodeName == name) return item as Element
        }
        return null
    }

    private fun Element.text(name: String): String = child(name)?.textContent?.trim().orEmpty()

    fun parse(input: InputStream): Parsed {
        // A local configuration file should never exhaust the application's memory.
        val bytes = input.use { source ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_IMPORT_BYTES) { "XML FileZilla слишком большой (максимум 4 МБ)" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(!String(bytes, Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            "XML с DOCTYPE не поддерживается"
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        // Some Android XML parsers do not recognize every Xerces feature.
        // Reject DOCTYPE above, disable external entities where supported,
        // and provide a resolver that never uses the network.
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        val doc = builder.parse(ByteArrayInputStream(bytes))
        require(doc.documentElement?.nodeName == "FileZilla3") { "Неверный формат: ожидается экспорт FileZilla3" }
        val serversRoot = doc.documentElement.child("Servers")
            ?: error("В XML нет раздела Servers")
        // Includes saved sites inside FileZilla's nested folders.
        val nodes = serversRoot.getElementsByTagName("Server")
        val unique = linkedMapOf<Identity, ServerProfile>()
        var skipped = 0
        var duplicateEntries = 0
        val conflictingConnections = hashSetOf<Identity>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val protocol = when (node.text("Protocol")) {
                "0", "6" -> "FTP"
                "1" -> "SFTP"
                "4" -> "FTPS" // FileZilla: explicit FTP-over-TLS
                "3" -> "FTPS_IMPLICIT"
                else -> { skipped++; continue }
            }
            val host = node.text("Host")
            val user = node.text("User").ifBlank {
                if (node.text("Logontype") == "0") "anonymous" else ""
            }
            val port = node.text("Port").toIntOrNull()
                ?: if (protocol == "SFTP") 22 else if (protocol == "FTPS_IMPLICIT") 990 else 21
            if (host.isBlank() || user.isBlank() || port !in 1..65535) {
                skipped++
                continue
            }
            val passwordField = node.child("Pass")
            val password = if (passwordField == null) "" else {
                val source = passwordField.textContent.orEmpty()
                when (passwordField.getAttribute("encoding")) {
                    "base64" -> try {
                        String(Base64.getDecoder().decode(source), Charsets.UTF_8)
                    } catch (_: IllegalArgumentException) {
                        skipped++
                        continue
                    }
                    "", "plain" -> source
                    else -> { skipped++; continue }
                }
            }
            val name = node.text("Name").ifBlank { host }
            val profile = ServerProfile(
                name = name, protocol = protocol, host = host, port = port,
                username = user, password = password, root = "/"
            )
            val key = identity(profile)
            val previous = unique[key]
            if (previous != null) {
                duplicateEntries++
                if (previous.password.isNotEmpty() && password.isNotEmpty() &&
                    previous.password != password) conflictingConnections.add(key)
                // Preserve the first readable title, but use the last non-empty credential.
                unique[key] = previous.copy(password = password.ifBlank { previous.password })
            } else {
                unique[key] = profile
            }
        }
        require(nodes.length > 0) { "В экспорте FileZilla нет сохранённых сайтов" }
        return Parsed(
            sites = unique.values.toList(),
            entriesRead = nodes.length,
            duplicateEntries = duplicateEntries,
            skipped = skipped,
            passwordMissing = unique.values.count { it.password.isEmpty() },
            conflictingPasswords = conflictingConnections.size
        )
    }

    /**
     * Merge by protocol + host + port + login (not display name).
     * Never delete existing entries. Preserve their IDs, custom names and FTP root paths.
     * Absent passwords in FileZilla must not clear locally saved credentials.
     */
    fun plan(existing: List<ServerProfile>, imported: Parsed): Plan {
        val merged = existing.toMutableList()
        val locations = hashMapOf<Identity, Int>()
        merged.forEachIndexed { index, item -> locations.putIfAbsent(identity(item), index) }
        var added = 0
        var updated = 0
        var unchanged = 0
        val changedNames = mutableListOf<String>()
        imported.sites.forEach { site ->
            val key = identity(site)
            val index = locations[key]
            if (index == null) {
                locations[key] = merged.size
                merged.add(site)
                added++
                changedNames.add(site.name)
            } else {
                val old = merged[index]
                val replacement = old.copy(
                    password = site.password.ifBlank { old.password }
                )
                if (replacement != old) {
                    merged[index] = replacement
                    updated++
                    changedNames.add(old.name)
                } else {
                    unchanged++
                }
            }
        }
        return Plan(
            profiles = merged,
            added = added,
            updated = updated,
            unchanged = unchanged,
            entriesRead = imported.entriesRead,
            duplicateEntries = imported.duplicateEntries,
            skipped = imported.skipped,
            passwordsNeeded = merged.count { it.password.isEmpty() },
            conflictingPasswords = imported.conflictingPasswords,
            examples = changedNames.take(8)
        )
    }
}
