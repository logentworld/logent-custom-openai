package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.PluginAiModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * OpenCode Go 구독 전용 규칙을 한 곳에 모아요.
 *
 * Go는 일반 종량제와 게이트웨이 경로가 다르고, 대화마다 안정적인 세션 ID를 요구하며,
 * 사용량을 별도 엔드포인트에서 알려 줘요. 이 규칙을 여러 클래스에 흩어 두지 않아요.
 */
internal object OpenCodeGo {
    /** Go가 대화 단위 라우팅·프롬프트 캐시에 쓰는 헤더 이름이에요. */
    const val SESSION_HEADER = "x-opencode-session"

    /** 모델을 지정하지 않은 Go 프로필에 쓰는 기본 모델이에요. */
    const val DEFAULT_MODEL = "deepseek-v4.1-flash"

    /** 앱 버전을 붙여 클라이언트를 식별하는 User-Agent 접두사예요. */
    const val USER_AGENT = "Logent/"

    private const val HOST = "opencode.ai"
    private val GO_PATHS = listOf("/inference/go", "/zen/go")
    private val RESET_MONTH_DAY = DateTimeFormatter.ofPattern("M/d HH:mm")

    /** opencode.ai의 Go 경로(`/inference/go`, `/zen/go`)면 그 접두사를 돌려줘요. */
    fun rootPath(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val path = uri.rawPath.orEmpty()
        return GO_PATHS.firstOrNull { path == it || path.startsWith("$it/") }
    }

    fun isEndpoint(value: String): Boolean = rootPath(value) != null

    /** Go 구독이 적용되지 않는 일반 종량제 경로예요. */
    fun isPayAsYouGoEndpoint(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.host.equals(HOST, ignoreCase = true)) return false
        val path = uri.rawPath.orEmpty()
        return path.startsWith("/inference/") && !path.startsWith("/inference/go/")
    }

    /** 같은 Go 게이트웨이의 사용량 조회 주소예요. Go 경로가 아니면 조회하지 않아요. */
    fun usageUrl(value: String): String? {
        val root = rootPath(value) ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val authority = if (uri.port > 0) "$HOST:${uri.port}" else HOST
        return "https://$authority$root/v1/usage"
    }

    /** Go 세션은 실제 대화 ID만 사용하며 안전하지 않은 헤더 값은 거부해요. */
    fun sessionId(conversationId: String?): String {
        if (conversationId.isNullOrEmpty() || conversationId.any { it !in '!'..'~' }) {
            throw com.sleepysoong.logent.plugin.PluginAiException(
                "OpenCode Go에 유효한 대화 ID가 필요해요. 앱을 최신 Logent로 업데이트해 주세요.",
            )
        }
        return conversationId
    }

    /** Go가 제공하는 모델 목록이에요. 마지막 실측 2026-11-14, `/inference/go/v1/models`. */
    fun models(): List<PluginAiModel> = MODELS

    /** `GET .../v1/usage` 응답을 읽어요. 형식이 다르면 null이에요. */
    fun parseUsage(body: String): Usage? {
        val root = runCatching { UsageJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val usage = root["usage"] as? JsonObject ?: root
        val windows = listOf(
            "rolling" to "5시간",
            "weekly" to "주간",
            "monthly" to "월간",
        ).mapNotNull { (key, label) ->
            val value = usage[key] as? JsonObject ?: return@mapNotNull null
            val percent = (value["percent"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            Window(
                label = label,
                remainingPercent = (100.0 - percent).coerceIn(0.0, 100.0),
                status = (value["status"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "ok",
                resetsAt = (value["resetsAt"] as? JsonPrimitive)?.content?.let { text ->
                    runCatching { Instant.parse(text) }.getOrNull()
                },
            )
        }
        return windows.takeIf { it.isNotEmpty() }?.let(::Usage)
    }

    /** 설정 화면에 한 줄로 보여 줄 요약이에요. 예: `5시간 98% 남음(2시간 뒤) · 주간 70% 남음(11/16 09:00) · 월간 63% 남음(12/8 20:45)`. */
    fun formatUsage(usage: Usage, zone: ZoneId, now: Instant): String {
        return usage.windows.joinToString(" · ") { window ->
            when {
                window.status != "ok" -> "${window.label} ${window.status}"
                window.resetsAt == null -> "${window.label} ${window.remainingPercent.roundPercent()}% 남음"
                else -> "${window.label} ${window.remainingPercent.roundPercent()}% 남음" +
                    "(${resetText(window.resetsAt, zone, now)})"
            }
        }
    }

    /** 같은 경로라도 모델을 잘못 넣으면 서버가 원인을 알려 주지 않아서 문구를 직접 만들어요. */
    fun errorHint(status: Int, detail: String?, model: String): String = when {
        detail?.contains("MissingSessionID", ignoreCase = true) == true ->
            "OpenCode Go는 대화마다 세션 헤더(x-opencode-session)가 필요해요. 앱을 최신 Logent로 업데이트해 주세요."
        status == 400 && detail?.contains("Model is unavailable", ignoreCase = true) == true ->
            "OpenCode Go가 모델 '$model'을(를) 찾지 못했어요. 프로필의 모델을 Go 모델 ID(예: $DEFAULT_MODEL)로 바꿔 주세요."
        status == 401 ->
            "OpenCode Go API 키가 거부됐어요. 콘솔에서 키와 Models: Request 권한을 확인해 주세요."
        status == 402 ->
            "OpenCode Go 사용 한도를 넘었어요. 5시간·주간·월간 사용량을 확인하고 리셋을 기다려 주세요."
        status == 403 ->
            "이 모델을 쓸 권한이 없어요. Go 요금제에 포함된 모델인지, 워크스페이스 Privacy의 Regions가 Global인지 확인해 주세요."
        status == 404 ->
            "Go 엔드포인트 경로를 찾지 못했어요. endpoint를 https://opencode.ai/inference/go 또는 /zen/go/v1로 맞춰 주세요."
        status == 429 ->
            "OpenCode Go 요청 한도를 초과했어요. 잠시 후 다시 시도해 주세요."
        status in 500..599 ->
            "OpenCode 서버가 HTTP $status 상태로 응답했어요. 잠시 후 다시 시도해 주세요."
        else -> ""
    }

    private fun resetText(at: Instant, zone: ZoneId, now: Instant): String {
        val duration = Duration.between(now, at)
        return if (duration.isNegative) {
            "곧"
        } else if (duration <= Duration.ofHours(24)) {
            val hours = duration.toHours()
            val minutes = duration.minusHours(hours).toMinutes()
            when {
                hours > 0 -> "${hours}시간 ${minutes}분 뒤"
                minutes > 0 -> "${minutes}분 뒤"
                else -> "1분 안"
            }
        } else {
            at.atZone(zone).format(RESET_MONTH_DAY)
        }
    }

    private fun Double.roundPercent(): Long = kotlin.math.round(this).toLong()

    /** 사용량 창 하나예요. */
    data class Window(
        val label: String,
        val remainingPercent: Double,
        val status: String,
        val resetsAt: Instant?,
    )

    data class Usage(val windows: List<Window>)

    private val UsageJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val MODELS = listOf(
        PluginAiModel("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 1_000_000),
        PluginAiModel("deepseek-v4-pro", "DeepSeek V4 Pro", 1_000_000),
        PluginAiModel("glm-5.2", "GLM-5.2", 1_000_000),
        PluginAiModel("glm-5.3", "GLM-5.3", 1_000_000),
        PluginAiModel("glm-5.3-flash", "GLM-5.3-Flash", 1_000_000),
        PluginAiModel("grok-4.7", "Grok 4.7", 500_000),
        PluginAiModel("muse-spark-1.2-contributor", "Muse Spark 1.2 Contributor", 1_048_576),
        PluginAiModel("muse-spark-1.3-contributor", "Muse Spark 1.3 Contributor", 1_048_576),
        PluginAiModel("gpt-5.6-luna", "GPT-5.6 Luna", 1_050_000),
        PluginAiModel("gpt-6-luna", "GPT-6 Luna", 1_050_000),
        PluginAiModel("claude-haiku-5-5", "Claude Haiku 5.5", 1_000_000),
        PluginAiModel("hy3", "Hy3", 256_000),
        PluginAiModel("hy4-preview", "Hy4 preview", 1_024_000),
        PluginAiModel("hy4", "Hy4", 1_048_576),
        PluginAiModel("kimi-k2.7-code", "Kimi K2.7 Code", 262_144),
        PluginAiModel("kimi-k3", "Kimi K3", 1_048_576),
        PluginAiModel("kimi-k3.1-pro", "Kimi K3.1 Pro", 1_048_576),
        PluginAiModel("mimo-v2.6-flash", "MiMo-V2.6-Flash", 1_048_576),
        PluginAiModel("mimo-v2.6-pro", "MiMo-V2.6-Pro", 1_048_576),
        PluginAiModel("minimax-m3", "MiniMax-M3", 1_000_000),
        PluginAiModel("minimax-m3.1-flash", "MiniMax M3.1 Flash", 1_000_000),
        PluginAiModel("longcat-2.0", "LongCat-2.0", 1_000_000),
        PluginAiModel("longcat-2.5-free", "LongCat 2.5 Free", 1_000_000),
        PluginAiModel("step-5-preview", "Step 5 Preview", 1_000_000),
        PluginAiModel("beam-free", "Beam Free", 1_048_576),
        PluginAiModel("iceman-contributor-free", "Iceman Contributor Free", 262_144),
        PluginAiModel("qwen3.8-max", "Qwen3.8 Max", 1_000_000),
        PluginAiModel("qwen3.8-flash", "Qwen3.8 Flash", 1_000_000),
        PluginAiModel("qwen3.7-plus", "Qwen3.7 Plus", 1_000_000),
        PluginAiModel("gemini-4-argon", "Gemini 4 Argon", 1_048_576),
    )
}
