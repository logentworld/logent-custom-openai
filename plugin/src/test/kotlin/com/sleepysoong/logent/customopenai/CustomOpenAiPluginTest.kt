package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.ApiMessage
import com.sleepysoong.logent.plugin.PluginAiProvider
import com.sleepysoong.logent.plugin.PluginAiRequest
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginHttpResponse
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.ProcessResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomOpenAiPluginTest {
    @Test
    fun `provider는 공용 services 계약으로 등록되고 응답과 도구 호출을 해석해요`() = runBlocking {
        val plugin = CustomOpenAiPlugin()
        val provider = plugin.services().filterIsInstance<PluginAiProvider>().single()
        assertEquals("custom-openai", provider.id)
        assertEquals("api-key", provider.apiKeySetting)
        val host = FakeHost(
            mapOf(
                "endpoint" to "https://example.com/v1/chat/completions",
                "api-key" to "secret",
            ),
        )
        val result = provider.open(host).complete(
            PluginAiRequest(
                model = "demo-model",
                messages = listOf(ApiMessage("user", JsonPrimitive("안녕"))),
            ),
        )
        assertEquals("완료", (result.message.content as JsonPrimitive).content)
        assertEquals("call-1", result.message.toolCalls?.single()?.id)
        assertEquals("demo", result.responseModel)
        assertTrue(host.lastBody!!.contains("\"model\":\"demo-model\""))
        assertTrue(host.lastBody!!.contains("\"stream\":false"))
    }

    @Test
    fun `스트리밍은 사고·본문·도구 인자를 누적해요`() = runBlocking {
        val plugin = CustomOpenAiPlugin()
        val provider = plugin.services().filterIsInstance<PluginAiProvider>().single()
        val host = FakeHost(
            mapOf(
                "endpoint" to "https://example.com/v1/chat/completions",
                "api-key" to "secret",
            ),
            streamLines = listOf(
                """data: {"model":"demo","choices":[{"delta":{"reasoning_content":"생각"}}]}""",
                """data: {"choices":[{"delta":{"content":"안"}}]}""",
                """data: {"choices":[{"delta":{"content":"녕"}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"demo","arguments":"{\"x\":"}}]}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}""",
                "data: [DONE]",
            ),
        )
        val reasoning = mutableListOf<String>()
        val content = mutableListOf<String>()
        val result = provider.open(host).completeStreaming(
            PluginAiRequest("demo-model", listOf(ApiMessage("user", JsonPrimitive("질문")))),
            onEvent = { event ->
                when (event) {
                    is com.sleepysoong.logent.plugin.PluginAiStreamEvent.Reasoning -> reasoning += event.delta
                    is com.sleepysoong.logent.plugin.PluginAiStreamEvent.Content -> content += event.delta
                }
            },
        )
        assertEquals(listOf("생각"), reasoning)
        assertEquals(listOf("안", "녕"), content)
        assertEquals("안녕", (result.message.content as JsonPrimitive).content)
        assertEquals("""{"x":1}""", result.message.toolCalls?.single()?.function?.arguments)
        assertTrue(host.lastBody!!.contains("\"stream\":true"))
    }

    private class FakeHost(
        private val values: Map<String, String>,
        private val streamLines: List<String> = emptyList(),
    ) : PluginHost {
        var lastBody: String? = null
        override val appVersion = "test"
        override val apkPath = "/tmp/app.apk"

        override suspend fun shell(command: String, timeoutSeconds: Int) =
            Result.success(ProcessResult(0, "", false))

        override suspend fun ensureDesktop() = Result.success(Unit)
        override suspend fun readAsset(path: String) = Result.failure<ByteArray>(UnsupportedOperationException())
        override fun setting(key: String): String? = values[key]
        override fun progress(message: String) = Unit

        override suspend fun http(request: PluginHttpRequest): Result<PluginHttpResponse> {
            lastBody = request.body
            return Result.success(
                PluginHttpResponse(
                    200,
                    emptyMap(),
                    """{"model":"demo","choices":[{"message":{"role":"assistant","content":"완료","tool_calls":[{"id":"call-1","type":"function","function":{"name":"demo","arguments":"{}"}}]}}],"usage":{"prompt_tokens":2,"completion_tokens":3}}""",
                ),
            )
        }

        override suspend fun httpStream(
            request: PluginHttpRequest,
            onLine: (String) -> Unit,
        ): Result<PluginHttpResponse> {
            lastBody = request.body
            streamLines.forEach(onLine)
            return Result.success(PluginHttpResponse(200, emptyMap(), ""))
        }
    }
}
