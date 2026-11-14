package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.ApiMessage
import com.sleepysoong.logent.plugin.PluginAiProvider
import com.sleepysoong.logent.plugin.PluginAiRequest
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginHttpRequest
import com.sleepysoong.logent.plugin.PluginHttpResponse
import com.sleepysoong.logent.plugin.PluginSettingEntry
import com.sleepysoong.logent.plugin.ProcessResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexModelSelectionTest {
    @Test
    fun `Codex sends selected model or profile default for both completion paths`() = runBlocking {
        val host = ModelHost()
        val provider = CustomOpenAiPlugin().services(host).filterIsInstance<PluginAiProvider>().single()
        assertEquals("profile-model", provider.defaultModel)
        val session = provider.open(host)
        val messages = listOf(ApiMessage("user", JsonPrimitive("hello")))

        session.complete(PluginAiRequest("  selected-model  ", messages))
        session.completeStreaming(PluginAiRequest("other-model", messages)) { }
        session.complete(PluginAiRequest(" ", messages))
        session.completeStreaming(PluginAiRequest("", messages)) { }
        session.complete(PluginAiRequest(OPENAI_MODEL, messages))

        assertEquals(
            listOf("selected-model", "other-model", "profile-model", "profile-model", OPENAI_MODEL),
            host.models,
        )
    }

    private class ModelHost : PluginHost {
        val models = mutableListOf<String>()
        override val appVersion = "test"
        override val apkPath = "/test/app.apk"
        override fun settingEntries(groupKey: String) = listOf(
            PluginSettingEntry("codex", mapOf(
                "name" to "Codex",
                "type" to CODEX_TYPE,
                "endpoint" to CODEX_ENDPOINT,
                "model" to "profile-model",
                "auth-json" to """{"tokens":{"access_token":"access","refresh_token":"refresh","expires_at":4102444800}}""",
            )),
        )
        override suspend fun shell(command: String, timeoutSeconds: Int): Result<ProcessResult> =
            Result.failure(UnsupportedOperationException())
        override suspend fun ensureDesktop() = Result.success(Unit)
        override suspend fun readAsset(path: String) = Result.failure<ByteArray>(UnsupportedOperationException())
        override fun progress(message: String) = Unit
        override suspend fun http(request: PluginHttpRequest): Result<PluginHttpResponse> =
            Result.failure(UnsupportedOperationException())
        override suspend fun httpStream(
            request: PluginHttpRequest,
            onLine: (String) -> Unit,
            protectedHeaders: Set<String>,
        ): Result<PluginHttpResponse> {
            models += Json.parseToJsonElement(request.body!!).jsonObject["model"]!!.jsonPrimitive.content
            onLine("""data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}}""")
            onLine("")
            return Result.success(PluginHttpResponse(200, emptyMap(), ""))
        }
    }
}
