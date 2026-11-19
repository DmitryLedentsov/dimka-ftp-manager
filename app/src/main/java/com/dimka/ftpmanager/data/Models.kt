package com.dimka.ftpmanager.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: String = "FTP",
    val host: String,
    val port: Int = if (protocol == "SFTP") 22 else if (protocol == "FTPS_IMPLICIT") 990 else 21,
    val username: String,
    val password: String,
    val root: String = "/"
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("protocol", protocol)
        put("host", host); put("port", port); put("username", username)
        put("password", password); put("root", root)
    }

    companion object {
        fun fromJson(o: JSONObject) = ServerProfile(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"), protocol = o.optString("protocol", "FTP"),
            host = o.optString("host"), port = o.optInt("port", 21),
            username = o.optString("username"), password = o.optString("password"),
            root = o.optString("root", "/")
        )
    }
}

data class RepoSettings(
    val token: String = "",
    val repository: String = "",
    val branch: String = "master",
    val ignore: String = DEFAULT_IGNORE
) {
    fun toJson() = JSONObject().apply {
        put("token", token); put("repository", repository)
        put("branch", branch); put("ignore", ignore)
    }
    companion object {
        fun fromJson(o: JSONObject) = RepoSettings(
            token = o.optString("token"), repository = o.optString("repository"),
            branch = o.optString("branch", "master"), ignore = o.optString("ignore", DEFAULT_IGNORE)
        )
    }
}

const val DEFAULT_IGNORE = """.git/
.env
.env.*
*.log
*.sql
*.zip
*.tar
*.gz
*.mp4
*.mov
*.avi
*.webm
*.jpg
*.jpeg
*.png
*.webp
cache/
tmp/
temp/
logs/
log/
uploads/
*.pem
*.key
*.p12
*.pfx
config.php
"""

data class RemoteEntry(val name: String, val path: String, val directory: Boolean, val size: Long = 0L)
data class RepoEntry(val path: String, val size: Long = 0L, val sha: String = "")

data class ImportSummary(val files: Int, val bytes: Long, val examples: List<String>, val skipped: Int)

fun List<ServerProfile>.toJsonString(): String = JSONArray().also { arr -> forEach { arr.put(it.toJson()) } }.toString()

fun parseServers(json: String): List<ServerProfile> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { ServerProfile.fromJson(arr.getJSONObject(it)) }
}.getOrDefault(emptyList())
