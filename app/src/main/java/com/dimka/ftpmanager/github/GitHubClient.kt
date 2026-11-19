package com.dimka.ftpmanager.github

import android.net.Uri
import android.util.Base64
import com.dimka.ftpmanager.data.RepoEntry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubClient(private val token: String) {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun api(method: String, path: String, json: JSONObject? = null): ByteArray {
        val req = Request.Builder().url("https://api.github.com$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .method(method, if (method == "GET") null else (json ?: JSONObject()).toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { response ->
            val body = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(String(body)).optString("message") }.getOrDefault("")
                throw IOException("GitHub HTTP ${response.code}: $msg")
            }
            return body
        }
    }

    private fun obj(method: String, path: String, json: JSONObject? = null): JSONObject =
        JSONObject(String(api(method, path, json), Charsets.UTF_8))

    private fun repoPath(repo: String): String {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo.trim())) {
            "Repository must be owner/name"
        }
        return "/repos/${repo.trim()}"
    }
    private fun ref(branch: String) = Uri.encode(branch.trim(), "/")

    fun verify(repo: String): String = obj("GET", repoPath(repo)).optString("default_branch", "master")

    fun tree(repo: String, branch: String): List<RepoEntry> {
        val payload = obj("GET", "${repoPath(repo)}/git/trees/${ref(branch)}?recursive=1")
        check(!payload.optBoolean("truncated")) { "GitHub tree too large, use a smaller repository" }
        val arr = payload.getJSONArray("tree")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("type") == "blob" }
            .map { RepoEntry(it.getString("path"), it.optLong("size")) }
            .sortedBy { it.path.lowercase() }
    }

    /** First page of accessible repositories, newest first. */
    fun repositories(): List<String> {
        val array = JSONArray(String(api("GET", "/user/repos?per_page=100&sort=updated"), Charsets.UTF_8))
        return (0 until array.length()).map { array.getJSONObject(it).getString("full_name") }
    }

    fun download(repo: String, branch: String, path: String, output: File) {
        require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." })
        val encoded = Uri.encode(path, "/")
        val request = Request.Builder()
            .url("https://api.github.com${repoPath(repo)}/contents/$encoded?ref=${Uri.encode(branch)}")
            .header("Accept", "application/vnd.github.raw+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        output.parentFile?.mkdirs()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub file $path: HTTP ${response.code}")
            output.outputStream().use { target ->
                response.body?.byteStream()?.use { it.copyTo(target) } ?: throw IOException("Empty GitHub response")
            }
        }
    }

    private fun blob(repo: String, file: File): String {
        require(file.isFile && file.length() <= 20L * 1024 * 1024) { "File too large for GitHub API: ${file.name}" }
        val payload = JSONObject().put("content", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            .put("encoding", "base64")
        return obj("POST", "${repoPath(repo)}/git/blobs", payload).getString("sha")
    }

    private fun blobText(repo: String, content: String): String = obj(
        "POST", "${repoPath(repo)}/git/blobs",
        JSONObject().put("content", content).put("encoding", "utf-8")
    ).getString("sha")

    private fun branchHead(repo: String, branch: String): Pair<String, String> {
        val head = obj("GET", "${repoPath(repo)}/git/ref/heads/${ref(branch)}").getJSONObject("object").getString("sha")
        val tree = obj("GET", "${repoPath(repo)}/git/commits/$head").getJSONObject("tree").getString("sha")
        return head to tree
    }

    private fun bootstrapEmpty(repo: String, branch: String, ignore: String) {
        val base = JSONObject().put("message", "Initialize repository for DimkaFtpManager")
            .put("content", Base64.encodeToString((ignore + "\n").toByteArray(), Base64.NO_WRAP))
            .put("branch", branch)
        // An empty GitHub repository has no branch; create .gitignore on its default branch.
        try {
            api("PUT", "${repoPath(repo)}/contents/.gitignore", base)
        } catch (e: IOException) {
            if (branch == "master") {
                // Some new repositories default to main even when empty.
                base.put("branch", "main")
                api("PUT", "${repoPath(repo)}/contents/.gitignore", base)
            } else throw e
        }
    }

    /** Commit only selected mapped files. No deletion or .gitignore rewrite. */
    fun commitFiles(
        repo: String, branch: String, files: List<Pair<String, File>>,
        message: String, progress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        require(token.isNotBlank()) { "GitHub authorization required" }
        require(files.isNotEmpty()) { "No files selected" }
        require(files.map { it.first }.distinct().size == files.size) { "Duplicate target paths" }
        val (head, baseTree) = branchHead(repo, branch)
        val entries = JSONArray()
        files.forEachIndexed { index, (path, source) ->
            require(path.isNotBlank() && !path.startsWith('/') &&
                path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Invalid GitHub destination: " + path
            }
            val sha = blob(repo, source)
            entries.put(JSONObject().put("path", path).put("mode", "100644")
                .put("type", "blob").put("sha", sha))
            progress(index + 1, files.size + 1)
        }
        val tree = obj("POST", "${repoPath(repo)}/git/trees",
            JSONObject().put("base_tree", baseTree).put("tree", entries)).getString("sha")
        val commit = obj("POST", "${repoPath(repo)}/git/commits",
            JSONObject().put("message", message.take(160))
                .put("tree", tree).put("parents", JSONArray().put(head))).getString("sha")
        // A concurrent branch update fails safely as a non-fast-forward.
        obj("PATCH", "${repoPath(repo)}/git/refs/heads/${ref(branch)}",
            JSONObject().put("sha", commit).put("force", false))
        progress(files.size + 1, files.size + 1)
        return commit
    }

    /** One Git commit for every imported source tree. Existing repo content is preserved. */
    fun commitImport(
        repo: String, branch: String, files: List<Pair<String, File>>, ignore: String,
        progress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        require(token.isNotBlank()) { "GitHub token required" }
        if (files.isEmpty()) error("No source files left after ignore filters")
        var head = runCatching { branchHead(repo, branch) }.getOrElse { error ->
            if (error is IOException && error.message?.contains("404") == true) {
                bootstrapEmpty(repo, branch, ignore)
                branchHead(repo, branch)
            } else throw error
        }
        val entries = JSONArray()
        files.forEachIndexed { i, (path, file) ->
            require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." })
            val sha = blob(repo, file)
            entries.put(JSONObject().put("path", path).put("mode", "100644")
                .put("type", "blob").put("sha", sha))
            progress(i + 1, files.size + 1)
        }
        val ignoreBlob = blobText(repo, ignore)
        entries.put(JSONObject().put("path", ".gitignore").put("mode", "100644")
            .put("type", "blob").put("sha", ignoreBlob))
        val newTree = obj("POST", "${repoPath(repo)}/git/trees",
            JSONObject().put("base_tree", head.second).put("tree", entries)).getString("sha")
        val commit = obj("POST", "${repoPath(repo)}/git/commits",
            JSONObject().put("message", "Import website via DimkaFtpManager")
                .put("tree", newTree).put("parents", JSONArray().put(head.first))).getString("sha")
        obj("PATCH", "${repoPath(repo)}/git/refs/heads/${ref(branch)}",
            JSONObject().put("sha", commit).put("force", false))
        progress(files.size + 1, files.size + 1)
        return commit
    }
}
