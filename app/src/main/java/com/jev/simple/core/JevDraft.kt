package com.jev.simple.core

import org.json.JSONArray
import org.json.JSONObject

// MARK: - 生成层：OpenAI / Anthropic 兼容起草客户端
//
// 一个话术一次请求（两话术合在一个 prompt 里会互相渗味，macOS 版实测结论）。
// 两种 API 形状：绝大多数端点（DeepSeek/智谱/通义/Moonshot/硅基/OpenRouter/Ollama）
// 只有 OpenAI 形状；智谱和少数网关两种都有。

class JevDraft(private val cfg: JevConfig) {

    val isConfigured: Boolean
        get() {
            val g = cfg.generation
            return g.key.isNotEmpty() && g.base.isNotEmpty() && g.model.isNotEmpty()
        }

    // MARK: 起草

    /** 一个话术一次调用，返回 2 条候选（前稳后放）。失败抛错，由管线层归拢。 */
    suspend fun draft(
        message: String, intent: String?, context: String?,
        tone: String, instruction: String,
    ): List<String> {
        val prompt = buildDraftPrompt(
            message = message, intent = intent, context = context,
            tone = tone, instruction = instruction, n = PER_TONE,
        )
        val raw = call(prompt)
        val lines = CandidateParser.parse(raw)
        if (lines.isEmpty()) throw JevErrors.emptyReply()
        return lines
    }

    suspend fun call(prompt: String): String {
        val g = cfg.generation
        val url = chatURL(g.base, g.kind)
        val body: JSONObject
        val headers = HashMap<String, String>()

        when (g.kind) {
            APIKind.OPENAI -> {
                body = JSONObject().apply {
                    put("model", g.model)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }))
                    put("max_tokens", 400)
                    put("temperature", TEMPERATURE)
                    put("stream", false)
                }
                headers["Authorization"] = "Bearer ${g.key}"
            }

            APIKind.ANTHROPIC -> {
                body = JSONObject().apply {
                    put("model", g.model)
                    put("max_tokens", 400)
                    put("temperature", TEMPERATURE)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }))
                }
                headers["x-api-key"] = g.key
                headers["anthropic-version"] = "2023-06-01"
            }
        }

        // 额外字段（关思考模式等）。非法 JSON 直接忽略——不该让一个可选配置打断整条链路。
        val extra = g.extraJSON.trim()
        if (extra.isNotEmpty()) {
            runCatching { JSONObject(extra) }.getOrNull()?.let { obj ->
                for (k in obj.keys()) body.put(k, obj.opt(k))
            }
        }

        val data = JevHTTP.postJSON(body, url = url, headers = headers, budget = 35.0, stage = "生成")
        return extractText(data, g.kind, g.model)
    }

    companion object {
        /**
         * 采样温度。对齐 macOS 版 `src/generate.py` 的 0.9：内置中转和部分渠道把范围夹在 [0,1]，
         * 发 1.2 会被上游直接拒（400 temperature参数非法）。
         */
        private const val TEMPERATURE = 0.9

        // MARK: URL 拼接
        //
        // base 带不带末尾 /chat/completions、/v1、/v4 都能拼对：
        //   https://api.deepseek.com                -> /chat/completions
        //   https://api.deepseek.com/v1             -> /v1/chat/completions
        //   https://open.bigmodel.cn/api/paas/v4    -> /api/paas/v4/chat/completions
        // Anthropic 形状对版本段单独处理（…/api/anthropic -> /v1/messages）。

        private val VERSION_SEG = Regex("^v\\d+$")

        fun chatURL(rawBase: String, kind: APIKind): String {
            val b = rawBase.trim().replace(Regex("/+$"), "")
            val last = b.split("/").lastOrNull()?.lowercase() ?: return b
            return when (kind) {
                APIKind.OPENAI -> if (last == "completions") b else "${b}/chat/completions"
                APIKind.ANTHROPIC -> when {
                    last == "messages" -> b
                    VERSION_SEG.matches(last) -> "${b}/messages"
                    else -> "${b}/v1/messages"
                }
            }
        }

        /** 两种响应形状的正文抽取 + 「思考型模型吃光额度」识别。 */
        fun extractText(data: JSONObject, kind: APIKind, model: String): String = when (kind) {
            APIKind.OPENAI -> {
                val choices = data.optJSONArray("choices")
                val msg = choices?.optJSONObject(0)?.optJSONObject("message")
                val text = msg?.optString("content", "").orEmpty()
                if (text.isNotBlank()) {
                    text
                } else {
                    val reasoning = when {
                        msg?.has("reasoning_content") == true -> msg.optString("reasoning_content")
                        msg?.has("reasoning") == true -> msg.optString("reasoning")
                        else -> ""
                    }
                    if (reasoning.isNotEmpty()) {
                        throw JevErrors.thinkingOnly(
                            "「${model}」是思考型模型：思考占满了额度，正文 0 条；" +
                                "请换非思考模型（如 deepseek-chat、glm-4-flash）"
                        )
                    }
                    throw JevErrors.emptyReply()
                }
            }

            APIKind.ANTHROPIC -> {
                val content = data.optJSONArray("content") ?: throw JevErrors.emptyReply()
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val t = content.optJSONObject(i)?.optString("text") ?: continue
                    sb.append(t)
                }
                if (sb.isEmpty()) throw JevErrors.emptyReply()
                sb.toString()
            }
        }
    }
}
