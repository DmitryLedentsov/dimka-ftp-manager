package com.dimka.ftpmanager.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dimka.ftpmanager.github.GitHubOAuth
import com.dimka.ftpmanager.data.SyncMapping
import com.dimka.ftpmanager.data.parseMappings
import com.dimka.ftpmanager.data.toMappingsJson
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps credentials out of plain SharedPreferences and of backup/restore images. */
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("dimka_config", Context.MODE_PRIVATE)
    private val alias = "dimka_ftp_manager_config"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val old = store.getKey(alias, null) as? SecretKey
        if (old != null) return old
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > 12) { "Invalid config" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }

    private fun get(name: String, defaultValue: String): String =
        prefs.getString(name, null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: defaultValue

    private fun put(name: String, value: String) {
        check(prefs.edit().putString(name, encrypt(value)).commit()) { "Failed to save settings" }
    }

    /**
     * Export ALL saved settings, including FTP passwords, PAT, OAuth refresh token and
     * pinned SFTP keys. The resulting JSON is plaintext by explicit user request.
     * Unlike normal getters, an unreadable encrypted value aborts the export.
     */
    fun exportFullConfig(): String {
        fun requireDecrypted(name: String, fallback: String): String =
            prefs.getString(name, null)?.let(::decrypt) ?: fallback
        val pins = appContext.getSharedPreferences("sftp_fingerprints", Context.MODE_PRIVATE)
            .all.mapNotNull { (name, value) ->
                (value as? String)?.let { name to it }
            }.toMap()
        return FullConfigBackup(
            serversJson = requireDecrypted("servers", "[]"),
            mappingsJson = requireDecrypted("sync_mappings", "[]"),
            statusesJson = requireDecrypted("sync_checks", "[]"),
            repoJson = requireDecrypted("repo", RepoSettings().toJson().toString()),
            oauthJson = prefs.getString("oauth", null)?.let(::decrypt),
            fingerprints = pins
        ).toJson()
    }

    /**
     * Validate first using FullConfigBackup.parse(), then atomically replace all
     * encrypted SharedPreferences keys. Android Keystore stays local to this device;
     * only the decrypted configuration travels inside the exported JSON.
     */
    fun importFullConfig(backup: FullConfigBackup) {
        val protected = mapOf(
            "servers" to encrypt(backup.serversJson),
            "sync_mappings" to encrypt(backup.mappingsJson),
            "sync_checks" to encrypt(backup.statusesJson),
            "repo" to encrypt(backup.repoJson)
        )
        val oauthEncrypted = backup.oauthJson?.let(::encrypt)
        val editor = prefs.edit()
        protected.forEach { (name, value) -> editor.putString(name, value) }
        if (oauthEncrypted == null) editor.remove("oauth")
        else editor.putString("oauth", oauthEncrypted)
        check(editor.commit()) { "Не удалось записать настройки Android" }

        val fingerprintEditor = appContext.getSharedPreferences(
            "sftp_fingerprints", Context.MODE_PRIVATE
        ).edit().clear()
        backup.fingerprints.forEach { (name, value) ->
            fingerprintEditor.putString(name, value)
        }
        check(fingerprintEditor.commit()) { "Не удалось восстановить SFTP-отпечатки" }
    }

    fun checkStatuses(): Map<String, CheckStatus> = checkStatusFromJson(get("sync_checks", "[]"))
    @Synchronized fun saveCheckStatuses(values: Map<String, CheckStatus>) =
        put("sync_checks", values.toChecksJson())
    fun updateCheckStatus(id: String, update: (CheckStatus) -> CheckStatus) {
        val map = checkStatuses().toMutableMap()
        map[id] = update(map[id] ?: CheckStatus(id))
        saveCheckStatuses(map)
    }
    fun mappings(): List<SyncMapping> = parseMappings(get("sync_mappings", "[]"))
    fun saveMappings(items: List<SyncMapping>) = put("sync_mappings", items.toMappingsJson())
    fun servers(): List<ServerProfile> = parseServers(get("servers", "[]"))
    fun saveServers(items: List<ServerProfile>) = put("servers", items.toJsonString())
    fun repo(): RepoSettings = runCatching { RepoSettings.fromJson(JSONObject(get("repo", "{}"))) }.getOrDefault(RepoSettings())
    fun saveRepo(value: RepoSettings) = put("repo", value.toJson().toString())
    fun oauth(): GitHubOAuth.Session? = runCatching {
        GitHubOAuth.Session.fromJson(JSONObject(get("oauth", "{}")))
    }.getOrNull()
    fun saveOAuth(session: GitHubOAuth.Session) = put("oauth", session.toJson().toString())
    fun clearOAuth() { check(prefs.edit().remove("oauth").commit()) { "Failed to clear GitHub session" } }
}
