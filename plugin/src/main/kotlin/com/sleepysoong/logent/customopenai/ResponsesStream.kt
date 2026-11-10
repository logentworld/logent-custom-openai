package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.ApiFunctionCall
import com.sleepysoong.logent.plugin.ApiMessage
import com.sleepysoong.logent.plugin.ApiToolCall
import com.sleepysoong.logent.plugin.ChatResult
import com.sleepysoong.logent.plugin.PluginAiException
import com.sleepysoong.logent.plugin.PluginAiStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Responses의 델타와 최종 스냅샷을 중복하지 않고 하나의 완료 응답으로 합쳐요. */
internal class ResponsesStream(private val onEvent: (PluginAiStreamEvent) -> Unit) {
    private val data = StringBuilder()
    private val text = StringBuilder()
    private var finalText = ""
    private val calls = linkedMapOf<String, Call>()
    private val itemCalls = mutableMapOf<String, String>()
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null
    private var responseModel: String? = null
    private var conflictingModels = false
    private var completed = false
    private var failure: String? = null
    var hasData = false
        private set

    fun accept(line: String) {
        if (line.isBlank()) {
            flush()
        } else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.removePrefix("data:").trimStart())
            if (data.length == 6 && data.toString() == "[DONE]") flush()
        }
    }

    fun ingest(body: String) {
        val response = parse(body)
        hasData = true
        completed = true
        collect(response)
    }

    fun finish(): ChatResult {
        flush()
        failure?.let { throw PluginAiException(it) }
        if (!completed) throw PluginAiException("Codex 스트림이 완료되기 전에 끊겼어요.")
        val content = text.toString().ifEmpty { finalText }
        val toolCalls = calls.values.mapNotNull { call ->
            val name = call.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ApiToolCall(call.id, "function", ApiFunctionCall(name, call.arguments.toString().ifEmpty { "{}" }))
        }.ifEmpty { null }
        if (content.isEmpty() && toolCalls == null) throw PluginAiException("Codex 모델이 빈 응답을 반환했어요.")
        return ChatResult(
            ApiMessage("assistant", content.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive), toolCalls = toolCalls),
            inputTokens, outputTokens, responseModel,
        )
    }

    private fun flush() {
        val payload = data.toString().trim()
        data.setLength(0)
        if (payload.isEmpty()) return
        hasData = true
        if (payload == "[DONE]") {
            completed = true
            return
        }
        val event = parse(payload)
        when (event.stringValue("type")) {
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta", "response.reasoning.delta" -> {
                event.stringValue("delta")?.let { onEvent(PluginAiStreamEvent.Reasoning(it)) }
            }
            "response.output_text.delta", "response.refusal.delta" -> {
                event.stringValue("delta")?.let {
                    text.append(it)
                    onEvent(PluginAiStreamEvent.Content(it))
                }
            }
            "response.output_item.added", "response.output_item.done" -> {
                val item = event["item"] as? JsonObject ?: return
                when (item.stringValue("type")) {
                    "function_call" -> addCall(item)
                    "message" -> if (text.isEmpty() && finalText.isEmpty()) finalText = messageText(item)
                }
            }
            "response.function_call_arguments.delta" -> {
                val id = event.stringValue("item_id")?.let { itemCalls[it] } ?: return
                val call = calls[id] ?: return
                event.stringValue("delta")?.let { call.arguments.append(it) }
            }
            "response.function_call_arguments.done" -> {
                val id = event.stringValue("item_id")?.let { itemCalls[it] } ?: return
                val call = calls[id] ?: return
                event.stringValue("arguments")?.let { call.arguments.setLength(0); call.arguments.append(it) }
            }
            "response.completed", "response.done", "response.incomplete" -> {
                completed = true
                (event["response"] as? JsonObject)?.let(::collect)
            }
            "response.failed", "error" -> {
                completed = true
                val detail = (event["response"] as? JsonObject)?.apiMessage() ?: event.apiMessage()
                failure = "Codex 응답이 실패했어요." + detail?.let { " · $it" }.orEmpty()
            }
        }
    }

    private fun collect(response: JsonObject) {
        if (response.stringValue("status") == "failed" || response["error"] is JsonObject) {
            failure = "Codex 응답이 실패했어요." + response.apiMessage()?.let { " · $it" }.orEmpty()
        }
        response.stringValue("model")?.let { model ->
            if (!conflictingModels) {
                if (responseModel != null && responseModel != model) {
                    responseModel = null
                    conflictingModels = true
                } else responseModel = model
            }
        }
        (response["usage"] as? JsonObject)?.let { usage ->
            inputTokens = usage.longValue("input_tokens")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
            outputTokens = usage.longValue("output_tokens")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        }
        val output = response["output"] as? JsonArray ?: return
        val final = if (text.isEmpty()) StringBuilder() else null
        output.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            when (item.stringValue("type")) {
                "function_call" -> addCall(item)
                "message" -> final?.append(messageText(item))
            }
        }
        if (final != null && final.isNotEmpty()) finalText = final.toString()
    }

    private fun addCall(item: JsonObject) {
        val id = item.stringValue("call_id", "id") ?: return
        val call = calls.getOrPut(id) { Call(id) }
        item.stringValue("id")?.let { itemCalls[it] = id }
        item.stringValue("name")?.let { call.name = it }
        item["arguments"]?.let { arguments ->
            call.arguments.setLength(0)
            call.arguments.append(if (arguments is JsonPrimitive && arguments.isString) arguments.content else arguments.toString())
        }
    }

    private fun messageText(item: JsonObject): String = (item["content"] as? JsonArray)?.joinToString("") { part ->
        val obj = part as? JsonObject ?: return@joinToString ""
        when (obj.stringValue("type")) {
            "output_text", "text", "refusal" -> obj.stringValue("text", "refusal").orEmpty()
            else -> ""
        }
    }.orEmpty()

    private fun parse(payload: String): JsonObject =
        runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: throw PluginAiException("Codex 응답을 해석할 수 없어요.")

    private class Call(val id: String) {
        var name: String? = null
        val arguments = StringBuilder()
    }
}
