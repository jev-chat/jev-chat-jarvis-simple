package com.jev.simple.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// MARK: - 配置模型

/** 生成层 API 形状。key 所在的组决定请求形状（与 macOS 版同一规则）。 */
enum class APIKind(val id: String, val label: String) {
    OPENAI("openai", "OpenAI 兼容（/chat/completions）"),
    ANTHROPIC("anthropic", "Anthropic 兼容（/v1/messages）");

    companion object {
        fun from(id: String?): APIKind = values().firstOrNull { it.id == id } ?: OPENAI
    }
}

/**
 * 随包分发的内置凭据：一个 key 都没配时用它，应用开箱就能出候选。
 * 与 macOS / iOS 版同一套值，换 token 只改这几行。
 *
 * ⚠️ 这些值随包分发就等于公开：任何人解开 APK 或翻仓库都能拿到。
 * 所以这里放的必须是**专用 token**（模型白名单 + 额度封顶 + 过期时间），而不是主账号 key。
 * 把 apiKey 留空 = 退回老行为：必须自己配，否则候选区只显示「还没配置生成层」。
 */
object JevBuiltin {
    const val apiKey = "sk-WwZDJLxyZSiLESeLjVTySpCiwjcNoJauuGVkWPNEpI2NyDbQ"

    /** 自建中转（One API / New API） */
    const val baseURL = "http://101.132.131.220:11111/v1"
    const val model = "glm-4-flash"

    /** 关思考：glm-4-flash 忽略未知字段，Qwen3 那类不关会慢到 85 秒 */
    const val extraBody = "{\"enable_thinking\": false}"
}

data class ProviderPreset(
    val id: String,
    val name: String,
    val kind: APIKind,
    val base: String,
    val model: String,
    val keyHint: String,
)

/** 与 Windows 版内置预设同一批（DeepSeek 国内直连最快、智谱 glm-4-flash 免费、
 *  OpenRouter 一个 key 全模型、通义便宜、Ollama 完全本地）。 */
val PROVIDER_PRESETS: List<ProviderPreset> = listOf(
    ProviderPreset("builtin", "内置中转（开箱即用，免填 Key）", APIKind.OPENAI,
        JevBuiltin.baseURL, JevBuiltin.model, "不用填：留空即走内置 token"),
    ProviderPreset("zhipu", "智谱（glm-4-flash 免费）", APIKind.OPENAI,
        "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "open.bigmodel.cn 的 API Key"),
    ProviderPreset("deepseek", "DeepSeek 官方", APIKind.OPENAI,
        "https://api.deepseek.com", "deepseek-chat", "platform.deepseek.com 的 sk-…"),
    ProviderPreset("openrouter", "OpenRouter", APIKind.OPENAI,
        "https://openrouter.ai/api/v1", "deepseek/deepseek-chat-v3.1", "sk-or-…"),
    ProviderPreset("dashscope", "阿里通义（兼容模式）", APIKind.OPENAI,
        "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash", "sk-…"),
    ProviderPreset("moonshot", "月之暗面 Kimi", APIKind.OPENAI,
        "https://api.moonshot.cn/v1", "moonshot-v1-8k", "sk-…"),
    ProviderPreset("siliconflow", "硅基流动", APIKind.OPENAI,
        "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", "sk-…"),
    ProviderPreset("ollama", "Ollama（同局域网）", APIKind.OPENAI,
        "http://127.0.0.1:11434/v1", "qwen2.5:7b", "随便填，如 ollama"),
    ProviderPreset("custom", "自定义…", APIKind.OPENAI, "", "", ""),
)

data class JudgePreset(
    val id: String,
    val name: String,
    val base: String,
    val model: String,
    val keyHint: String,
)

/** 判断层预设。Jev native 在 waitlist，网关同形状只换地址+模型+key。 */
val JUDGE_PRESETS: List<JudgePreset> = listOf(
    JudgePreset("typesafe", "TypeSafe 直连", "https://api.typesafe.ai", "jev-latest",
        "api.typesafe.ai 的 key"),
    JudgePreset("openrouter", "OpenRouter 网关",
        "https://openrouter.ai/api/alpha/decisions", "typesafe/jev-1.13", "OpenRouter 的 sk-or-…"),
    JudgePreset("vercel", "Vercel AI Gateway",
        "https://ai-gateway.vercel.sh/v1/evaluate", "typesafe-ai/jev", "Vercel AI Gateway 的 key"),
    JudgePreset("custom", "自定义…", "", "", ""),
)

/** 生成层实际生效的那一组。URL / key / 模型同源，不跨来源混搭——
 *  混搭就是拿 A 家的 key 调 B 家的端点，换来一个看不懂的 401。 */
data class GenCredentials(
    val kind: APIKind,
    val base: String,
    val key: String,
    val model: String,
    val extraJSON: String,
    /** true = 这一组来自内置中转，不是用户自己配的 */
    val isBuiltin: Boolean,
)

/**
 * 话术槽上限。手机屏幕高度有限，槽位越多候选越多，面板会顶到半个屏幕以上，
 * 2 槽 4 条是屏幕占用与可选性的平衡点（与 iOS 版同一取舍）。
 */
const val MAX_SLOTS = 2

/** 全部配置。存 SharedPreferences，输入法与主 App 共享同一份。
 *  Android 上输入法和主 App 在同一个 APK / 同一个进程，所以不像 iOS 那样需要 App Group。 */
data class JevConfig(
    // 生成层（用户没填 key 时自动回退到 JevBuiltin，见 `generation`）
    val genKind: APIKind = APIKind.OPENAI,
    val genBase: String = JevBuiltin.baseURL,
    val genKey: String = "",
    val genModel: String = JevBuiltin.model,
    /** 额外请求体字段（JSON），端点要靠额外字段关思考模式时填 */
    val genExtraJSON: String = "{\"enable_thinking\": false}",

    // 判断层（Jev：意图 + 风险 + 排序，核心判断引擎）。
    // 没配 key 时管线自动退化为「盲起草」——只出候选、无意图/风险，运行时兜底而非配置开关。
    val judgeBase: String = "https://api.typesafe.ai",
    val judgeKey: String = "",
    val judgeModel: String = "jev-latest",

    /** 话术槽位。空串 = 不用（与 NONE_LABEL 同语义）。 */
    val slots: List<String> = listOf("高情商话术", "稳如老狗"),

    /** 用户自定义话术（名字 = 说明），同名覆盖内置。 */
    val customTones: Map<String, String> = emptyMap(),
) {
    val activeSlots: List<String> get() = slots.filter { it.isNotEmpty() }.take(MAX_SLOTS)

    /** 生成层实际会用的凭据：用户填了 key 就用他那一整组，一个都没填才回退到内置中转
     *  （与 macOS 版 `src/generate.py` 同序：内置永远不会盖掉用户显式配的那一组）。 */
    val generation: GenCredentials
        get() {
            if (genKey.isNotEmpty()) {
                return GenCredentials(
                    kind = genKind,
                    base = genBase.ifEmpty { JevBuiltin.baseURL },
                    key = genKey,
                    model = genModel.ifEmpty { JevBuiltin.model },
                    extraJSON = genExtraJSON,
                    isBuiltin = false,
                )
            }
            if (JevBuiltin.apiKey.isEmpty()) {
                return GenCredentials(genKind, genBase, "", genModel, genExtraJSON, false)
            }
            return GenCredentials(APIKind.OPENAI, JevBuiltin.baseURL, JevBuiltin.apiKey,
                JevBuiltin.model, JevBuiltin.extraBody, true)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("genKind", genKind.id)
        put("genBase", genBase)
        put("genKey", genKey)
        put("genModel", genModel)
        put("genExtraJSON", genExtraJSON)
        put("judgeBase", judgeBase)
        put("judgeKey", judgeKey)
        put("judgeModel", judgeModel)
        put("slots", JSONArray(slots))
        val tones = JSONObject()
        for ((k, v) in customTones) tones.put(k, v)
        put("customTones", tones)
    }

    companion object {
        fun fromJson(o: JSONObject): JevConfig {
            val slotsArr = o.optJSONArray("slots")
            val slots = ArrayList<String>()
            if (slotsArr != null) for (i in 0 until slotsArr.length()) slots.add(slotsArr.optString(i))
            val tonesObj = o.optJSONObject("customTones")
            val tones = LinkedHashMap<String, String>()
            if (tonesObj != null) {
                for (k in tonesObj.keys()) tones[k] = tonesObj.optString(k)
            }
            return JevConfig(
                genKind = APIKind.from(o.optString("genKind", APIKind.OPENAI.id)),
                genBase = o.optString("genBase", JevBuiltin.baseURL),
                genKey = o.optString("genKey", ""),
                genModel = o.optString("genModel", JevBuiltin.model),
                genExtraJSON = o.optString("genExtraJSON", "{\"enable_thinking\": false}"),
                judgeBase = o.optString("judgeBase", "https://api.typesafe.ai"),
                judgeKey = o.optString("judgeKey", ""),
                judgeModel = o.optString("judgeModel", "jev-latest"),
                slots = if (slots.isEmpty()) listOf("高情商话术", "稳如老狗") else slots,
                customTones = tones,
            )
        }
    }
}

/** 输入法侧回写的运行状态，主 App 的引导页用它判断「键盘装没装、最近出现过没」。
 *  Android 的输入法不需要「允许完全访问」这道门禁——联网和读剪贴板都是它天然就有的，
 *  所以这里只记「最近一次出现时间」。 */
data class ImeStatus(val lastSeen: Long)

// MARK: - 存储

/** 配置与状态的唯一存放点：SharedPreferences（MODE_PRIVATE，仅本 App 与输入法可读）。 */
object JevStore {
    private const val FILE = "jev"
    private const val CONFIG_KEY = "jev.config.v1"
    private const val STATUS_KEY = "jev.ime.status.v1"

    @Volatile
    private var appCtx: Context? = null

    /** 主 App 与输入法启动时各调一次。 */
    fun init(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    private val prefs: SharedPreferences
        get() = checkNotNull(appCtx) { "JevStore.init 未调用" }
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun loadConfig(): JevConfig {
        val raw = prefs.getString(CONFIG_KEY, null) ?: return JevConfig()
        return runCatching { JevConfig.fromJson(JSONObject(raw)) }.getOrElse { JevConfig() }
    }

    fun saveConfig(cfg: JevConfig) {
        prefs.edit().putString(CONFIG_KEY, cfg.toJson().toString()).apply()
    }

    fun loadImeStatus(): ImeStatus? {
        val t = prefs.getLong(STATUS_KEY, 0L)
        return if (t == 0L) null else ImeStatus(t)
    }

    fun saveImeStatus(s: ImeStatus) {
        prefs.edit().putLong(STATUS_KEY, s.lastSeen).apply()
    }

    /** 密钥展示用掩码 */
    fun masked(key: String): String {
        if (key.isEmpty()) return "（未配置）"
        if (key.length <= 8) return "•".repeat((key.length - 2).coerceAtLeast(2)) + key.takeLast(2)
        return key.take(4) + "…" + key.takeLast(4)
    }
}
