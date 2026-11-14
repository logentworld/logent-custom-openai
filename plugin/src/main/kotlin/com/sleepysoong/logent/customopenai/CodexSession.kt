package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.ChatResult
import com.sleepysoong.logent.plugin.PluginAiException
import com.sleepysoong.logent.plugin.PluginAiRequest
import com.sleepysoong.logent.plugin.PluginAiStreamEvent
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginHttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Codex 구독의 Responses 프로토콜을 공개 호스트 HTTP로만 실행해요. */
internal class CodexSession(
    private val host: PluginHost,
    private val profile: ProviderProfile,
    private val credentials: CodexCredentials,
) : RequestSession() {
    override suspend fun complete(request: PluginAiRequest): ChatResult = runRequest {
        perform(request) {}
    }

    override suspend fun completeStreaming(
        request: PluginAiRequest,
        onEvent: (PluginAiStreamEvent) -> Unit,
    ): ChatResult = runRequest { perform(request, onEvent) }

    private suspend fun perform(
        request: PluginAiRequest,
        onEvent: (PluginAiStreamEvent) -> Unit,
    ): ChatResult {
        val tokens = credentials.tokens(host, profile.id)
        val accumulator = ResponsesStream(onEvent)
        val response = host.httpStream(
            PluginHttpRequest(
                method = "POST",
                url = profile.endpoint,
                headers = buildMap {
                    put("Authorization", "Bearer ${tokens.accessToken}")
                    tokens.accountId?.let { put("chatgpt-account-id", it) }
                    put("OpenAI-Beta", "responses=experimental")
                    put("originator", "omp")
                    put("version", "0.159.0")
                    put("User-Agent", "codex_cli_rs/0.159.0 (Logent)")
                    put("Content-Type", "application/json")
                    put("Accept", "text/event-stream")
                },
                body = requestBody(request).toString(),
                timeoutSeconds = 120,
                maxResponseBytes = 4 * 1024 * 1024,
            ),
            accumulator::accept,
            setOf("chatgpt-account-id"),
        ).getOrThrow()
        if (response.status !in 200..299) throw response.codexError()
        if (!accumulator.hasData && response.body.isNotBlank()) accumulator.ingest(response.body)
        return accumulator.finish()
    }

    private fun requestBody(request: PluginAiRequest): JsonObject {
        val instructions = StringBuilder()
        val input = buildJsonArray {
            request.messages.forEach { message ->
                when (message.role) {
                    "system", "developer" -> {
                        val text = message.content.asText()
                        if (text.isNotBlank()) {
                            if (instructions.isNotEmpty()) instructions.append("\n\n")
                            instructions.append(text)
                        }
                    }
                    "tool" -> {
                        val callId = message.toolCallId?.takeIf { it.isNotBlank() } ?: return@forEach
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", message.content.asText())
                        })
                    }
                    "assistant" -> {
                        val parts = message.content.asContentParts(assistant = true)
                        if (parts.isNotEmpty()) add(buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            put("status", "completed")
                            put("content", JsonArray(parts))
                        })
                        message.toolCalls.orEmpty().forEach { call ->
                            add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.function.name)
                                put("arguments", call.function.arguments.ifBlank { "{}" })
                            })
                        }
                    }
                    else -> add(buildJsonObject {
                        put("type", "message")
                        put("role", "user")
                        put("content", JsonArray(message.content.asContentParts(assistant = false)))
                    })
                }
            }
        }
        return buildJsonObject {
            put("model", request.model.trim().ifEmpty { profile.model })
            if (instructions.isNotEmpty()) put("instructions", instructions.toString())
            put("input", input)
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { spec ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", spec.function.name)
                            put("description", spec.function.description)
                            put("parameters", spec.function.parameters)
                            put("strict", false)
                        })
                    }
                })
                put("tool_choice", "auto")
            }
            put("stream", true)
            put("store", false)
            putJsonObject("reasoning") { put("effort", "medium") }
        }
    }
}

private fun JsonElement?.asContentParts(assistant: Boolean): List<JsonElement> {
    val textType = if (assistant) "output_text" else "input_text"
    fun textPart(text: String) = buildJsonObject {
        put("type", textType)
        put("text", text)
        if (assistant) put("annotations", buildJsonArray {})
    }
    return when (this) {
        null, is JsonNull -> emptyList()
        is JsonPrimitive -> if (content.isEmpty()) emptyList() else listOf(textPart(content))
        is JsonArray -> mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            when (obj.stringValue("type")) {
                "text", "input_text", "output_text" -> obj.stringValue("text")?.let(::textPart)
                "image_url", "input_image" -> {
                    if (assistant) return@mapNotNull null
                    val url = (obj["image_url"] as? JsonObject)?.stringValue("url")
                        ?: obj.stringValue("image_url")
                    url?.let { buildJsonObject {
                        put("type", "input_image")
                        put("image_url", it)
                        put("detail", "auto")
                    } }
                }
                else -> null
            }
        }
        else -> emptyList()
    }
}

private fun JsonElement?.asText(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> content
    is JsonArray -> joinToString("\n") { part ->
        (part as? JsonObject)?.stringValue("text") ?: (part as? JsonPrimitive)?.content.orEmpty()
    }
    else -> toString()
}

private fun PluginHttpResponse.codexError(): PluginAiException {
    val detail = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.apiMessage()
    val message = when (status) {
        400 -> "요청 형식이 올바르지 않아요. 모델과 대화 기록을 확인해 주세요."
        401 -> "Codex 로그인이 만료됐어요. ChatGPT 계정으로 다시 로그인해 주세요."
        403 -> "이 Codex 기능에 접근할 수 없어요. 계정의 플랜과 권한을 확인해 주세요."
        404 -> "Codex 모델 또는 엔드포인트를 찾을 수 없어요."
        429 -> "Codex 사용량 한도를 초과했어요. 잠시 후 다시 시도하거나 사용량을 확인해 주세요."
        in 500..599 -> "Codex 서버 오류예요. 잠시 후 다시 시도해 주세요."
        else -> "Codex 요청이 실패했어요. (HTTP $status)"
    }
    return PluginAiException(message + detail?.let { " · $it" }.orEmpty(), status, retryAfterSeconds())
}

internal fun JsonObject.stringValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

internal fun JsonObject.longValue(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
}

internal fun JsonObject.apiMessage(): String? {
    val error = this["error"]
    return (stringValue("error_description") ?: when (error) {
        is JsonObject -> error.stringValue("message", "code")
        is JsonPrimitive -> error.content
        else -> stringValue("detail", "message")
    })?.trim()?.take(200)?.takeIf { it.isNotBlank() }
}
