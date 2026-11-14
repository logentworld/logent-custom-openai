package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.ApiFunctionCall
import com.sleepysoong.logent.plugin.ApiMessage
import com.sleepysoong.logent.plugin.ApiToolCall
import com.sleepysoong.logent.plugin.FunctionSpec
import com.sleepysoong.logent.plugin.PluginAiException
import com.sleepysoong.logent.plugin.PluginAiProvider
import com.sleepysoong.logent.plugin.PluginAiRequest
import com.sleepysoong.logent.plugin.PluginAiStreamEvent
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginHttpResponse
import com.sleepysoong.logent.plugin.PluginSettingEntry
import com.sleepysoong.logent.plugin.ProcessResult
import com.sleepysoong.logent.plugin.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

class CustomOpenAiPluginTest {
    @Test
    fun `두 프로필은 키와 모델을 격리하고 수정과 재정렬 뒤에도 같은 ID를 유지해요`() = runBlocking {
        val plugin = CustomOpenAiPlugin()
        val host = RecordingHost(
            profile("alpha", "https://one.example/v1", "private-one", "model-one"),
            profile("bravo", "https://two.example/v1", "", "model-two"),
        )
        val oldProviders = providers(plugin, host)
        assertTrue(oldProviders.all { it.apiKeySetting == null })
        val oldSession = oldProviders.first().open(host)
        host.entries["alpha"] = profile("alpha", "https://changed.example/v1", "private-new", "model-new", name = "새 이름")
        host.entries.remove("alpha")?.let { host.entries["alpha"] = it }
        val next = providers(plugin, host)
        assertEquals(setOf("alpha", "bravo"), next.map { it.id }.toSet())
        val changed = next.single { it.id == "alpha" }
        assertEquals("새 이름", changed.name)
        assertEquals("model-new", changed.defaultModel)
        oldSession.complete(request())
        changed.open(host).complete(request())
        next.single { it.id == "bravo" }.open(host).complete(request())
        assertEquals(listOf("one.example", "changed.example", "two.example"), host.calls.map { java.net.URI(it.url).host })
        assertEquals(listOf("Bearer private-one", "Bearer private-new", null), host.calls.map { it.headers["Authorization"] })
        assertEquals(listOf("model-one", "model-new", "model-two"), host.calls.map { body(it).stringValue("model") })
        host.entries.remove("alpha")
        assertTrue(runCatching { oldProviders.first().open(host) }.exceptionOrNull() is PluginAiException)
        oldSession.complete(request())
        assertEquals("https://one.example/v1/chat/completions", host.calls.last().url)
    }

    @Test
    fun `Go base 주소는 실제 대화 ID를 세션 헤더로 보내고 사용량을 조회해요`() = runBlocking {
        for (base in listOf("https://opencode.ai/inference/go", "https://opencode.ai/zen/go/v1")) {
            val host = RecordingHost(profile("go", base, "go-key"), conversationId = "saved-session")
            providers(CustomOpenAiPlugin(), host).single().open(host).complete(request())
            val chat = host.calls.single { it.method == "POST" }
            assertTrue(chat.url.endsWith("/v1/chat/completions"))
            assertEquals(setOf("x-opencode-session"), host.protections[host.calls.indexOf(chat)])
            assertEquals("saved-session", chat.headers["x-opencode-session"])
            assertTrue(chat.headers["User-Agent"].orEmpty().startsWith("Logent/"))
            val usage = host.calls.firstOrNull { it.method == "GET" }
            assertTrue(usage != null && usage.url.endsWith("/v1/usage"))
        }
        val unrelated = RecordingHost(profile("other", "https://opencode.ai.example/inference/go/v1"))
        providers(CustomOpenAiPlugin(), unrelated).single().open(unrelated).complete(request())
        assertNull(unrelated.calls.single().headers["x-opencode-session"])
    }

    @Test
    fun `대화 ID가 없거나 안전하지 않으면 프로필별 고정 세션 ID로 대체해요`() = runBlocking {
        for (unsafe in listOf(null, "", "한글", "session\r\nInjected: value", "session\u007f")) {
            val host = RecordingHost(profile("go", "https://opencode.ai/inference/go", "go-key"), conversationId = unsafe)
            providers(CustomOpenAiPlugin(), host).single().open(host).complete(request())
            val value = host.calls.single { it.method == "POST" }.headers["x-opencode-session"].orEmpty()
            assertTrue(value.startsWith("logent-"))
            assertTrue(value.all { it in '!'..'~' })
        }
        val first = RecordingHost(profile("one", "https://opencode.ai/inference/go", "go-key"))
        val again = RecordingHost(profile("one", "https://opencode.ai/inference/go", "go-key"))
        val other = RecordingHost(profile("two", "https://opencode.ai/inference/go", "go-key"))
        listOf(first, again, other).forEach { host ->
            providers(CustomOpenAiPlugin(), host).single().open(host).complete(request())
        }
        assertEquals(
            first.calls.single { it.method == "POST" }.headers["x-opencode-session"],
            again.calls.single { it.method == "POST" }.headers["x-opencode-session"],
        )
        assertFalse(
            first.calls.single { it.method == "POST" }.headers["x-opencode-session"] ==
                other.calls.single { it.method == "POST" }.headers["x-opencode-session"],
        )
    }

    @Test
    fun `Go 도구 정의는 type을 포함해 직렬화해요`() = runBlocking {
        val host = RecordingHost(profile("go", "https://opencode.ai/inference/go", "go-key"), conversationId = "conv")
        val tool = ToolSpec(
            function = FunctionSpec(
                name = "get_weather",
                description = "도시 날씨를 알려 줘요.",
                parameters = Json.parseToJsonElement("""{"type":"object","properties":{"city":{"type":"string"}}}""").jsonObject,
            ),
        )
        providers(CustomOpenAiPlugin(), host).single().open(host).complete(
            PluginAiRequest("", listOf(ApiMessage("user", JsonPrimitive("질문"))), listOf(tool)),
        )
        val sent = body(host.calls.single { it.method == "POST" })
        val first = sent["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", first["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", first["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("auto", sent.stringValue("tool_choice"))
    }

    @Test
    fun `Go 프로필은 OpenAI 기본 모델 대신 Go 모델을 쓰고 모델 목록을 알려 줘요`() = runBlocking {
        val host = RecordingHost(
            profile("go", "https://opencode.ai/inference/go", "go-key", OPENAI_MODEL),
            conversationId = "conv",
        )
        val provider = providers(CustomOpenAiPlugin(), host).single()
        assertEquals(OpenCodeGo.DEFAULT_MODEL, provider.defaultModel)
        assertTrue(provider.models.any { it.id == OpenCodeGo.DEFAULT_MODEL })
        assertEquals(1_000_000, provider.contextWindow)
        provider.open(host).complete(PluginAiRequest(OPENAI_MODEL, listOf(ApiMessage("user", JsonPrimitive("질문")))))
        assertEquals(OpenCodeGo.DEFAULT_MODEL, body(host.calls.single { it.method == "POST" }).stringValue("model"))
        val plain = RecordingHost(profile("plain", "https://chat.example/v1", "key", "server-default"))
        assertEquals(emptyList<String>(), providers(CustomOpenAiPlugin(), plain).single().models.map { it.id })
    }

    @Test
    fun `모델 화면에서 고른 모델은 프로필 기본값을 덮어써요`() = runBlocking {
        val host = RecordingHost(profile("go", "https://opencode.ai/inference/go", "go-key"), conversationId = "conv")
        providers(CustomOpenAiPlugin(), host).single().open(host).complete(
            PluginAiRequest("glm-5.3", listOf(ApiMessage("user", JsonPrimitive("질문")))),
        )
        assertEquals("glm-5.3", body(host.calls.single { it.method == "POST" }).stringValue("model"))
        val plain = RecordingHost(profile("plain", "https://chat.example/v1", "key", "server-default"))
        providers(CustomOpenAiPlugin(), plain).single().open(plain).complete(
            PluginAiRequest("picked", listOf(ApiMessage("user", JsonPrimitive("질문")))),
        )
        assertEquals("picked", body(plain.calls.single { it.method == "POST" }).stringValue("model"))
    }

    @Test
    fun `Go 사용량을 읽어 설정 필드에 남은 퍼센트로 기록해요`() = runBlocking {
        val host = RecordingHost(profile("go", "https://opencode.ai/inference/go", "go-key"), conversationId = "conv")
        host.httpHandler = { request ->
            if (request.method == "GET") PluginHttpResponse(200, emptyMap(), USAGE_RESPONSE)
            else PluginHttpResponse(200, emptyMap(), CHAT_RESPONSE)
        }
        val provider = providers(CustomOpenAiPlugin(), host).single()
        provider.open(host).complete(request())
        val stored = host.entries.getValue("go").values["usage"].orEmpty()
        assertTrue(stored.contains("5시간 98% 남음"))
        assertTrue(stored.contains("주간 70% 남음"))
        assertTrue(stored.contains("월간 63% 남음"))
        assertTrue(provider.description.contains("5시간 98% 남음"))
        assertEquals(1, host.calls.count { it.method == "GET" })
        // 캐시 안에서는 사용량을 다시 부르지 않아요.
        provider.open(host).complete(request())
        assertEquals(1, host.calls.count { it.method == "GET" })
    }

    @Test
    fun `사용량 조회가 실패해도 대화와 저장된 값은 그대로예요`() = runBlocking {
        val host = RecordingHost(profile("go", "https://opencode.ai/inference/go", "go-key"), conversationId = "conv")
        host.httpHandler = { request ->
            if (request.method == "GET") PluginHttpResponse(500, emptyMap(), "{}")
            else PluginHttpResponse(200, emptyMap(), CHAT_RESPONSE)
        }
        providers(CustomOpenAiPlugin(), host).single().open(host).complete(request())
        assertEquals("", host.entries.getValue("go").values["usage"].orEmpty())
        assertEquals(0, host.successfulWrites)
    }

    @Test
    fun `Go 모델·권한 오류는 원인과 해결책을 알려 줘요`() = runBlocking {
        val cases = listOf(
            Triple(400, """{"error":{"type":"server_error","message":"Upstream request failed: Model is unavailable."}}""", "deepseek-v4.1-flash"),
            Triple(401, """{"error":{"message":"invalid api key"}}""", "API 키가 거부됐어요"),
            Triple(403, """{"error":{"message":"forbidden"}}""", "Privacy"),
            Triple(404, """{"error":{"message":"not found"}}""", "endpoint"),
            Triple(429, """{"error":{"message":"rate limited"}}""", "한도를 초과했어요"),
        )
        for ((status, body, needle) in cases) {
            val host = RecordingHost(profile("go", "https://opencode.ai/inference/go", "go-key"), conversationId = "conv")
            host.httpHandler = { PluginHttpResponse(status, mapOf("Retry-After" to "2"), body) }
            val failure = runCatching {
                providers(CustomOpenAiPlugin(), host).single().open(host).complete(request())
            }.exceptionOrNull() as PluginAiException
            assertEquals(status, failure.status)
            assertTrue(failure.message.orEmpty().contains(needle))
        }
    }

    @Test
    fun `OpenCode 과금 오류는 Go 주소를 안내하고 다른 endpoint로 재전송하지 않아요`() = runBlocking {
        val host = RecordingHost(profile("go", "https://opencode.ai/inference/openai/v1/chat/completions"))
        host.httpHandler = { PluginHttpResponse(402, emptyMap(), """{"error":{"message":"Insufficient account funds"}}""") }
        val failure = runCatching { providers(CustomOpenAiPlugin(), host).single().open(host).complete(request()) }.exceptionOrNull()
        assertTrue(failure is PluginAiException)
        assertTrue(failure!!.message.orEmpty().contains("/inference/go/openai/v1/chat/completions"))
        assertTrue(failure.message.orEmpty().contains("/zen/go/v1/chat/completions"))
        assertEquals(1, host.calls.size)
    }

    @Test
    fun `Chat SSE는 사고와 본문을 분리하고 분할 도구 인자와 마지막 사용량을 합쳐요`() = runBlocking {
        val host = RecordingHost(profile("chat"))
        host.streamHandler = { _, emit ->
            sse(emit,
                """{"model":"served","choices":[{"delta":{"reasoning_content":"생각"}}]}""",
                """{"choices":[{"delta":{"content":"답"}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"read","arguments":"{\"path\":"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a\"}"}}]},"finish_reason":"tool_calls"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":7}}""",
                "[DONE]",
            )
        }
        val events = mutableListOf<PluginAiStreamEvent>()
        val result = providers(CustomOpenAiPlugin(), host).single().open(host).completeStreaming(request(), events::add)
        assertEquals(listOf(PluginAiStreamEvent.Reasoning("생각"), PluginAiStreamEvent.Content("답")), events)
        assertEquals("답", (result.message.content as JsonPrimitive).content)
        assertEquals("""{"path":"a"}""", result.message.toolCalls!!.single().function.arguments)
        assertEquals(10, result.inputTokens)
        assertEquals(7, result.outputTokens)
        assertEquals("served", result.responseModel)
    }

    @Test
    fun `끊기거나 오류가 난 스트림은 성공으로 만들거나 다른 요청으로 우회하지 않아요`() = runBlocking {
        for (payload in listOf(
            """{"choices":[{"delta":{"content":"부분"}}]}""",
            """{"error":{"message":"거부"}}""",
        )) {
            val host = RecordingHost(profile("chat"))
            host.streamHandler = { _, emit -> sse(emit, payload) }
            val failure = runCatching { providers(CustomOpenAiPlugin(), host).single().open(host).completeStreaming(request()) {} }
            assertTrue(failure.isFailure)
            assertEquals(1, host.calls.size)
        }
        val jsonHost = RecordingHost(profile("json"))
        jsonHost.streamHandler = { _, _ -> PluginHttpResponse(200, emptyMap(), CHAT_RESPONSE) }
        val result = providers(CustomOpenAiPlugin(), jsonHost).single().open(jsonHost).completeStreaming(request()) {}
        assertEquals("완료", (result.message.content as JsonPrimitive).content)
        assertEquals(1, jsonHost.calls.size)
    }

    @Test
    fun `NVIDIA는 키가 없으면 전송을 거부하고 인증 오류를 재시도하지 않아요`() = runBlocking {
        val missing = RecordingHost(profile("nim", type = NVIDIA_TYPE))
        assertTrue(runCatching { providers(CustomOpenAiPlugin(), missing).single().open(missing) }.exceptionOrNull() is PluginAiException)
        assertTrue(missing.calls.isEmpty())
        val host = RecordingHost(profile("nim", apiKey = "nim-secret", type = NVIDIA_TYPE))
        host.httpHandler = { PluginHttpResponse(401, mapOf("Retry-After" to "3"), """{"error":{"message":"Invalid key"}}""") }
        val failure = runCatching { providers(CustomOpenAiPlugin(), host).single().open(host).complete(request()) }.exceptionOrNull() as PluginAiException
        assertEquals(401, failure.status)
        assertEquals(3L, failure.retryAfterSeconds)
        assertTrue(failure.message.orEmpty().contains("NVIDIA"))
        assertEquals(1, host.calls.size)
    }

    @Test
    fun `Codex는 최초 요청에만 Termux 인증을 읽고 두 계정의 인증을 섞지 않아요`() = runBlocking {
        val host = RecordingHost(
            profile("codex-a", type = CODEX_TYPE),
            profile("codex-b", type = CODEX_TYPE, auth = auth("second", "second-refresh", "account-b")),
        )
        host.shellOutput = auth("first", "first-refresh", "account-a")
        host.streamHandler = { _, emit -> sse(emit, CODEX_DONE) }
        val plugin = CustomOpenAiPlugin()
        val services = providers(plugin, host)
        val first = services.first().open(host)
        val second = services.last().open(host)
        assertEquals(0, host.shellCommands.size)
        first.complete(request())
        first.complete(request())
        second.complete(request())
        assertEquals(1, host.shellCommands.size)
        assertFalse(host.shellCommands.single().contains("first-refresh"))
        assertEquals(listOf("Bearer first", "Bearer first", "Bearer second"), host.calls.map { it.headers["Authorization"] })
        assertEquals(listOf("account-a", "account-a", "account-b"), host.calls.map { it.headers["chatgpt-account-id"] })
        assertTrue(host.entries.getValue("codex-a").values["auth-json"].orEmpty().contains("first-refresh"))
        assertEquals("second", Json.parseToJsonElement(host.entries.getValue("codex-b").values.getValue("auth-json")).jsonObject["tokens"]!!.jsonObject.stringValue("access_token"))
    }

    @Test
    fun `Codex 만료 토큰은 직렬로 한번 갱신하고 원래 JSON 구조와 회전 토큰을 저장해요`() = runBlocking {
        for (nested in listOf(true, false)) {
            val host = RecordingHost(profile("codex", type = CODEX_TYPE, auth = auth("old", "r+ /", "account", expiry = 1, nested = nested)))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            host.httpHandler = {
                entered.complete(Unit)
                release.await()
                PluginHttpResponse(200, emptyMap(), """{"access_token":"new","refresh_token":"rotated","expires_in":3600,"id_token":"identity"}""")
            }
            host.streamHandler = { _, emit -> sse(emit, CODEX_DONE) }
            val provider = providers(CustomOpenAiPlugin(), host).single()
            val first = provider.open(host)
            val second = provider.open(host)
            val a = async { first.complete(request()) }
            entered.await()
            val b = async { second.complete(request()) }
            release.complete(Unit)
            a.await()
            b.await()
            val refreshCalls = host.calls.filter { it.url.endsWith("/oauth/token") }
            assertEquals(1, refreshCalls.size)
            val form = refreshCalls.single().body!!.split('&').associate { field ->
                field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), "UTF-8")
            }
            assertEquals("r+ /", form["refresh_token"])
            assertEquals("refresh_token", form["grant_type"])
            val saved = Json.parseToJsonElement(host.entries.getValue("codex").values.getValue("auth-json")).jsonObject
            assertEquals(nested, saved["tokens"] is JsonObject)
            val tokens = if (nested) saved["tokens"]!!.jsonObject else saved
            assertEquals("rotated", tokens.stringValue("refresh_token"))
            assertEquals("identity", tokens.stringValue("id_token"))
            assertEquals("보존", saved.stringValue("metadata"))
            assertTrue(tokens.longValue("expires_at")!! > System.currentTimeMillis() / 1000)
            assertEquals(listOf("Bearer new", "Bearer new"), host.calls.filter { it.url.endsWith("/responses") }.map { it.headers["Authorization"] })
        }
    }

    @Test
    fun `늦은 Codex 갱신은 교체한 인증과 삭제한 프로필을 덮거나 복원하지 않아요`() = runBlocking {
        for (delete in listOf(false, true)) {
            val oldAuth = auth("old", "old-refresh", "old-account", expiry = 1)
            val replacement = auth("replacement", "replacement-refresh", "replacement-account")
            val host = RecordingHost(profile("codex", type = CODEX_TYPE, auth = oldAuth))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            host.httpHandler = {
                entered.complete(Unit)
                release.await()
                PluginHttpResponse(200, emptyMap(), """{"access_token":"refreshed-old","expires_in":3600}""")
            }
            host.streamHandler = { _, emit -> sse(emit, CODEX_DONE) }
            val session = providers(CustomOpenAiPlugin(), host).single().open(host)
            val job = async { session.complete(request()) }
            entered.await()
            if (delete) host.entries.remove("codex") else host.entries["codex"] = profile("codex", type = CODEX_TYPE, auth = replacement)
            release.complete(Unit)
            job.await()
            if (delete) assertFalse(host.entries.containsKey("codex"))
            else assertEquals(replacement, host.entries.getValue("codex").values["auth-json"])
            assertEquals("Bearer refreshed-old", host.calls.last().headers["Authorization"])
            assertEquals(0, host.successfulWrites)
        }
    }

    @Test
    fun `Codex Responses는 JWT 계정과 이미지 도구 이력을 보존하고 최종 스냅샷을 중복하지 않아요`() = runBlocking {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"exp":4102444800,"https://api.openai.com/auth":{"chatgpt_account_id":"jwt-account"}}""".toByteArray(),
        )
        val host = RecordingHost(profile("codex", type = CODEX_TYPE, auth = auth("header.$payload.signature", "refresh", "stale-account", expiry = 1)))
        host.streamHandler = { _, emit -> sse(emit,
            """{"type":"response.reasoning_summary_text.delta","delta":"사고"}""",
            """{"type":"response.output_text.delta","delta":"답변"}""",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"item-1","call_id":"call-1","name":"read","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","item_id":"item-1","delta":"{\"path\":"}""",
            """{"type":"response.function_call_arguments.delta","item_id":"item-1","delta":"\"a\"}"}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"item-1","call_id":"call-1","name":"read","arguments":"{\"path\":\"a\"}"}}""",
            """{"type":"response.completed","response":{"model":"served-codex","usage":{"input_tokens":12,"output_tokens":9},"output":[{"type":"message","content":[{"type":"output_text","text":"답변"}]},{"type":"function_call","call_id":"call-1","name":"read","arguments":"{\"path\":\"a\"}"}]}}""",
        ) }
        val image = Json.parseToJsonElement("""[{"type":"text","text":"이미지"},{"type":"image_url","image_url":{"url":"data:image/png;base64,aQ=="}}]""")
        val messages = listOf(
            ApiMessage("system", JsonPrimitive("지침")),
            ApiMessage("user", image),
            ApiMessage("assistant", toolCalls = listOf(ApiToolCall("previous", "function", ApiFunctionCall("read", "{}")))),
            ApiMessage("tool", JsonPrimitive("결과"), toolCallId = "previous"),
        )
        val events = mutableListOf<PluginAiStreamEvent>()
        val result = providers(CustomOpenAiPlugin(), host).single().open(host).completeStreaming(PluginAiRequest("ignored", messages), events::add)
        assertEquals(listOf(PluginAiStreamEvent.Reasoning("사고"), PluginAiStreamEvent.Content("답변")), events)
        assertEquals("답변", (result.message.content as JsonPrimitive).content)
        assertEquals("call-1", result.message.toolCalls!!.single().id)
        assertEquals("""{"path":"a"}""", result.message.toolCalls!!.single().function.arguments)
        assertEquals(12, result.inputTokens)
        assertEquals(9, result.outputTokens)
        assertEquals("served-codex", result.responseModel)
        assertEquals("jwt-account", host.calls.single().headers["chatgpt-account-id"])
        val sent = body(host.calls.single())
        val input = sent["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("input_image", input.first()["content"]!!.jsonArray.last().jsonObject.stringValue("type"))
        assertEquals(listOf("message", "function_call", "function_call_output"), input.map { it.stringValue("type") })
        assertEquals("previous", input.last().stringValue("call_id"))
        assertFalse(sent.toString().contains("사고"))
    }

    @Test
    fun `세션 중지는 진행 중인 스트림과 토큰 갱신을 취소하고 인증을 저장하지 않아요`() = runBlocking {
        for (refresh in listOf(false, true)) {
            val host = RecordingHost(if (refresh) profile("cancel", type = CODEX_TYPE, auth = auth("old", "refresh", "account", expiry = 1)) else profile("cancel"))
            val entered = CompletableDeferred<Unit>()
            host.httpHandler = { entered.complete(Unit); awaitCancellation() }
            host.streamHandler = { _, _ -> entered.complete(Unit); awaitCancellation() }
            val session = providers(CustomOpenAiPlugin(), host).single().open(host)
            val job = launch { session.completeStreaming(request()) {} }
            entered.await()
            session.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(0, host.successfulWrites)
        }
    }

    @Test
    fun `고정 인증을 물려받은 자식은 이미 갱신한 토큰을 재사용하고 명시적 초기화는 다시 읽어요`() = runBlocking {
        val original = profile("codex", type = CODEX_TYPE, auth = auth("old", "refresh", "account", expiry = 1))
        val host = RecordingHost(original)
        host.httpHandler = { PluginHttpResponse(200, emptyMap(), """{"access_token":"new","expires_in":3600}""") }
        host.streamHandler = { _, emit -> sse(emit, CODEX_DONE) }
        val plugin = CustomOpenAiPlugin()
        val provider = providers(plugin, host).single()
        provider.open(host).complete(request())
        var opening = true
        val childHost = object : PluginHost by host {
            override fun settingEntries(groupKey: String) =
                if (opening) listOf(original) else host.settingEntries(groupKey)
        }
        val child = provider.open(childHost)
        opening = false
        child.complete(request())
        assertEquals(1, host.calls.count { it.url.endsWith("/oauth/token") })
        assertEquals("Bearer new", host.calls.last().headers["Authorization"])

        val imported = RecordingHost(profile("imported", type = CODEX_TYPE))
        imported.shellOutput = auth("file-first", "refresh-one", "account-one")
        imported.streamHandler = { _, emit -> sse(emit, CODEX_DONE) }
        val importedPlugin = CustomOpenAiPlugin()
        providers(importedPlugin, imported).single().open(imported).complete(request())
        imported.entries["imported"] = profile("imported", type = CODEX_TYPE)
        imported.shellOutput = auth("file-second", "refresh-two", "account-two")
        providers(importedPlugin, imported).single().open(imported).complete(request())
        assertEquals(2, imported.shellCommands.size)
        assertEquals("Bearer file-second", imported.calls.last().headers["Authorization"])
    }

    @Test
    fun `Codex 실패와 인증 저장 실패는 성공이나 다른 요청으로 대체하지 않아요`() = runBlocking {
        for (event in listOf(
            """{"type":"response.output_text.delta","delta":"부분"}""",
            """{"type":"response.failed","response":{"error":{"message":"실패"}}}""",
            """{"type":"error","message":"실패"}""",
        )) {
            val host = RecordingHost(profile("codex", type = CODEX_TYPE, auth = auth("access", "refresh", "account")))
            host.streamHandler = { _, emit -> sse(emit, event) }
            val outcome = runCatching { providers(CustomOpenAiPlugin(), host).single().open(host).complete(request()) }
            assertTrue(outcome.exceptionOrNull() is PluginAiException)
            assertEquals(1, host.calls.size)
        }
        val host = RecordingHost(profile("codex", type = CODEX_TYPE, auth = auth("old", "refresh", "account", expiry = 1)))
        host.httpHandler = { PluginHttpResponse(200, emptyMap(), """{"access_token":"new","expires_in":3600}""") }
        val storageFailureHost = object : PluginHost by host {
            override fun writeSettingEntryIfUnchanged(
                groupKey: String,
                entry: PluginSettingEntry,
                expectedValues: Map<String, String>,
            ): Result<Boolean> = Result.failure(java.io.IOException("저장 실패"))
        }
        val outcome = runCatching {
            providers(CustomOpenAiPlugin(), storageFailureHost).single().open(storageFailureHost).complete(request())
        }
        assertTrue(outcome.exceptionOrNull() is PluginAiException)
        assertEquals(1, host.calls.size)
        assertEquals(0, host.successfulWrites)
    }

    private fun providers(plugin: CustomOpenAiPlugin, host: PluginHost) = plugin.services(host).filterIsInstance<PluginAiProvider>()
    private fun request() = PluginAiRequest("", listOf(ApiMessage("user", JsonPrimitive("질문"))))
    private fun body(request: PluginHttpRequest) = Json.parseToJsonElement(request.body!!).jsonObject

    private fun profile(
        id: String,
        endpoint: String = "https://chat.example/v1",
        apiKey: String = "",
        model: String = "profile-model",
        type: String = OPENAI_TYPE,
        auth: String = "",
        name: String = id,
    ) = PluginSettingEntry(id, mapOf("name" to name, "endpoint" to (if (type == CODEX_TYPE && endpoint == "https://chat.example/v1") CODEX_ENDPOINT else endpoint),
        "api-key" to apiKey, "model" to model, "type" to type, "auth-json" to auth))

    private fun auth(access: String, refresh: String, account: String, expiry: Long = 4102444800, nested: Boolean = true): String {
        val tokens = """"access_token":"$access","refresh_token":"$refresh","account_id":"$account","expires_at":$expiry"""
        return if (nested) """{"metadata":"보존","tokens":{$tokens}}""" else """{"metadata":"보존",$tokens}"""
    }

    private class RecordingHost(vararg profiles: PluginSettingEntry, private val conversationId: String? = null) : PluginHost {
        val entries = profiles.associateByTo(linkedMapOf()) { it.id }
        val calls = mutableListOf<PluginHttpRequest>()
        val protections = mutableListOf<Set<String>>()
        val shellCommands = mutableListOf<String>()
        var shellOutput = ""
        var successfulWrites = 0
        var httpHandler: suspend (PluginHttpRequest) -> PluginHttpResponse = { PluginHttpResponse(200, emptyMap(), CHAT_RESPONSE) }
        var streamHandler: suspend (PluginHttpRequest, (String) -> Unit) -> PluginHttpResponse = { _, emit -> sse(emit, "[DONE]") }
        override val appVersion = "test"
        override val apkPath = "/test/app.apk"
        override val aiConversationId get() = conversationId
        override fun settingEntries(groupKey: String) = synchronized(entries) { entries.values.toList() }
        override fun writeSettingEntryIfUnchanged(groupKey: String, entry: PluginSettingEntry, expectedValues: Map<String, String>): Result<Boolean> = synchronized(entries) {
            val old = entries[entry.id] ?: return@synchronized Result.success(false)
            if (expectedValues.any { (key, value) -> old.values[key].orEmpty() != value }) return@synchronized Result.success(false)
            entries[entry.id] = PluginSettingEntry(entry.id, old.values + entry.values)
            successfulWrites++
            Result.success(true)
        }
        override suspend fun shell(command: String, timeoutSeconds: Int): Result<ProcessResult> {
            shellCommands += command
            return Result.success(ProcessResult(0, shellOutput, false))
        }
        override suspend fun ensureDesktop() = Result.success(Unit)
        override suspend fun readAsset(path: String) = Result.failure<ByteArray>(UnsupportedOperationException())
        override fun progress(message: String) = Unit
        override suspend fun http(request: PluginHttpRequest): Result<PluginHttpResponse> {
            calls += request
            protections += emptySet<String>()
            return Result.success(httpHandler(request))
        }
        override suspend fun http(request: PluginHttpRequest, protectedHeaders: Set<String>): Result<PluginHttpResponse> {
            calls += request
            protections += protectedHeaders
            return Result.success(httpHandler(request))
        }
        override suspend fun httpStream(request: PluginHttpRequest, onLine: (String) -> Unit, protectedHeaders: Set<String>): Result<PluginHttpResponse> {
            calls += request
            protections += protectedHeaders
            return Result.success(streamHandler(request, onLine))
        }
    }

    companion object {
        private const val USAGE_RESPONSE = """{"usage":{"rolling":{"status":"ok","percent":2,"resetsAt":"2026-11-14T09:10:48.000Z"},"weekly":{"status":"ok","percent":30,"resetsAt":"2026-11-16T00:00:00.000Z"},"monthly":{"status":"ok","percent":37,"resetsAt":"2026-12-08T11:45:25.000Z"}}}"""
        private const val CHAT_RESPONSE = """{"choices":[{"message":{"role":"assistant","content":"완료"}}]}"""
        private const val CODEX_DONE = """{"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"완료"}]}]}}"""
        private fun sse(emit: (String) -> Unit, vararg events: String): PluginHttpResponse {
            events.forEach { emit("data: $it"); emit("") }
            return PluginHttpResponse(200, emptyMap(), "")
        }
    }
}
