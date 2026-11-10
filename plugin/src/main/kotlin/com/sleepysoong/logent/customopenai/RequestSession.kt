package com.sleepysoong.logent.customopenai

import com.sleepysoong.logent.plugin.PluginAiSession
import com.sleepysoong.logent.plugin.PluginHttpResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 호출자 취소와 세션 중지를 같은 HTTP 코루틴에 전달해요. */
internal abstract class RequestSession : PluginAiSession {
    private val requests = mutableSetOf<Job>()

    final override fun cancel() {
        val active = synchronized(requests) { requests.toList() }
        active.forEach { it.cancel() }
    }

    protected suspend fun <T> runRequest(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        synchronized(requests) { requests.add(job) }
        try {
            block()
        } finally {
            synchronized(requests) { requests.remove(job) }
        }
    }
}

internal fun PluginHttpResponse.retryAfterSeconds(): Long? {
    val value = headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value
        ?: return null
    value.trim().toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
    return runCatching {
        val deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    }.getOrNull()
}
