package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.PluginAiException
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginSettingEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

/** 같은 프로필의 동시 요청만 직렬화하고 다른 프로필의 토큰은 공유하지 않아요. */
internal class CodexAuthStore {
    private val mutex = Mutex()
    private var current: CodexCredentials? = null

    @Synchronized
    fun credentials(authJson: String): CodexCredentials {
        val known = current
        if (known != null && known.storedAuth == authJson) return known
        return CodexCredentials(authJson, mutex, known?.takeIf { it.initialAuth == authJson }).also { current = it }
    }
}

internal class CodexCredentials(
    val initialAuth: String,
    private val mutex: Mutex,
    private var previous: CodexCredentials? = null,
) {
    @Volatile
    var storedAuth: String = initialAuth
        private set
    private var current: CodexTokens? = null
    private var canPersist = true

    suspend fun tokens(host: PluginHost, profileId: String): CodexTokens = mutex.withLock {
        var tokens = current
        if (tokens == null) {
            // 고정 인증을 물려받은 자식은 이미 갱신해 저장한 같은 인증만 재사용해요.
            // 사용자가 인증을 비우거나 교체했다면 새 인증을 대신 읽지 않아요.
            val known = previous
            previous = null
            val liveAuth = if (known == null) null else host.settingEntries(PROVIDERS_GROUP)
                .firstOrNull { it.id == profileId }?.values?.get("auth-json")
            if (known != null && known.current != null && liveAuth == known.storedAuth &&
                known.storedAuth != initialAuth
            ) {
                current = known.current
                storedAuth = known.storedAuth
                tokens = current
            }
        }
        if (tokens == null) {
            val source = initialAuth.trim().ifBlank {
                val result = host.shell("cat -- \"\$HOME/.codex/auth.json\"", 15).getOrElse {
                    throw PluginAiException("Termux의 Codex 로그인 정보를 읽지 못했어요. codex login을 실행해 주세요.", cause = it)
                }
                if (result.exitCode != 0 || result.truncated) {
                    throw PluginAiException("Termux의 Codex 로그인 정보를 읽지 못했어요. codex login을 실행해 주세요.")
                }
                result.output.trim()
            }
            tokens = parseTokens(source)
                ?: throw PluginAiException("Codex 로그인 정보가 없거나 올바르지 않아요. 인증 JSON 또는 Termux의 codex login을 확인해 주세요.")
            if (initialAuth.isBlank()) persist(host, profileId, tokens.raw.toString())
            current = tokens
        }
        val resolved = tokens
        val expiry = resolved.expiresAtMillis
        if (expiry == null || System.currentTimeMillis() < expiry - 60_000L) return@withLock resolved
        val updated = refresh(host, resolved)
        currentCoroutineContext().ensureActive()
        persist(host, profileId, updated.raw.toString())
        current = updated
        updated
    }

    private suspend fun persist(host: PluginHost, profileId: String, auth: String) {
        currentCoroutineContext().ensureActive()
        if (!canPersist) return
        // 비교와 부분 갱신은 호스트 저장소의 같은 잠금 안에서 이뤄져요.
        val written = host.writeSettingEntryIfUnchanged(
            PROVIDERS_GROUP,
            PluginSettingEntry(profileId, mapOf("auth-json" to auth)),
            mapOf("auth-json" to storedAuth, "type" to CODEX_TYPE),
        ).getOrElse {
            throw PluginAiException("Codex 갱신 인증을 이 제공자 프로필에 저장하지 못했어요.", cause = it)
        }
        if (written) storedAuth = auth else canPersist = false
    }

    private suspend fun refresh(host: PluginHost, tokens: CodexTokens): CodexTokens {
        if (tokens.refreshToken.isBlank()) {
            throw PluginAiException("Codex 로그인이 만료됐어요. ChatGPT 계정으로 다시 로그인해 주세요.")
        }
        val response = host.http(
            PluginHttpRequest(
                method = "POST",
                url = "https://auth.openai.com/oauth/token",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = "grant_type=refresh_token&client_id=app_EMoamEEZ73f0CkXaXp7hrann&refresh_token=" +
                    URLEncoder.encode(tokens.refreshToken, Charsets.UTF_8.name()),
            ),
        ).getOrThrow()
        val payload = runCatching { Json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
        val access = payload?.stringValue("access_token")
        if (response.status !in 200..299 || payload == null || access == null) {
            throw PluginAiException(
                "Codex 토큰을 갱신하지 못했어요. ChatGPT 계정으로 다시 로그인해 주세요." +
                    (payload?.apiMessage()?.let { " ($it)" } ?: " (HTTP ${response.status})"),
                response.status, response.retryAfterSeconds(),
            )
        }
        val expiry = payload.longValue("expires_in")?.let { System.currentTimeMillis() + it * 1000 }
            ?: expiryFromAccessToken(access)
        return tokens.updated(
            access,
            payload.stringValue("refresh_token") ?: tokens.refreshToken,
            expiry,
            payload.stringValue("id_token"),
        )
    }
}

/** 비밀 토큰의 자동 toString을 만들지 않아요. */
internal class CodexTokens(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String?,
    val expiresAtMillis: Long?,
    val raw: JsonObject,
) {
    fun updated(access: String, refresh: String, expiry: Long?, idToken: String?): CodexTokens {
        val nested = raw["tokens"] as? JsonObject
        val source = nested ?: raw
        val account = accountIdFromAccessToken(access) ?: accountId
        val fields = buildJsonObject {
            source.forEach { (key, value) -> if (key !in TOKEN_FIELDS) put(key, value) }
            put("access_token", access)
            if (refresh.isNotBlank()) put("refresh_token", refresh)
            if (idToken != null) put("id_token", idToken)
            if (account != null) put("account_id", account)
            if (expiry != null) {
                val previous = source.longValue("expires_at", "expires")
                val key = if (source.containsKey("expires")) "expires" else "expires_at"
                put(key, if (previous != null && previous <= 10_000_000_000L) expiry / 1000 else expiry)
            }
        }
        val root = if (nested != null) {
            JsonObject(raw + mapOf("tokens" to fields, "last_refresh" to JsonPrimitive(Instant.now().toString())))
        } else {
            JsonObject(raw.filterKeys { it !in TOKEN_FIELDS } + fields +
                mapOf("last_refresh" to JsonPrimitive(Instant.now().toString())))
        }
        return CodexTokens(access, refresh, account, expiry, root)
    }
}

private val TOKEN_FIELDS = setOf(
    "access_token", "access", "refresh_token", "refresh", "account_id", "accountId",
    "expires_at", "expires", "expires_in",
)

private fun parseTokens(text: String): CodexTokens? {
    val root = if (text.count { it == '.' } == 2 && text.length > 40 && !text.startsWith("{")) {
        buildJsonObject { putJsonObject("tokens") { put("access_token", text) } }
    } else {
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    }
    val source = root["tokens"] as? JsonObject ?: root
    val access = source.stringValue("access_token", "access") ?: root.stringValue("access_token", "access")
        ?: return null
    val refresh = source.stringValue("refresh_token", "refresh") ?: root.stringValue("refresh_token", "refresh").orEmpty()
    val account = accountIdFromAccessToken(access) ?: source.stringValue("account_id", "accountId")
        ?: root.stringValue("account_id", "accountId")
    val expiry = expiryFromAccessToken(access) ?: (source.longValue("expires_at", "expires")
        ?: root.longValue("expires_at", "expires"))?.let { if (it > 10_000_000_000L) it else it * 1000 }
    return CodexTokens(access, refresh, account, expiry, root)
}

private fun jwtPayload(token: String): JsonObject? {
    val parts = token.split('.')
    if (parts.size != 3) return null
    return runCatching {
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)) as? JsonObject
    }.getOrNull()
}

private fun accountIdFromAccessToken(token: String): String? {
    val payload = jwtPayload(token) ?: return null
    return (payload["https://api.openai.com/auth"] as? JsonObject)?.stringValue("chatgpt_account_id")
        ?: payload.stringValue("chatgpt_account_id")
}

private fun expiryFromAccessToken(token: String): Long? = jwtPayload(token)?.longValue("exp")?.let {
    if (it > 10_000_000_000L) it else it * 1000
}
