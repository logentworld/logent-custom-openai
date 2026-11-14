package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.PluginAiException
import com.sleepysoong.logent.plugin.PluginSettingEntry
import java.net.URI

internal const val PROVIDERS_GROUP = "providers"
internal const val OPENAI_TYPE = "OpenAI 호환"
internal const val NVIDIA_TYPE = "NVIDIA NIM"
internal const val CODEX_TYPE = "Codex 구독"
internal const val OPENAI_ENDPOINT = "https://api.openai.com/v1"
internal const val OPENAI_MODEL = "gpt-4.1-mini"
internal const val NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1"
internal const val NVIDIA_MODEL = "deepseek-ai/deepseek-v4.1-flash"
internal const val CODEX_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
internal const val CODEX_MODEL = "gpt-5.6-sol"

/** 실행 시작에 읽은 한 프로필의 설정을 다른 프로필과 섞지 않고 유지해요. */
internal class ProviderProfile(entry: PluginSettingEntry) {
    val id = entry.id
    val name = entry.values["name"].orEmpty().trim().ifBlank { "AI 제공자" }
    val type = entry.values["type"].orEmpty().ifBlank { OPENAI_TYPE }
    val apiKey = entry.values["api-key"].orEmpty().trim()
    val authJson = entry.values["auth-json"].orEmpty()
    private val configuredEndpoint = entry.values["endpoint"].orEmpty().trim()
    private val configuredModel = entry.values["model"].orEmpty().trim()

    // 공통 양식의 OpenAI 기본값은 NVIDIA·Codex에서는 해당 전송의 기본값으로 해석해요.
    val endpoint: String = when (type) {
        NVIDIA_TYPE -> configuredEndpoint.takeUnless { it.isBlank() || it == OPENAI_ENDPOINT }
            ?: NVIDIA_ENDPOINT
        CODEX_TYPE -> configuredEndpoint.takeUnless { it.isBlank() || it == OPENAI_ENDPOINT }
            ?: CODEX_ENDPOINT
        else -> configuredEndpoint.ifBlank { OPENAI_ENDPOINT }
    }

    /**
     * 이 프로필이 OpenCode Go 구독 경로를 가리키는지 여부예요.
     *
     * Go는 모델 ID가 일반 OpenAI·NVIDIA와 다르고 세션 헤더·사용량 조회가 붙어서,
     * endpoint만 보고 같은 판단을 여러 곳에서 되풀이하지 않도록 여기서 한 번 정해요.
     */
    val isOpenCodeGo: Boolean = type != CODEX_TYPE && OpenCodeGo.isEndpoint(ProviderEndpoint.chatCompletions(endpoint))

    val model: String = when {
        type == NVIDIA_TYPE -> configuredModel.takeUnless { it.isBlank() || it == OPENAI_MODEL }
            ?: NVIDIA_MODEL
        type == CODEX_TYPE -> configuredModel.takeUnless { it.isBlank() || it == OPENAI_MODEL }
            ?: CODEX_MODEL
        // Go에서는 폼 기본값인 OpenAI 모델을 Go 모델로 해석해요. 그대로 보내면 서버가
        // "Model is unavailable"만 돌려주고 사용자는 원인을 알 수 없어요.
        isOpenCodeGo -> configuredModel.takeUnless { it.isBlank() || it == OPENAI_MODEL }
            ?: OpenCodeGo.DEFAULT_MODEL
        else -> configuredModel.ifBlank { OPENAI_MODEL }
    }

    fun validate() {
        if (type != OPENAI_TYPE && type != NVIDIA_TYPE && type != CODEX_TYPE) {
            throw PluginAiException("지원하지 않는 AI 제공자 종류예요.")
        }
        val uri = runCatching { URI(endpoint) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.rawFragment != null
        ) {
            throw PluginAiException("endpoint는 사용자 정보와 fragment가 없는 https:// 주소여야 해요.")
        }
        if (type == NVIDIA_TYPE && apiKey.isBlank()) {
            throw PluginAiException("이 제공자 프로필에 NVIDIA API 키를 입력해 주세요.")
        }
    }
}
