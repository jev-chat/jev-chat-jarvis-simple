package com.jev.simple.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.lifecycle.lifecycleScope
import com.jev.simple.R
import com.jev.simple.core.JUDGE_PRESETS
import com.jev.simple.core.JevConfig
import com.jev.simple.core.JevDraft
import com.jev.simple.core.JevError
import com.jev.simple.core.JevErrors
import com.jev.simple.core.JevJudge
import com.jev.simple.core.JevStore
import com.jev.simple.core.PROVIDER_PRESETS
import kotlinx.coroutines.launch

/** 模型页：生成层（必需）+ 判断层（可选）。改完即存，输入法下次分析生效。 */
object ProvidersPage {

    fun build(activity: MainActivity): View {
        val ctx = activity
        val cfg = JevStore.loadConfig()
        val out = ArrayList<View>()

        out += Ui.label(ctx, "模型", 20f, R.color.text_primary, bold = true)

        // ---------- 判断层 ----------
        out += sectionTitle(ctx, "判断层（Jev · 意图 + 风险 + 排序）")
        out += Ui.label(
            ctx,
            "核心判断引擎：一次调用出 8 类意图概率和 0–9 风险分布，并给候选排序。" +
                "没填 key 时退化为「盲起草」（只出候选）。地址填到动作段或带 /v1 都能拼对；" +
                "key 与生成层可以不是同一家。",
            12f,
            R.color.text_secondary,
        )

        val judgeBase = Ui.edit(ctx, "服务地址", cfg.judgeBase)
        val judgeKey = Ui.edit(ctx, "API Key", cfg.judgeKey)
        val judgeModel = Ui.edit(ctx, "模型", cfg.judgeModel)
        bind(judgeBase) { v -> save { it.copy(judgeBase = v) } }
        bind(judgeKey) { v -> save { it.copy(judgeKey = v) } }
        bind(judgeModel) { v -> save { it.copy(judgeModel = v) } }

        out += presetSpinner(
            ctx,
            JUDGE_PRESETS.map { it.name },
            JUDGE_PRESETS.indexOfFirst { it.base.isNotEmpty() && it.base == cfg.judgeBase },
        ) { pos ->
            val p = JUDGE_PRESETS[pos]
            if (p.id != "custom" && p.base.isNotEmpty()) {
                judgeBase.setText(p.base)
                judgeModel.setText(p.model)
            }
        }
        out += judgeBase
        out += judgeKey
        out += judgeModel

        val judgeStatus = Ui.label(
            ctx,
            "当前：${cfg.judgeModel} · Key ${JevStore.masked(cfg.judgeKey)}",
            11f,
            R.color.text_secondary,
        )
        out += testButton(activity, "判断层", judgeStatus) {
            val c = JevStore.loadConfig()
            val judge = JevJudge(c)
            if (!judge.isConfigured) throw JevErrors.missingKey("判断层 API Key")
            val jr = judge.judge("这个需求你今天跟一下", null)
            "✅ 成功：意图「${jr.intent}」（${(jr.confidence * 100).toInt()}%），风险 ${"%.1f".format(jr.risk)}/9"
        }
        out += judgeStatus

        // ---------- 生成层 ----------
        out += sectionTitle(ctx, "生成层（候选回复，必配）")

        val genBase = Ui.edit(ctx, "服务地址", cfg.genBase)
        val genKey = Ui.edit(ctx, "API Key", cfg.genKey)
        val genModel = Ui.edit(ctx, "模型", cfg.genModel)
        val genExtra = Ui.edit(ctx, "额外字段 JSON（可选）", cfg.genExtraJSON, mono = true)
        bind(genBase) { v -> save { it.copy(genBase = v) } }
        bind(genKey) { v -> save { it.copy(genKey = v) } }
        bind(genModel) { v -> save { it.copy(genModel = v) } }
        bind(genExtra) { v -> save { it.copy(genExtraJSON = v) } }

        out += presetSpinner(
            ctx,
            PROVIDER_PRESETS.map { it.name },
            PROVIDER_PRESETS.indexOfFirst { it.base.isNotEmpty() && it.base == cfg.genBase },
        ) { pos ->
            val p = PROVIDER_PRESETS[pos]
            if (p.id != "custom" && p.base.isNotEmpty()) {
                genBase.setText(p.base)
                genModel.setText(p.model)
            }
        }
        out += genBase
        out += genKey
        out += genModel
        out += genExtra

        val genStatus = Ui.label(
            ctx,
            genStatusLine(cfg),
            11f,
            R.color.text_secondary,
        )
        out += testButton(activity, "生成层", genStatus) {
            val c = JevStore.loadConfig()
            val draft = JevDraft(c)
            if (!draft.isConfigured) throw JevErrors.missingKey("生成层 API Key")
            val text = draft.call("回复两个字：收到").trim()
            "✅ 成功，模型回了：${text.take(40)}"
        }
        out += genStatus

        out += Ui.label(
            ctx,
            "不填 Key 时自动走内置中转（${com.jev.simple.core.JevBuiltin.baseURL} · " +
                "${com.jev.simple.core.JevBuiltin.model}），填了自己的 Key 就以你的为准。" +
                "别用思考型模型（思考会占满额度导致 0 条候选）。" +
                "端点需要额外字段关思考时改上面那行，默认已带 enable_thinking:false。",
            11f,
            R.color.text_secondary,
        )

        return Ui.page(ctx, out)
    }

    /** 显示实际生效的那一组，而不是输入框里的值——没填 key 时用的是内置中转。 */
    private fun genStatusLine(cfg: JevConfig): String {
        val g = cfg.generation
        val key = if (g.isBuiltin) "内置中转（免填）" else JevStore.masked(g.key)
        return "当前：${g.kind.id} · ${g.model} · Key ${key}"
    }

    private fun sectionTitle(ctx: android.content.Context, text: String): View =
        Ui.label(ctx, text, 15f, R.color.text_primary, bold = true).apply {
            setPadding(0, Ui.dp(ctx, 6), 0, 0)
        }

    private fun save(apply: (JevConfig) -> JevConfig) {
        JevStore.saveConfig(apply(JevStore.loadConfig()))
    }

    /** 字段 → 配置的即时同步。改一个字符存一次，SharedPreferences 的 apply 是异步的，够便宜。 */
    private fun bind(edit: EditText, apply: (String) -> Unit) {
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = apply(s?.toString().orEmpty())
        })
    }

    private fun presetSpinner(
        ctx: android.content.Context,
        names: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ): Spinner {
        val sp = Spinner(ctx)
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        if (selected in names.indices) sp.setSelection(selected)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onPick(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        sp.layoutParams = LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(ctx, 44),
        )
        return sp
    }

    /** 测试连接：真打一次最小请求，把「配置对不对」这件事变成一句话。 */
    private fun testButton(
        activity: MainActivity,
        label: String,
        statusLine: android.widget.TextView,
        run: suspend () -> String,
    ): View {
        val ctx = activity
        val btn = Ui.button(ctx, "测试「${label}」连接", primary = true, sizeSp = 14f)
        btn.layoutParams = LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 44))
        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "测试中…"
            statusLine.setTextColor(Ui.color(ctx, R.color.text_secondary))
            statusLine.text = "测试中…"
            activity.lifecycleScope.launch {
                val result = try {
                    run()
                } catch (e: JevError) {
                    "❌ ${e.message}"
                } catch (e: Exception) {
                    "❌ ${e.message ?: e.toString()}"
                }
                statusLine.text = result
                statusLine.setTextColor(
                    Ui.color(ctx, if (result.startsWith("✅")) R.color.risk_low else R.color.risk_max),
                )
                btn.isEnabled = true
                btn.text = "测试「${label}」连接"
            }
        }
        return btn
    }
}
