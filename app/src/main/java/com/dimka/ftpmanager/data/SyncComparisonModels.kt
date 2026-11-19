package com.dimka.ftpmanager.data

import org.json.JSONArray
import org.json.JSONObject

enum class FileDifference {
    IDENTICAL, CHANGED, ONLY_GITHUB, ONLY_FTP, TOO_LARGE
}

data class ComparedFile(
    val relative: String,
    val githubPath: String,
    val ftpPath: String,
    val difference: FileDifference,
    val githubSha: String = "",
    val ftpSha: String = "",
    val githubSize: Long = -1,
    val ftpSize: Long = -1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("relative", relative)
        put("githubPath", githubPath)
        put("ftpPath", ftpPath)
        put("difference", difference.name)
        put("githubSha", githubSha)
        put("ftpSha", ftpSha)
        put("githubSize", githubSize)
        put("ftpSize", ftpSize)
    }
    companion object {
        fun fromJson(obj: JSONObject): ComparedFile = ComparedFile(
            obj.getString("relative"),
            obj.getString("githubPath"),
            obj.getString("ftpPath"),
            FileDifference.valueOf(obj.getString("difference")),
            obj.optString("githubSha"),
            obj.optString("ftpSha"),
            obj.optLong("githubSize", -1),
            obj.optLong("ftpSize", -1)
        )
    }
}

data class MappingComparison(
    val mapping: SyncMapping,
    val checkedAt: Long,
    val files: List<ComparedFile>
) {
    val matching get() = files.count { it.difference == FileDifference.IDENTICAL }
    val changes get() = files.count { it.difference != FileDifference.IDENTICAL }
    val blocked get() = files.count { it.difference == FileDifference.TOO_LARGE }
    val synchronized get() = changes == 0
    fun toJson(): JSONObject = JSONObject().apply {
        put("mapping", mapping.toJson()); put("checkedAt", checkedAt)
        put("files", JSONArray().also { a -> files.forEach { a.put(it.toJson()) } })
    }
    companion object {
        fun fromJson(obj: JSONObject): MappingComparison {
            val files = obj.getJSONArray("files")
            return MappingComparison(SyncMapping.fromJson(obj.getJSONObject("mapping")),
                obj.getLong("checkedAt"),
                (0 until files.length()).map { ComparedFile.fromJson(files.getJSONObject(it)) })
        }
    }
}

data class CheckStatus(
    val mappingId: String,
    val status: String = "UNKNOWN",
    val checkedAt: Long = 0L,
    val syncedAt: Long = 0L,
    val matching: Int = 0,
    val changed: Int = 0,
    val error: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mappingId", mappingId); put("status", status)
        put("checkedAt", checkedAt); put("syncedAt", syncedAt)
        put("matching", matching); put("changed", changed); put("error", error)
    }
    companion object {
        fun fromJson(obj: JSONObject): CheckStatus = CheckStatus(
            obj.getString("mappingId"), obj.optString("status", "UNKNOWN"),
            obj.optLong("checkedAt"), obj.optLong("syncedAt"),
            obj.optInt("matching"), obj.optInt("changed"), obj.optString("error")
        )
    }
}

fun checkStatusFromJson(json: String): Map<String, CheckStatus> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { CheckStatus.fromJson(arr.getJSONObject(it)) }
        .associateBy { it.mappingId }
}.getOrDefault(emptyMap())

fun Map<String, CheckStatus>.toChecksJson(): String = JSONArray().apply {
    values.forEach { put(it.toJson()) }
}.toString()

/** An explicit selection and its checked snapshot, persisted before enqueuing WorkManager. */
data class SyncJobDraft(
    val comparison: MappingComparison,
    val direction: SyncDirection,
    val selection: Set<String>
) {
    fun toJson(): String = JSONObject().apply {
        put("comparison", comparison.toJson())
        put("direction", direction.name)
        put("selection", JSONArray().also { a -> selection.forEach { a.put(it) } })
    }.toString()
    companion object {
        fun fromJson(json: String): SyncJobDraft {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("selection")
            return SyncJobDraft(MappingComparison.fromJson(obj.getJSONObject("comparison")),
                SyncDirection.valueOf(obj.getString("direction")),
                (0 until arr.length()).map { arr.getString(it) }.toSet())
        }
    }
}
