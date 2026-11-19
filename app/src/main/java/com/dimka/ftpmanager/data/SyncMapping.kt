package com.dimka.ftpmanager.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A named, manual mapping between exactly one GitHub file/directory and one
 * FTP/SFTP file/directory. No implicit deletion/mirroring.
 */
data class SyncMapping(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Новая синхронизация",
    val repository: String = "",
    val branch: String = "",
    val githubPath: String = "",
    val githubDirectory: Boolean = true,
    val githubPicked: Boolean = false,
    val serverId: String = "",
    val ftpPath: String = "",
    val ftpDirectory: Boolean = true,
    val ftpPicked: Boolean = false,
    val compareMode: FolderCompareMode = FolderCompareMode.ALL,
    val scope: ComparisonScope = ComparisonScope()
) {
    val ready: Boolean get() = repository.isNotBlank() && branch.isNotBlank() &&
        serverId.isNotBlank() && githubPicked && ftpPicked &&
        githubDirectory == ftpDirectory &&
        (githubDirectory || githubPath.isNotBlank()) &&
        ftpPath.startsWith('/')

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("repository", repository); put("branch", branch)
        put("githubPath", githubPath); put("githubDirectory", githubDirectory)
        put("githubPicked", githubPicked); put("serverId", serverId)
        put("ftpPath", ftpPath); put("ftpDirectory", ftpDirectory)
        put("ftpPicked", ftpPicked)
        put("compareMode", compareMode.name)
        put("scope", scope.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = SyncMapping(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Синхронизация"),
            repository = o.optString("repository"), branch = o.optString("branch"),
            githubPath = o.optString("githubPath"), githubDirectory = o.optBoolean("githubDirectory", true),
            githubPicked = o.optBoolean("githubPicked"), serverId = o.optString("serverId"),
            ftpPath = o.optString("ftpPath"), ftpDirectory = o.optBoolean("ftpDirectory", true),
            ftpPicked = o.optBoolean("ftpPicked"),
            compareMode = runCatching {
                FolderCompareMode.valueOf(o.optString("compareMode", FolderCompareMode.ALL.name))
            }.getOrDefault(FolderCompareMode.ALL),
            scope = if (o.has("scope")) ComparisonScope.fromJson(o.getJSONArray("scope"))
                else ComparisonScope()
        )
    }
}

/**
 * The three folder comparison modes. Single-file mappings ignore this choice.
 * Filtering happens BEFORE downloading/hashing remote files, not merely in the UI.
 */
enum class FolderCompareMode {
    ALL,
    COMMON_ONLY,
    COMMON_AND_GITHUB;

    fun paths(github: Set<String>, ftp: Set<String>): List<String> = when (this) {
        ALL -> (github + ftp).sorted()
        COMMON_ONLY -> github.intersect(ftp).sorted()
        COMMON_AND_GITHUB -> github.sorted()
    }
}


/**
 * Inclusion/exclusion rules for a mapping's RELATIVE paths.
 *
 * An empty rule set means "compare everything", preserving existing mappings.
 * The most specific matching directory rule wins; exact file rules override their
 * parent's directory rule. No external absolute paths or ".." segments are accepted.
 */
data class ScopeRule(val path: String, val directory: Boolean, val included: Boolean) {
    init { ComparisonScope.validatePath(path, directory) }
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("directory", directory)
        put("included", included)
    }
    companion object {
        fun fromJson(obj: JSONObject): ScopeRule = ScopeRule(
            obj.getString("path"),
            obj.getBoolean("directory"),
            obj.getBoolean("included")
        )
    }
}

data class ComparisonScope(val rules: List<ScopeRule> = emptyList()) {
    init {
        require(rules.size <= 3000) { "Слишком много правил выбора" }
        require(rules.map { it.path }.distinct().size == rules.size) {
            "Повторяющиеся правила выбора области"
        }
    }

    val all: Boolean get() = rules.isEmpty()

    /** A directory rule applies to the directory itself and all its descendants. */
    fun isSelected(path: String, directory: Boolean): Boolean {
        validatePath(path, directory)
        return rules.filter { rule ->
            (rule.directory && (rule.path.isEmpty() || path == rule.path ||
                path.startsWith(rule.path + "/"))) ||
                (!rule.directory && !directory && path == rule.path)
        }.maxByOrNull { it.path.length }?.included ?: true
    }

    fun includesFile(path: String): Boolean = isSelected(path, false)

    /**
     * Prune excluded FTP folders before recursive scan. Enter an excluded folder
     * only when a more specific include rule exists somewhere below it.
     */
    fun shouldTraverseDirectory(path: String): Boolean {
        validatePath(path, true)
        return isSelected(path, true) ||
            rules.any { it.included && (path.isEmpty() || it.path.startsWith("$path/")) }
    }

    fun withSelection(path: String, directory: Boolean, include: Boolean): ComparisonScope {
        validatePath(path, directory)
        val remaining = rules.filterNot { rule ->
            rule.path == path ||
                (directory && (path.isEmpty() || rule.path.startsWith("$path/")))
        }
        if (path.isEmpty() && include) return ComparisonScope()
        val parentDecision = ComparisonScope(remaining).isSelected(path, directory)
        return ComparisonScope(if (parentDecision == include) remaining
            else remaining + ScopeRule(path, directory, include))
    }

    fun toJson(): JSONArray = JSONArray().also { arr -> rules.forEach { arr.put(it.toJson()) } }

    companion object {
        fun validatePath(path: String, directory: Boolean) {
            require(path.length <= 4096 && !path.startsWith('/') &&
                !path.endsWith('/') && '\\' !in path && '\u0000' !in path &&
                (path.isNotEmpty() || directory) &&
                path.split('/').none { it == "." || it == ".." || (path.isNotEmpty() && it.isEmpty()) }
            ) { "Небезопасный относительный путь: $path" }
        }

        fun fromJson(array: JSONArray): ComparisonScope = ComparisonScope(
            (0 until array.length()).map { ScopeRule.fromJson(array.getJSONObject(it)) }
        )
    }
}

enum class SyncDirection { GITHUB_TO_FTP, FTP_TO_GITHUB }

data class SyncPreview(
    val mappingId: String,
    val direction: SyncDirection,
    val count: Int,
    val willReplace: Int,
    val willAdd: Int,
    val sample: List<String>
)

fun parseMappings(json: String): List<SyncMapping> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).map { SyncMapping.fromJson(array.getJSONObject(it)) }
}.getOrDefault(emptyList())

fun List<SyncMapping>.toMappingsJson(): String = JSONArray().also { array ->
    forEach { array.put(it.toJson()) }
}.toString()

/** Return the relative path of a remote/GitHub file inside a selected mapping. */
object SyncPaths {
    const val MAX_FILES = 1500

    fun relative(source: String, selection: String, directory: Boolean): String? {
        val path = source.trim('/')
        val base = selection.trim('/')
        if (path.isBlank() || path.split('/').any { it == "." || it == ".." || it.isBlank() }) return null
        if (!directory) return if (path == base) path.substringAfterLast('/') else null
        return when {
            base.isEmpty() -> path
            path.startsWith("$base/") -> path.removePrefix("$base/")
            else -> null
        }
    }

    fun githubDestination(mapping: SyncMapping, relative: String): String =
        if (mapping.githubDirectory) {
            listOf(mapping.githubPath.trim('/'), relative).filter { it.isNotBlank() }.joinToString("/")
        } else mapping.githubPath.trim('/')

    fun ftpDestination(mapping: SyncMapping, relative: String): String =
        if (mapping.ftpDirectory) {
            val base = mapping.ftpPath.trimEnd('/').ifBlank { "/" }
            if (base == "/") "/$relative" else "$base/$relative"
        } else mapping.ftpPath
}
