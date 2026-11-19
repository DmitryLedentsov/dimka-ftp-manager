package com.dimka.ftpmanager.data

import com.dimka.ftpmanager.github.GitHubOAuth
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable, intentionally UNENCRYPTED JSON backup. Secrets are exported in clear text
 * only when a user explicitly chooses Export configuration in Settings.
 */
data class FullConfigBackup(
    val serversJson: String,
    val mappingsJson: String,
    val statusesJson: String,
    val repoJson: String,
    val oauthJson: String?,
    val fingerprints: Map<String, String>,
    val exportedAt: Long = System.currentTimeMillis()
) {
    val serverCount get() = JSONArray(serversJson).length()
    val mappingCount get() = JSONArray(mappingsJson).length()
    val checksCount get() = JSONArray(statusesJson).length()
    val hasOAuth get() = oauthJson != null
    val hasPat get() = JSONObject(repoJson).optString("token").isNotBlank()

    fun toJson(): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("exportedAt", exportedAt)
        put("servers", JSONArray(serversJson))
        put("syncMappings", JSONArray(mappingsJson))
        put("syncChecks", JSONArray(statusesJson))
        put("githubRepoSettings", JSONObject(repoJson))
        put("githubOAuth", if (oauthJson == null) JSONObject.NULL else JSONObject(oauthJson))
        put("sftpHostFingerprints", JSONObject().also { out ->
            fingerprints.forEach { (host, fingerprint) -> out.put(host, fingerprint) }
        })
    }.toString(2)

    companion object {
        const val FORMAT = "dimka-ftp-manager-full-config"
        const val VERSION = 1
        const val MAX_JSON_BYTES = 16 * 1024 * 1024

        /** Validate the entire file BEFORE allowing the user to overwrite local configuration. */
        fun parse(source: String): FullConfigBackup {
            require(source.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
                "Файл конфигурации слишком большой (лимит 16 МиБ)"
            }
            val obj = JSONObject(source)
            require(obj.getString("format") == FORMAT) {
                "Это не файл полного экспорта DimkaFtpManager"
            }
            require(obj.getInt("version") == VERSION) {
                "Неподдерживаемая версия формата конфигурации"
            }
            val hosts = obj.getJSONArray("servers")
            val hostIds = HashSet<String>()
            for (i in 0 until hosts.length()) {
                val entry = hosts.getJSONObject(i)
                val id = entry.getString("id")
                require(id.isNotBlank() && hostIds.add(id)) { "Повторный или пустой ID сервера" }
                require(entry.getString("name").isNotBlank() && entry.getString("host").isNotBlank()) {
                    "Имя или адрес сервера отсутствует"
                }
                require(entry.getString("username").isNotBlank() && entry.has("password")) {
                    "Отсутствуют данные FTP-авторизации"
                }
                require(entry.getString("protocol") in setOf("FTP", "FTPS", "FTPS_IMPLICIT", "SFTP")) {
                    "Неизвестный FTP-протокол"
                }
                require(entry.getInt("port") in 1..65535) { "Неверный порт сервера" }
                require(entry.getString("root").startsWith('/')) { "Неверная корневая папка" }
            }

            val mappings = obj.getJSONArray("syncMappings")
            val mappingIds = HashSet<String>()
            for (i in 0 until mappings.length()) {
                val entry = mappings.getJSONObject(i)
                val id = entry.getString("id")
                require(id.isNotBlank() && mappingIds.add(id)) { "Повторный или пустой ID синхронизации" }
                val mapping = SyncMapping.fromJson(entry)
                require(mapping.compareMode in FolderCompareMode.entries) {
                    "Неизвестный режим сравнения"
                }
                if (mapping.ftpPicked) require(mapping.ftpPath.startsWith('/')) {
                    "Некорректный FTP-путь синхронизации"
                }
            }

            val status = obj.getJSONArray("syncChecks")
            for (i in 0 until status.length()) {
                val entry = status.getJSONObject(i)
                require(entry.getString("mappingId").isNotBlank()) { "Некорректный ID проверки" }
            }
            val settings = obj.getJSONObject("githubRepoSettings")
            require(settings.has("token") && settings.has("repository") &&
                settings.has("branch") && settings.has("ignore")) {
                "Неполные настройки GitHub"
            }
            val oauth = if (obj.isNull("githubOAuth")) null else {
                val data = obj.getJSONObject("githubOAuth")
                require(GitHubOAuth.Session.fromJson(data) != null) {
                    "Некорректная GitHub OAuth-сессия"
                }
                data.toString()
            }
            val fp = obj.getJSONObject("sftpHostFingerprints")
            val pins = linkedMapOf<String, String>()
            val names = fp.keys()
            while (names.hasNext()) {
                val name = names.next()
                val value = fp.getString(name)
                require(name.isNotBlank() && value.isNotBlank()) { "Некорректный SFTP-отпечаток" }
                pins[name] = value
            }
            return FullConfigBackup(
                hosts.toString(), mappings.toString(), status.toString(), settings.toString(),
                oauth, pins, obj.optLong("exportedAt")
            )
        }
    }
}
