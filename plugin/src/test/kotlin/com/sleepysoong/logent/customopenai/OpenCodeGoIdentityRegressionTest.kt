package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.ApiMessage
import com.sleepysoong.logent.plugin.PluginAiException
import com.sleepysoong.logent.plugin.PluginAiProvider
import com.sleepysoong.logent.plugin.PluginAiRequest
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginHttpResponse
import com.sleepysoong.logent.plugin.PluginSettingEntry
import com.sleepysoong.logent.plugin.ProcessResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeGoIdentityRegressionTest {
    @Test
    fun `each conversation sends its actual identity as a protected header`() = runBlocking {
        for (id in listOf("conversation-1", "conversation-2")) {
            val host = IdentityHost(id)
            val session = provider(host).open(host)
            session.complete(request())
            val call = host.calls.single { it.method == "POST" }
            assertEquals(id, call.headers["x-opencode-session"])
            assertEquals(setOf("x-opencode-session"), host.protections[host.calls.indexOf(call)])
        }
    }

    @Test
    fun `missing and unsafe conversation identities fail before any network request`() = runBlocking {
        for (id in listOf(null, "", " ", "session with space", "한글", "session\r\nInjected: value", "session\u007f")) {
            for (stream in listOf(false, true)) {
                val host = IdentityHost(id)
                val failure = runCatching {
                    val session = provider(host).open(host)
                    if (stream) session.completeStreaming(request()) {} else session.complete(request())
                }.exceptionOrNull()
                assertTrue("Expected PluginAiException for $id", failure is PluginAiException)
                assertTrue(host.calls.isEmpty())
            }
        }
    }

    @Test
    fun `context windows belong to models and not the provider`() {
        val provider = provider(IdentityHost("conversation"))
        assertNull(provider.contextWindow)
        assertEquals(1_000_000, provider.models.single { it.id == "deepseek-v4.1-flash" }.contextWindow)
        assertEquals(500_000, provider.models.single { it.id == "grok-4.7" }.contextWindow)
    }

    private fun provider(host: PluginHost) =
        CustomOpenAiPlugin().services(host).filterIsInstance<PluginAiProvider>().single()

    private fun request() = PluginAiRequest("", listOf(ApiMessage("user", JsonPrimitive("hello"))))

    private class IdentityHost(override val aiConversationId: String?) : PluginHost {
        private val entry = PluginSettingEntry("go", mapOf(
            "name" to "Go",
            "endpoint" to "https://opencode.ai/inference/go",
            "api-key" to "key",
            "model" to "deepseek-v4.1-flash",
            "type" to OPENAI_TYPE,
        ))
        val calls = java.util.concurrent.CopyOnWriteArrayList<PluginHttpRequest>()
        val protections = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        override val appVersion = "test"
        override val apkPath = "/test/app.apk"
        override fun settingEntries(groupKey: String) = listOf(entry)
        override suspend fun shell(command: String, timeoutSeconds: Int): Result<ProcessResult> =
            Result.failure(UnsupportedOperationException())
        override suspend fun ensureDesktop() = Result.success(Unit)
        override suspend fun readAsset(path: String) = Result.failure<ByteArray>(UnsupportedOperationException())
        override fun progress(message: String) = Unit
        override suspend fun http(request: PluginHttpRequest): Result<PluginHttpResponse> =
            http(request, emptySet())
        override suspend fun http(request: PluginHttpRequest, protectedHeaders: Set<String>): Result<PluginHttpResponse> {
            calls += request
            protections += protectedHeaders
            val body = if (request.method == "POST") {
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
            } else "{}"
            return Result.success(PluginHttpResponse(200, emptyMap(), body))
        }
        override suspend fun httpStream(
            request: PluginHttpRequest,
            onLine: (String) -> Unit,
            protectedHeaders: Set<String>,
        ): Result<PluginHttpResponse> {
            calls += request
            protections += protectedHeaders
            return Result.failure(UnsupportedOperationException())
        }
    }
}
