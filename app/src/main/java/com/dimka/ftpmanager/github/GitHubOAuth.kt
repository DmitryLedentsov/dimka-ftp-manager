package com.dimka.ftpmanager.github

import android.content.Context
import com.dimka.ftpmanager.data.SettingsStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public OAuth App identifier (NOT a client secret). GitHub Device Flow needs no backend. */
object GitHubOAuth {
    const val CLIENT_ID = "Ov23liMhyKLhKmZ8N7BU"
    const val DEVICE_URL = "https://github.com/login/device"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class DeviceCode(
        val code: String,
        val userCode: String,
        val expiresIn: Long,
        val interval: Long
    )

    data class Session(
        val accessToken: String,
        val refreshToken: String = "",
        val expiresAt: Long = 0L,
        val refreshExpiresAt: Long = 0L,
        val login: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            put("expires_at", expiresAt)
            put("refresh_expires_at", refreshExpiresAt)
            put("login", login)
        }

        companion object {
            fun fromJson(o: JSONObject): Session? {
                val access = o.optString("access_token")
                if (access.isBlank()) return null
                return Session(
                    accessToken = access,
                    refreshToken = o.optString("refresh_token"),
                    expiresAt = o.optLong("expires_at"),
                    refreshExpiresAt = o.optLong("refresh_expires_at"),
                    login = o.optString("login")
                )
            }
        }
    }

    private fun post(path: String, vararg pairs: Pair<String, String>): JSONObject {
        val form = FormBody.Builder().apply {
            pairs.forEach { (name, value) -> add(name, value) }
        }.build()
        val request = Request.Builder()
            .url("https://github.com" + path)
            .header("Accept", "application/json")
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub OAuth HTTP " + response.code + ": " + body.take(250))
            }
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IOException("Unexpected GitHub OAuth response", e)
            }
        }
    }

    fun requestDeviceCode(): DeviceCode {
        // OAuth 'repo' scope is broad (including private repositories). The UI warns the user.
        val data = post("/login/device/code", "client_id" to CLIENT_ID, "scope" to "repo")
        if (data.has("error")) throw IOException(data.optString("error_description", data.optString("error")))
        return DeviceCode(
            code = data.getString("device_code"),
            userCode = data.getString("user_code"),
            expiresIn = data.optLong("expires_in", 900L).coerceAtLeast(1L),
            interval = data.optLong("interval", 5L).coerceAtLeast(5L)
        )
    }

    fun poll(code: String): JSONObject = post(
        "/login/oauth/access_token",
        "client_id" to CLIENT_ID,
        "device_code" to code,
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
    )

    fun sessionFrom(response: JSONObject, login: String): Session {
        val token = response.optString("access_token")
        if (token.isBlank()) throw IOException("GitHub did not return an access token")
        val now = System.currentTimeMillis()
        val expires = response.optLong("expires_in", 0L)
        val refreshExpires = response.optLong("refresh_token_expires_in", 0L)
        return Session(
            accessToken = token,
            refreshToken = response.optString("refresh_token"),
            expiresAt = if (expires > 0) now + expires * 1000L else 0L,
            refreshExpiresAt = if (refreshExpires > 0) now + refreshExpires * 1000L else 0L,
            login = login
        )
    }

    fun refresh(old: Session): Session {
        if (old.refreshToken.isBlank()) throw IOException("GitHub session expired; sign in again")
        val response = post(
            "/login/oauth/access_token",
            "client_id" to CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to old.refreshToken
        )
        if (response.has("error")) {
            throw IOException("GitHub session needs a new sign-in: " + response.optString("error"))
        }
        return sessionFrom(response, old.login)
    }

    fun login(token: String): String {
        val request = Request.Builder()
            .url("https://api.github.com/user")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Cannot verify GitHub login (HTTP " + response.code + ")")
            val login = JSONObject(response.body?.string().orEmpty()).optString("login")
            if (login.isBlank()) throw IOException("GitHub did not identify the account")
            return login
        }
    }

    /**
     * Resolves the active credential for both the foreground browser and WorkManager.
     * A refreshed token pair is atomically stored in encrypted app preferences.
     */
    @Synchronized
    fun accessToken(context: Context): String {
        val settings = SettingsStore(context)
        val oauth = settings.oauth()
        if (oauth != null) {
            val now = System.currentTimeMillis()
            if (oauth.expiresAt == 0L || oauth.expiresAt > now + 60_000L) return oauth.accessToken
            if (oauth.refreshExpiresAt != 0L && oauth.refreshExpiresAt <= now) {
                throw IOException("GitHub session expired; sign in again")
            }
            val next = refresh(oauth)
            settings.saveOAuth(next)
            return next.accessToken
        }
        return settings.repo().token.trim().ifBlank {
            throw IOException("Sign in with GitHub or enter a personal access token in Settings")
        }
    }
}
