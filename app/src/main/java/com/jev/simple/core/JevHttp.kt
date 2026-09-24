package com.jev.simple.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.pow

/** 与 iOS 版 JevError 同一套文案：所有分支的差别只体现在这句话上。 */
class JevError(message: String) : Exception(message)

object JevErrors {
    fun config(m: String) = JevError("配置问题：${m}")
    fun missingKey(n: String) = JevError("缺少密钥：${n}")
    fun http(code: Int, m: String) = JevError("HTTP ${code}：${m}")
    fun badJSON(m: String) = JevError("返回格式不对：${m}")
    fun emptyReply() = JevError("模型返回了空内容")

    /** 思考型模型把 max_tokens 吃光、正文 0 条——是模型选错，不是网络坏，必须单独报 */
    fun thinkingOnly(m: String) = JevError(m)
    fun timeout(stage: String, sec: Double) =
        JevError("${stage} 超时（${sec.toInt()} 秒内没返回）")
    fun cancelled() = JevError("已取消")
}

// MARK: - 带总预算的 HTTP POST

/**
 * 每个分析阶段（判断/起草/排序）受一个总时长预算约束，而不是只靠单次请求的读超时：
 * 读超时和重试是相乘关系，没有总预算时「3 次重试 × 60 秒超时」最坏是几分钟的转圈。
 * （这条规则来自社区 iOS 版踩过的真机坑。）
 *
 * Android 侧用 HttpURLConnection，不引第三方网络库——输入法进程越轻越好。
 */
object JevHTTP {

    suspend fun postJSON(
        body: JSONObject,
        url: String,
        headers: Map<String, String>,
        budget: Double,
        stage: String,
        retries: Int = 1,
    ): JSONObject = withContext(Dispatchers.IO) {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val start = System.currentTimeMillis()
        var lastErr: Exception? = null

        fun elapsed() = (System.currentTimeMillis() - start) / 1000.0

        for (attempt in 0..retries) {
            val remaining = budget - elapsed()
            if (remaining <= 2) throw lastErr ?: JevErrors.timeout(stage, budget)
            coroutineContext.ensureActive()

            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    val ms = (remaining * 1000).toInt().coerceIn(1000, 60_000)
                    connectTimeout = ms
                    readTimeout = ms
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    for ((k, v) in headers) setRequestProperty(k, v)
                }
                val code: Int
                val text: String
                try {
                    conn.outputStream.use { it.write(payload) }
                    code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                } finally {
                    conn.disconnect()
                }

                if (code == 200) {
                    val obj = runCatching { JSONObject(text) }.getOrNull()
                    if (obj != null) return@withContext obj
                }
                if ((code == 429 || code in 500..599) && attempt < retries) {
                    lastErr = JevErrors.http(code, text.take(200))
                    val left = budget - elapsed()
                    if (left > 4) delay((min(2.0.pow(attempt), left - 2) * 1000).toLong())
                    continue
                }
                throw JevErrors.http(code, text.take(300))
            } catch (e: JevError) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
                if (attempt < retries) {
                    val left = budget - elapsed()
                    if (left > 4) delay((min(2.0, left - 2) * 1000).toLong())
                    continue
                }
            }
        }
        if (elapsed() >= budget - 2) throw JevErrors.timeout(stage, budget)
        throw lastErr ?: JevErrors.http(0, "重试耗尽")
    }

    /** 容错解析：剥掉 markdown 围栏与前后杂字，取第一个 { 到最后一个 }。 */
    fun parseJSONObject(content: String): JSONObject {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.replace("```json", "").replace("```", "")
        }
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw JevErrors.badJSON(content.take(200))
        return runCatching { JSONObject(s.substring(a, b + 1)) }
            .getOrElse { throw JevErrors.badJSON(content.take(200)) }
    }
}
