package com.sleepysoong.logent.customopenai

import java.net.URI

/** 프로필이 적은 주소를 실제 Chat Completions 전체 주소로 맞춰요. */
internal object ProviderEndpoint {
    /**
     * 다른 하네스가 쓰는 base URL을 Chat Completions 전체 주소로 맞춰요.
     *
     * 이미 전체 주소인 값과 쿼리가 붙은 값은 건드리지 않아요.
     */
    fun chatCompletions(value: String): String {
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
}
