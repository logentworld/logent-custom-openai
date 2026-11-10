package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.ApiMessage
import com.sleepysoong.logent.plugin.PluginAiException
import com.sleepysoong.logent.plugin.PluginAiProvider
import com.sleepysoong.logent.plugin.PluginAiRequest
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginHttpResponse
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.ProcessResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomOpenAiPluginTest {
    @Test
    fun `Go 대화 ID가 없거나 헤더를 오염시키면 HTTP 전송 전에 실패해요`() {
        val provider = CustomOpenAiPlugin().services().filterIsInstance<PluginAiProvider>().single()
        for (conversationId in listOf(null, "", " ", "대화", "session\r\nInjected: value", "session\u007f")) {
            val host = FakeHost(
                values = mapOf(
                    "endpoint" to "https://opencode.ai/inference/go/openai/v1/chat/completions",
                    "api-key" to "secret",
                ),
                aiConversationId = conversationId,
            )
            val failure = runCatching { provider.open(host) }.exceptionOrNull()
            assertTrue(failure is PluginAiException)
            assertTrue(failure!!.message.orEmpty().contains("대화 ID"))
            assertEquals(null, host.lastBody)
        }
    }

    @Test
    fun `Go와 비슷한 다른 호스트와 종량제 경로는 대화 ID 없이도 기존 요청을 보내요`() = runBlocking {
        val provider = CustomOpenAiPlugin().services().filterIsInstance<PluginAiProvider>().single()
        for (endpoint in listOf(
            "https://opencode.ai.example.com/inference/go/openai/v1/chat/completions",
            "https://example.com/inference/go/openai/v1/chat/completions",
            "https://opencode.ai/inference/openai/v1/chat/completions",
            "https://opencode.ai/inference/gopher/openai/v1/chat/completions",
            "https://example.com/v1/chat/completions?next=https://opencode.ai/inference/go/",
        )) {
            val host = FakeHost(mapOf("endpoint" to endpoint, "api-key" to "secret"))
            val result = provider.open(host).complete(
                PluginAiRequest(
                    model = "demo-model",
                    messages = listOf(ApiMessage("user", JsonPrimitive("안녕"))),
                ),
            )
            assertEquals("완료", (result.message.content as JsonPrimitive).content)
            assertEquals(null, host.lastHeaders["x-opencode-session"])
            assertTrue(host.lastProtectedHeaders.isEmpty())
        }
    }

    @Test
    fun `과금 경로의 잔액 부족 오류는 Go 경로 안내를 붙여요`() = runBlocking {
        val provider = CustomOpenAiPlugin().services().filterIsInstance<PluginAiProvider>().single()
        val host = FakeHost(
            mapOf(
                "endpoint" to "https://opencode.ai/inference/openai/v1/chat/completions",
                "api-key" to "secret",
            ),
            httpStatus = 402,
            httpBody = """{"error":{"type":"invalid_request_error","message":"Insufficient account funds"}}""",
        )
        val failure = runCatching {
            provider.open(host).complete(
                PluginAiRequest("deepseek-v4.1-flash", listOf(ApiMessage("user", JsonPrimitive("hi")))),
            )
        }.exceptionOrNull()
        assertTrue(failure is PluginAiException)
        val message = (failure as? PluginAiException)?.message.orEmpty()
        assertTrue(message, message.contains("Insufficient account funds"))
        assertTrue(message, message.contains("/inference/go/openai/v1/chat/completions"))
        assertTrue(message, message.contains("/zen/go/v1/chat/completions"))
    }

    @Test
    fun `Zen Go 경로도 대화 헤더와 리다이렉트 보호를 선언해요`() = runBlocking {
        val provider = CustomOpenAiPlugin().services().filterIsInstance<PluginAiProvider>().single()
        val host = FakeHost(
            mapOf(
                "endpoint" to "https://opencode.ai/zen/go/v1/chat/completions",
                "api-key" to "secret",
            ),
            aiConversationId = "session-zen",
        )
        val result = provider.open(host).complete(
            PluginAiRequest("deepseek-v4.1-flash", listOf(ApiMessage("user", JsonPrimitive("hi")))),
        )
        assertEquals("완료", (result.message.content as JsonPrimitive).content)
        assertEquals("session-zen", host.lastHeaders["x-opencode-session"])
        assertTrue(host.lastHeaders["User-Agent"].orEmpty().startsWith("Logent/"))
        assertEquals(setOf("x-opencode-session"), host.lastProtectedHeaders)
    }

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
                "",
                """data: {"choices":[{"delta":{"content":"안"}}]}""",
                "",
                """data: {"choices":[{"delta":{"content":"녕"}}]}""",
                "",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"demo","arguments":"{\"x\":"}}]}}]}""",
                "",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}""",
                "",
                "data: [DONE]",
                "",
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

    @Test
    fun `stream true를 무시한 JSON 응답은 일반 완료로 대체해요`() = runBlocking {
        val provider = CustomOpenAiPlugin().services().filterIsInstance<PluginAiProvider>().single()
        val host = FakeHost(
            mapOf("endpoint" to "https://example.com/v1/chat/completions", "api-key" to "secret"),
            streamBody = """{"model":"demo","choices":[{"message":{"role":"assistant","content":"완료"}}]}""",
        )
        val result = provider.open(host).completeStreaming(
            PluginAiRequest("demo-model", listOf(ApiMessage("user", JsonPrimitive("질문")))),
            onEvent = {},
        )
        assertEquals("완료", (result.message.content as JsonPrimitive).content)
    }

    @Test
    fun `완료 표시 없이 끊긴 스트림은 실패해요`() = runBlocking {
        val provider = CustomOpenAiPlugin().services().filterIsInstance<PluginAiProvider>().single()
        val host = FakeHost(
            mapOf("endpoint" to "https://example.com/v1/chat/completions", "api-key" to "secret"),
            streamLines = listOf("""data: {"choices":[{"delta":{"content":"부분"}}]}""", ""),
        )
        val result = runCatching {
            provider.open(host).completeStreaming(
                PluginAiRequest("demo-model", listOf(ApiMessage("user", JsonPrimitive("질문")))),
                onEvent = {},
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `세션 cancel은 진행 중인 스트림 요청을 취소해요`() = runBlocking {
        val provider = CustomOpenAiPlugin().services().filterIsInstance<PluginAiProvider>().single()
        val session = provider.open(
            FakeHost(
                mapOf("endpoint" to "https://example.com/v1/chat/completions", "api-key" to "secret"),
                streamDelayMs = 10_000,
            ),
        )
        val job = launch {
            session.completeStreaming(
                PluginAiRequest("demo-model", listOf(ApiMessage("user", JsonPrimitive("질문")))),
                onEvent = {},
            )
        }
        delay(200)
        session.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    private class FakeHost(
        private val values: Map<String, String>,
        private val streamLines: List<String> = emptyList(),
        private val streamBody: String = "",
        private val streamDelayMs: Long = 0,
        private val httpStatus: Int = 200,
        private val httpBody: String? = null,
        override val aiConversationId: String? = null,
    ) : PluginHost {
        var lastBody: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var lastProtectedHeaders: Set<String> = emptySet()
        override val appVersion = "test"
        override val apkPath = "/tmp/app.apk"

        override suspend fun shell(command: String, timeoutSeconds: Int) =
            Result.success(ProcessResult(0, "", false))

        override suspend fun ensureDesktop() = Result.success(Unit)
        override suspend fun readAsset(path: String) = Result.failure<ByteArray>(UnsupportedOperationException())
        override fun setting(key: String): String? = values[key]
        override fun progress(message: String) = Unit

        override suspend fun http(request: PluginHttpRequest): Result<PluginHttpResponse> = respond(request)

        override suspend fun http(
            request: PluginHttpRequest,
            protectedHeaders: Set<String>,
        ): Result<PluginHttpResponse> {
            lastProtectedHeaders = protectedHeaders
            return respond(request)
        }

        override suspend fun httpStream(
            request: PluginHttpRequest,
            onLine: (String) -> Unit,
        ): Result<PluginHttpResponse> = respondStream(request, onLine)

        override suspend fun httpStream(
            request: PluginHttpRequest,
            onLine: (String) -> Unit,
            protectedHeaders: Set<String>,
        ): Result<PluginHttpResponse> {
            lastProtectedHeaders = protectedHeaders
            return respondStream(request, onLine)
        }

        private fun respond(request: PluginHttpRequest): Result<PluginHttpResponse> {
            lastBody = request.body
            lastHeaders = request.headers
            return Result.success(
                PluginHttpResponse(
                    httpStatus,
                    emptyMap(),
                    httpBody ?: """{"model":"demo","choices":[{"message":{"role":"assistant","content":"완료","tool_calls":[{"id":"call-1","type":"function","function":{"name":"demo","arguments":"{}"}}]}}],"usage":{"prompt_tokens":2,"completion_tokens":3}}""",
                ),
            )
        }

        private suspend fun respondStream(
            request: PluginHttpRequest,
            onLine: (String) -> Unit,
        ): Result<PluginHttpResponse> {
            lastBody = request.body
            lastHeaders = request.headers
            if (streamDelayMs > 0) delay(streamDelayMs)
            if (streamBody.isNotEmpty()) return Result.success(PluginHttpResponse(200, emptyMap(), streamBody))
            streamLines.forEach(onLine)
            return Result.success(PluginHttpResponse(200, emptyMap(), ""))
        }
    }
}
