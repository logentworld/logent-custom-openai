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
import com.sleepysoong.logent.plugin.PluginService
import com.sleepysoong.logent.plugin.PluginSettingKind
import com.sleepysoong.logent.plugin.ToolSpec
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
 * 사용자가 endpoint·키·모델을 지정하는 OpenAI 호환 Chat Completions 백엔드예요.
 *
 * 도구·이미지·사고 과정·SSE를 공용 API 계약으로 전달하고, 인증 키는 플러그인 설정에서만 읽어요.
 */
class CustomOpenAiPlugin : LogentPlugin {
    override val id = "com.sleepysoong.logent.custom-openai"
    override val name = "사용자 지정 OpenAI"
    override val version = BuildConfig.PLUGIN_VERSION

    override fun settings() = listOf(
        PluginSetting(
            key = "endpoint",
            title = "Chat Completions endpoint",
            description = "OpenAI 호환 HTTPS 주소예요. 예: https://api.openai.com/v1/chat/completions",
            kind = PluginSettingKind.TEXT,
            defaultValue = DEFAULT_ENDPOINT,
        ),
        PluginSetting(
            key = "api-key",
            title = "API 키",
            description = "플러그인 전용 저장소에만 보관하고 화면·로그·프롬프트에 내보내지 않아요.",
            kind = PluginSettingKind.SECRET,
        ),
    )

    override fun services(): List<PluginService> = listOf(CUSTOM_PROVIDER)

    private object CUSTOM_PROVIDER : PluginAiProvider {
        override val id = "custom-openai"
        override val name = "사용자 지정 OpenAI"
        override val description = "사용자가 지정한 OpenAI 호환 Chat Completions 서버"
        override val defaultModel = "gpt-4.1-mini"
        override val apiKeySetting = "api-key"

        override fun open(host: PluginHost): PluginAiSession {
            val endpoint = host.setting("endpoint").orEmpty().trim().ifBlank { DEFAULT_ENDPOINT }
            require(isHttpsEndpoint(endpoint)) { "endpoint는 사용자 정보가 없는 https:// 주소여야 해요." }
            val key = host.setting("api-key").orEmpty().trim()
            require(key.isNotEmpty()) { "API 키를 먼저 저장해 주세요." }
            return CustomSession(host, endpoint, key)
        }
    }

    private class CustomSession(
        private val host: PluginHost,
        private val endpoint: String,
        private val apiKey: String,
    ) : PluginAiSession {
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        override suspend fun complete(request: PluginAiRequest): ChatResult {
            val response = host.http(httpRequest(request, stream = false)).getOrThrow()
            ensureSuccess(response)
            return parseCompletion(response.body)
        }

        override suspend fun completeStreaming(
            request: PluginAiRequest,
            onEvent: (PluginAiStreamEvent) -> Unit,
        ): ChatResult {
            val state = StreamState(json, onEvent)
            val response = host.httpStream(httpRequest(request, stream = true)) { state.accept(it) }.getOrThrow()
            ensureSuccess(response)
            return state.finish()
        }

        private fun httpRequest(request: PluginAiRequest, stream: Boolean): PluginHttpRequest =
            PluginHttpRequest(
                method = "POST",
                url = endpoint,
                headers = buildMap {
                    put("Authorization", "Bearer $apiKey")
                    put("Content-Type", "application/json")
                    put("Accept", if (stream) "text/event-stream" else "application/json")
                },
                body = requestBody(request, stream).toString(),
                timeoutSeconds = 120,
                maxResponseBytes = 4 * 1024 * 1024,
            )

        private fun requestBody(request: PluginAiRequest, stream: Boolean): JsonObject = buildJsonObject {
            put("model", request.model)
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
            val message = errorMessage(response.body) ?: "OpenAI 호환 서버가 ${response.status}로 응답했어요."
            throw PluginAiException(message, response.status, response.headers["retry-after"]?.toLongOrNull())
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
        private var sawData = false
        private var inputTokens: Int? = null
        private var outputTokens: Int? = null
        private var responseModel: String? = null

        fun accept(line: String) {
            if (!line.startsWith("data:")) return
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") return
            if (payload.isEmpty()) return
            sawData = true
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw PluginAiException("OpenAI 호환 스트림을 해석하지 못했어요.", cause = it) }
            root["error"]?.let { throw PluginAiException((it as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: "스트림 오류") }
            responseModel = root["model"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: responseModel
            (root["usage"] as? JsonObject)?.let { usage ->
                inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: inputTokens
                outputTokens = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: outputTokens
            }
            val delta = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject ?: return
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

        fun finish(): ChatResult {
            if (!sawData && text.isEmpty() && calls.isEmpty()) {
                throw PluginAiException("OpenAI 호환 스트림에 응답 데이터가 없어요.")
            }
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

        private class CallBuilder {
            var id: String? = null
            val name = StringBuilder()
            val arguments = StringBuilder()
        }
    }

    companion object {
        private const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private fun isHttpsEndpoint(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
    }
}
