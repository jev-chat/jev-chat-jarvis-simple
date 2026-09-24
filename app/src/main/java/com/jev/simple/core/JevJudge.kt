package com.jev.simple.core

import org.json.JSONArray
import org.json.JSONObject

// MARK: - 判断层：TypeSafe Jev / System One 客户端
//
// Jev 是结构化决策模型：给它一个 state 加一组带类型的题目，返回校准过的概率而不是文字。
// 与 macOS 版 src/judge_jev.py 同一请求形状：
//   POST {base}/v1/systemone   Authorization: Bearer <key>
//   {"model": …, "state": …, "questions": {intent: choice, risk: score}}
// 排序只是又一道 choice 题：把候选文本当选项，哪条最合适。

data class JudgeResult(
    val intent: String,
    val confidence: Double,
    val intentProbs: Map<String, Double>,
    val risk: Double,
    val riskProbs: Map<String, Double>,
    val actions: List<String>,
) {
    val riskLevelText: String get() = riskLabel(risk)
}

data class RankedCandidate(val text: String, val prob: Double)

/**
 * 判断结果的内存缓存。实测判断一次要一秒多，而「换一批」是对同一条消息反复分析——
 * 判断结论不会变，没必要每次重付一次往返。只在进程内、不落盘；换了消息自然失效。
 */
object JevJudgeCache {
    private data class Key(val message: String, val context: String)

    private const val TTL_MS = 300_000L
    private const val MAX_ENTRIES = 8

    private val store = LinkedHashMap<Key, Pair<JudgeResult, Long>>()
    private val lock = Any()

    fun get(message: String, context: String?): JudgeResult? {
        synchronized(lock) {
            val hit = store[Key(message, context ?: "")] ?: return null
            if (System.currentTimeMillis() - hit.second >= TTL_MS) return null
            return hit.first
        }
    }

    fun put(result: JudgeResult, message: String, context: String?) {
        synchronized(lock) {
            if (store.size > MAX_ENTRIES) store.clear()
            store[Key(message, context ?: "")] = result to System.currentTimeMillis()
        }
    }
}

class JevJudge(cfg: JevConfig) {
    private val base = cfg.judgeBase.trim()
    private val key = cfg.judgeKey.trim()
    private val model = cfg.judgeModel.trim()

    val isConfigured: Boolean get() = key.isNotEmpty() && base.isNotEmpty() && model.isNotEmpty()

    // MARK: 判断（意图 + 风险，一次调用出全分布）

    suspend fun judge(message: String, context: String?): JudgeResult {
        val state = if (!context.isNullOrEmpty()) "${context}\n\n${message}" else message

        val intentCriteria = JSONObject()
        for ((k, v) in INTENTS) intentCriteria.put(k, v)

        val questions = JSONObject().apply {
            put("intent", JSONObject().apply {
                put("type", "choice")
                put("instructions", "这句话的真实意图是什么？")
                put("criteria", intentCriteria)
            })
            put("risk", JSONObject().apply {
                put("type", "score")
                put("instructions", "如果直接回复这句话，风险有多大？")
                put("criteria", JSONArray(RISK_LEVELS))
            })
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("state", state)
            put("questions", questions)
        }

        val data = post(payload, stage = "判断")
        val answers = data.optJSONObject("answers") ?: JSONObject()
        val intentAns = answers.optJSONObject("intent") ?: JSONObject()
        val riskAns = answers.optJSONObject("risk") ?: JSONObject()

        var intent = intentAns.optString("choice", "闲聊")
        if (!INTENTS.containsKey(intent)) {
            // 网关偶尔回显下标或近似标签
            intent = INTENTS.keys.firstOrNull { intent.contains(it) } ?: "闲聊"
        }
        return JudgeResult(
            intent = intent,
            confidence = doubleValue(intentAns.opt("confidence")),
            intentProbs = doubleDict(intentAns.opt("probabilities")),
            risk = doubleValue(riskAns.opt("score")),
            riskProbs = doubleDict(riskAns.opt("probabilities")),
            actions = ACTION_MAP[intent] ?: emptyList(),
        )
    }

    // MARK: 排序（候选文本当 choice 选项）

    suspend fun rank(message: String, intent: String, candidates: List<String>): List<RankedCandidate> {
        if (candidates.isEmpty()) return emptyList()

        // 去重后再当选项：两个槽位选了同一个话术时，模型很可能给出两条一模一样的候选，
        // 而 JSON 对象键不能重复。去重保留首次出现的那条。
        val criteria = JSONObject()
        for (c in candidates) if (!criteria.has(c)) criteria.put(c, JSONObject.NULL)

        val payload = JSONObject().apply {
            put("model", model)
            put("state", "收到的消息：${message}\n判断出的意图：${intent}")
            put("questions", JSONObject().put("best", JSONObject().apply {
                put("type", "choice")
                put("instructions", "哪一条回复最合适？")
                put("criteria", criteria)
            }))
        }

        val data = post(payload, stage = "排序")
        val ans = data.optJSONObject("answers")?.optJSONObject("best") ?: JSONObject()
        val probs = doubleDict(ans.opt("probabilities"))

        val ranked = candidates.map { c ->
            var p = probs[c] ?: 0.0
            if (p == 0.0 && ans.optString("choice") == c) {
                p = doubleValue(ans.opt("confidence"))   // 网关可能只回显选中项
            }
            RankedCandidate(c, p)
        }
        return ranked.sortedByDescending { it.prob }
    }

    // MARK: transport

    private suspend fun post(payload: JSONObject, stage: String): JSONObject =
        JevHTTP.postJSON(
            body = payload,
            url = requestURL(base),
            headers = mapOf("Authorization" to "Bearer ${key}"),
            budget = if (stage == "判断") 15.0 else 12.0,
            stage = stage,
        )

    private fun doubleValue(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun doubleDict(v: Any?): Map<String, Double> {
        val o = v as? JSONObject ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (k in o.keys()) out[k] = doubleValue(o.opt(k))
        return out
    }

    companion object {
        // MARK: URL 拼接（与 macOS 版 #42 单一规则一致）
        //
        // 三种填法等价可用：只到主机（…/v1/systemone 自动补）、带版本段（…/v1 只补动作段）、
        // 完整动作路径（原样使用）。网关可以改名动作段（Vercel 是 /v1/evaluate、
        // OpenRouter 是 /api/alpha/decisions）。

        private val ACTION_SEGS = setOf("systemone", "evaluate", "decisions")
        private val VERSION_SEG = Regex("^v\\d+$")

        fun requestURL(rawBase: String): String {
            val b = rawBase.trim().replace(Regex("/+$"), "")
            val last = b.split("/").lastOrNull()?.lowercase() ?: return b
            if (ACTION_SEGS.contains(last)) return b
            if (VERSION_SEG.matches(last)) return "${b}/systemone"
            return "${b}/v1/systemone"
        }
    }
}
