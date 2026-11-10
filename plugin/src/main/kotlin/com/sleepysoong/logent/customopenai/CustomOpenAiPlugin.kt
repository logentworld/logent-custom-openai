package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.ApiFunctionCall
import com.sleepysoong.logent.plugin.ApiMessage
import com.sleepysoong.logent.plugin.ApiToolCall
import com.sleepysoong.logent.plugin.ChatResult
import com.sleepysoong.logent.plugin.LogentPlugin
import com.sleepysoong.logent.plugin.PluginAiException
import com.sleepysoong.logent.plugin.PluginAiProvider
import com.sleepysoong.logent.plugin.PluginAiRequest
import com.sleepysoong.logent.plugin.PluginAiSession
import com.sleepysoong.logent.plugin.PluginAiStreamEvent
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginHttpResponse
import com.sleepysoong.logent.plugin.PluginSetting
import com.sleepysoong.logent.plugin.PluginSettingGroup
import com.sleepysoong.logent.plugin.PluginService
import com.sleepysoong.logent.plugin.PluginSettingKind
import com.sleepysoong.logent.plugin.ToolSpec
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI

/**
 * 여러 OpenAI 호환·NVIDIA NIM·Codex 구독 프로필을 AI 제공자로 기여해요.
 * 인증과 모델은 프로필이 소유하며 모델 화면에서 키를 다시 입력하지 않아요.
 */
class CustomOpenAiPlugin : LogentPlugin {
    override val id = "com.sleepysoong.logent.custom-openai"
    override val name = "AI 제공자"
    override val version = BuildConfig.PLUGIN_VERSION

    private val codexAuth = ConcurrentHashMap<String, CodexAuthStore>()

    override fun settingGroups() = listOf(
        PluginSettingGroup(
            key = PROVIDERS_GROUP,
            title = "제공자",
            description = "서로 다른 서버·계정·모델을 프로필별로 관리해요.",
            itemTitleKey = "name",
            addLabel = "제공자 추가",
            fields = listOf(
                PluginSetting("name", "이름", defaultValue = "AI 제공자"),
                PluginSetting(
                    "type", "종류",
                    kind = PluginSettingKind.SELECT,
                    defaultValue = OPENAI_TYPE,
                    options = listOf(OPENAI_TYPE, NVIDIA_TYPE, CODEX_TYPE),
                ),
                PluginSetting(
                    "endpoint", "Endpoint",
                    description = "HTTPS base 또는 전체 요청 주소예요. NVIDIA·Codex에서는 비우거나 OpenAI 기본값을 두면 종류별 기본 주소를 써요.",
                    defaultValue = OPENAI_ENDPOINT,
                ),
                PluginSetting(
                    "model", "모델",
                    description = "이 프로필이 실행할 모델 ID예요. NVIDIA·Codex에서는 비우거나 OpenAI 기본값을 두면 종류별 기본 모델을 써요.",
                    defaultValue = OPENAI_MODEL,
                ),
                PluginSetting(
                    "api-key", "API 키",
                    description = "선택 입력이에요. 키 없는 OpenAI 호환 서버는 인증 헤더를 보내지 않아요. NVIDIA는 키가 필요해요.",
                    kind = PluginSettingKind.SECRET,
                ),
                PluginSetting(
                    "auth-json", "Codex 인증 JSON",
                    description = "선택 입력이에요. 비어 있으면 최초 요청 때 Termux의 ~/.codex/auth.json을 읽고 갱신 토큰을 이 프로필에 저장해요.",
                    kind = PluginSettingKind.SECRET,
                ),
            ),
        ),
    )

    override fun services(host: PluginHost): List<PluginService> =
        host.settingEntries(PROVIDERS_GROUP).map { entry ->
            ProfileProvider(ProviderProfile(entry))
        }

    private inner class ProfileProvider(private val declared: ProviderProfile) : PluginAiProvider {
        override val id = declared.id
        override val name = declared.name
        override val description = declared.type
        override val defaultModel = declared.model
        override val apiKeySetting: String? = null

        override fun open(host: PluginHost): PluginAiSession {
            val entry = host.settingEntries(PROVIDERS_GROUP).firstOrNull { it.id == declared.id }
                ?: throw PluginAiException("선택한 제공자 프로필이 삭제됐어요. 다른 프로필을 선택해 주세요.")
            val profile = ProviderProfile(entry)
            profile.validate()
            return when (profile.type) {
                CODEX_TYPE -> CodexSession(
                    host, profile,
                    codexAuth.getOrPut(profile.id) { CodexAuthStore() }.credentials(profile.authJson),
                )
                else -> CustomSession(host, profile)
            }
        }
    }

    private class CustomSession(
        private val host: PluginHost,
        private val profile: ProviderProfile,
    ) : RequestSession() {
        private val endpoint = normalizeEndpoint(profile.endpoint)
        private val goSessionId: String? = if (isOpenCodeGoEndpoint(endpoint)) {
            val conversationId = host.aiConversationId
                ?: throw PluginAiException("OpenCode Go에는 실제 AI 대화 ID가 필요해요. 대화가 연결된 Logent에서 실행해 주세요.")
            if (conversationId.isEmpty() || conversationId.any { it !in '!'..'~' }) {
                throw PluginAiException("OpenCode Go 대화 ID가 안전한 HTTP 헤더 값이 아니에요.")
            }
            conversationId
        } else {
            null
        }
        /** 다른 출처로 리다이렉트될 때 대화 식별자를 넘기지 않도록 호스트에 직접 알려요. */
        private val protectedHeaders: Set<String> =
            if (goSessionId == null) emptySet() else setOf(SESSION_HEADER)
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        override suspend fun complete(request: PluginAiRequest): ChatResult = runRequest {
            completeOnce(request)
        }

        override suspend fun completeStreaming(
            request: PluginAiRequest,
            onEvent: (PluginAiStreamEvent) -> Unit,
        ): ChatResult = runRequest {
            completeStreamingOnce(request, onEvent)
        }


        private suspend fun completeOnce(request: PluginAiRequest): ChatResult {
            val response = host.http(httpRequest(request, stream = false), protectedHeaders).getOrThrow()
            ensureSuccess(response)
            return parseCompletion(response.body)
        }

        private suspend fun completeStreamingOnce(
            request: PluginAiRequest,
            onEvent: (PluginAiStreamEvent) -> Unit,
        ): ChatResult {
            val state = StreamState(json, onEvent)
            val result = host.httpStream(httpRequest(request, stream = true), { state.accept(it) }, protectedHeaders)
            if (result.exceptionOrNull() is UnsupportedOperationException) {
                return completeOnce(request)
            }
            val response = result.getOrThrow()
            ensureSuccess(response)
            // 일부 OpenAI 호환 서버는 stream:true를 무시하고 일반 JSON을 돌려줘요.
            if (!state.hasData && response.body.isNotBlank()) return parseCompletion(response.body)
            return state.finish()
        }

        private fun httpRequest(request: PluginAiRequest, stream: Boolean): PluginHttpRequest =
            PluginHttpRequest(
                method = "POST",
                url = endpoint,
                headers = buildMap {
                    if (profile.apiKey.isNotEmpty()) put("Authorization", "Bearer ${profile.apiKey}")
                    put("Content-Type", "application/json")
                    put("Accept", if (stream) "text/event-stream" else "application/json")
                    put("User-Agent", "Logent/${host.appVersion}")
                    if (goSessionId != null) put(SESSION_HEADER, goSessionId)
                },
                body = requestBody(request, stream).toString(),
                timeoutSeconds = 120,
                maxResponseBytes = 4 * 1024 * 1024,
            )

        private fun requestBody(request: PluginAiRequest, stream: Boolean): JsonObject = buildJsonObject {
            put("model", profile.model)
            put("stream", stream)
            put("messages", buildJsonArray {
                request.messages.forEach { add(json.encodeToJsonElement(ApiMessage.serializer(), it)) }
            })
            if (request.tools.isNotEmpty()) {
                put("tools", json.encodeToJsonElement(ListSerializer(ToolSpec.serializer()), request.tools))
                put("tool_choice", "auto")
            }
            if (stream) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
        }

        private fun ensureSuccess(response: PluginHttpResponse) {
            if (response.status in 200..299) return
            if (profile.type == NVIDIA_TYPE) {
                val detail = errorMessage(response.body)
                val message = when (response.status) {
                    401 -> "NVIDIA API 키가 거부됐어요. build.nvidia.com에서 키를 다시 확인해 주세요."
                    402 -> "NVIDIA 크레딧이 부족해요. build.nvidia.com에서 결제 정보를 확인해 주세요."
                    403 -> "이 모델에 접근할 수 없어요. NVIDIA 계정의 모델 접근 권한을 확인해 주세요."
                    404 -> "NVIDIA 모델 또는 엔드포인트를 찾을 수 없어요. 프로필의 모델 ID를 확인해 주세요."
                    429 -> "NVIDIA 요청 한도를 초과했어요. 잠시 후 다시 시도해 주세요."
                    in 500..599 -> "NVIDIA 서버 오류예요. 잠시 후 다시 시도해 주세요."
                    else -> "NVIDIA 요청이 실패했어요. (HTTP ${response.status})"
                }
                throw PluginAiException(
                    message + detail?.let { " · $it" }.orEmpty(),
                    response.status, response.retryAfterSeconds(),
                )
            }
            val message = errorMessage(response.body) ?: "OpenAI 호환 서버가 ${response.status}로 응답했어요."
            val hint = if (response.status == 402 &&
                message.contains("Insufficient account funds", ignoreCase = true) &&
                isOpenCodePayAsYouGoEndpoint(endpoint)
            ) {
                " OpenCode Go 구독은 일반 /inference 경로가 아니라 " +
                    "https://opencode.ai/inference/go/openai/v1/chat/completions 또는 " +
                    "https://opencode.ai/zen/go/v1/chat/completions 에서만 적용돼요. " +
                    "플러그인 설정의 endpoint를 Go 주소로 바꿔 주세요."
            } else {
                ""
            }
            throw PluginAiException(message + hint, response.status, response.retryAfterSeconds())
        }

        private fun parseCompletion(body: String): ChatResult {
            val root = parseObject(body)
            root["error"]?.let { throw PluginAiException(errorText(it)) }
            val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                ?.let { json.decodeFromJsonElement(ApiMessage.serializer(), it) }
                ?: throw PluginAiException("OpenAI 호환 응답에 message가 없어요.")
            return ChatResult(
                message = message,
                inputTokens = usage(root, "prompt_tokens"),
                outputTokens = usage(root, "completion_tokens"),
                responseModel = root["model"]?.stringOrNull(),
            )
        }

        private fun parseObject(body: String): JsonObject =
            runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw PluginAiException("OpenAI 호환 응답을 해석하지 못했어요.", cause = it) }

        private fun errorMessage(body: String): String? {
            val root = runCatching { parseObject(body) }.getOrNull() ?: return null
            return root["error"]?.let(::errorText)
                ?: root["detail"]?.stringOrNull()
                ?: root["message"]?.stringOrNull()
        }

        private fun errorText(element: kotlinx.serialization.json.JsonElement): String =
            (element as? JsonObject)?.get("message")?.stringOrNull()
                ?: element.stringOrNull()
                ?: "OpenAI 호환 서버가 요청을 거부했어요."

        private fun usage(root: JsonObject, key: String): Int? =
            (root["usage"] as? JsonObject)?.get(key)?.jsonPrimitive?.content?.toIntOrNull()

        private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private class StreamState(
        private val json: Json,
        private val onEvent: (PluginAiStreamEvent) -> Unit,
    ) {
        private val text = StringBuilder()
        private val calls = sortedMapOf<Int, CallBuilder>()
        private val data = StringBuilder()
        private var sawData = false
        private var finished = false
        private var inputTokens: Int? = null
        private var outputTokens: Int? = null
        private var responseModel: String? = null

        val hasData: Boolean get() = sawData || text.isNotEmpty() || calls.isNotEmpty()

        fun accept(line: String) {
            if (line.isBlank()) {
                flush()
                return
            }
            if (!line.startsWith("data:")) return
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.removePrefix("data:").trimStart())
            if (data.toString().trim() == "[DONE]") flush()
        }

        fun finish(): ChatResult {
            flush()
            if (!finished) throw PluginAiException("OpenAI 호환 스트림이 완료되기 전에 끊겼어요.")
            if (!hasData) throw PluginAiException("OpenAI 호환 스트림에 응답 데이터가 없어요.")
            val content = text.toString()
            val toolCalls = calls.map { (index, builder) ->
                ApiToolCall(
                    id = builder.id ?: "call_$index",
                    type = "function",
                    function = ApiFunctionCall(builder.name.toString(), builder.arguments.toString().ifEmpty { "{}" }),
                )
            }.ifEmpty { null }
            if (content.isEmpty() && toolCalls == null) throw PluginAiException("OpenAI 호환 모델이 빈 응답을 반환했어요.")
            return ChatResult(
                message = ApiMessage(
                    role = "assistant",
                    content = content.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) },
                    toolCalls = toolCalls,
                ),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                responseModel = responseModel,
            )
        }

        private fun flush() {
            val payload = data.toString().trim()
            data.setLength(0)
            if (payload.isEmpty()) return
            if (payload == "[DONE]") {
                finished = true
                return
            }
            sawData = true
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw PluginAiException("OpenAI 호환 스트림을 해석하지 못했어요.", cause = it) }
            root["error"]?.let { throw PluginAiException(streamError(it)) }
            responseModel = root["model"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: responseModel
            (root["usage"] as? JsonObject)?.let { usage ->
                inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: inputTokens
                outputTokens = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: outputTokens
            }
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            if (choice?.get("finish_reason")?.jsonPrimitive?.takeIf { it.isString }?.content != null) finished = true
            val delta = choice?.get("delta") as? JsonObject ?: return
            delta["content"]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onEvent(PluginAiStreamEvent.Content(it))
            }
            val reasoning = delta["reasoning"]?.jsonPrimitive?.takeIf { it.isString }?.content
                ?: delta["reasoning_content"]?.jsonPrimitive?.takeIf { it.isString }?.content
            if (!reasoning.isNullOrEmpty()) onEvent(PluginAiStreamEvent.Reasoning(reasoning))
            (delta["tool_calls"] as? JsonArray)?.forEach { element ->
                val call = element as? JsonObject ?: return@forEach
                val index = call["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val builder = calls.getOrPut(index) { CallBuilder() }
                call["id"]?.jsonPrimitive?.takeIf { it.isString }?.content?.let { builder.id = it }
                val function = call["function"] as? JsonObject ?: return@forEach
                function["name"]?.jsonPrimitive?.takeIf { it.isString }?.content?.let { builder.name.append(it) }
                function["arguments"]?.jsonPrimitive?.takeIf { it.isString }?.content?.let { builder.arguments.append(it) }
            }
        }

        private fun streamError(element: kotlinx.serialization.json.JsonElement): String =
            (element as? JsonObject)?.get("message")?.jsonPrimitive?.takeIf { it.isString }?.content
                ?: (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: "OpenAI 호환 스트림이 오류를 반환했어요."

        private class CallBuilder {
            var id: String? = null
            val name = StringBuilder()
            val arguments = StringBuilder()
        }
    }

    companion object {
        private const val SESSION_HEADER = "x-opencode-session"
        private fun isOpenCodeGoEndpoint(value: String): Boolean {
            val uri = URI(value)
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.host.equals("opencode.ai", ignoreCase = true)
            ) {
                return false
            }
            val path = uri.rawPath.orEmpty()
            return path.startsWith("/inference/go/") || path.startsWith("/zen/go/")
        }

        /**
         * 다른 하네스가 쓰는 base URL을 Chat Completions 전체 주소로 맞춰요.
         *
         * 이미 전체 주소인 값과 쿼리가 붙은 값은 건드리지 않아요.
         */
        private fun normalizeEndpoint(value: String): String {
            val uri = runCatching { URI(value) }.getOrNull() ?: return value
            if (uri.rawQuery != null) return value
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val suffix = when {
                uri.host.equals("opencode.ai", ignoreCase = true) && path == "/inference/go" ->
                    "/openai/v1/chat/completions"
                uri.host.equals("opencode.ai", ignoreCase = true) && path == "/zen/go" ->
                    "/v1/chat/completions"
                path.endsWith("/v1") -> "/chat/completions"
                else -> return value
            }
            return value.substringBefore('?').trimEnd('/') + suffix
        }

        /** OpenCode의 일반 과금 경로예요. Go 구독은 이 경로에 적용되지 않아요. */
        private fun isOpenCodePayAsYouGoEndpoint(value: String): Boolean {
            val uri = URI(value)
            val path = uri.rawPath.orEmpty()
            return uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("opencode.ai", ignoreCase = true) &&
                path.startsWith("/inference/") &&
                !path.startsWith("/inference/go/")
        }

    }
}
