package com.jev.simple.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.jev.simple.R
import com.jev.simple.core.Analysis
import com.jev.simple.core.JevPipeline
import com.jev.simple.core.JevStore
import com.jev.simple.core.PipelineStage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 试一试页：把一句话跑完整条链路（判断 → 起草 → 排序），用来验证配置通没通。 */
object PlaygroundPage {

    fun build(activity: MainActivity): View {
        val ctx: Context = activity
        val out = ArrayList<View>()

        out += Ui.label(ctx, "试一试", 20f, R.color.text_primary, bold = true)
        out += Ui.label(
            ctx,
            "把对方发来的那句话贴进来，跑一遍完整链路。这里的耗时就是键盘上的真实水平。",
            12f,
            R.color.text_secondary,
        )

        val input = Ui.edit(ctx, "比如：这个需求你今天跟一下", "", multiline = true)
        input.minLines = 3
        out += input

        val status = Ui.label(ctx, "", 12f, R.color.text_secondary)
        val resultHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val runBtn = Ui.button(ctx, "分析这条消息", primary = true, sizeSp = 15f).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 46))
        }
        runBtn.setOnClickListener {
            val msg = input.text.toString().trim()
            if (msg.isEmpty()) {
                status.text = "先在上面写一句要回的话"
                return@setOnClickListener
            }
            runBtn.isEnabled = false
            runBtn.text = "分析中…"
            status.text = "判断中…"
            resultHost.removeAllViews()
            val pipeline = JevPipeline(JevStore.loadConfig())

            activity.lifecycleScope.launch {
                val result = pipeline.analyze(
                    message = msg,
                    context = null,
                    onStage = { stage ->
                        status.text = when (stage) {
                            PipelineStage.Judging -> "判断中…"
                            is PipelineStage.Drafting -> "生成中 ${stage.done}/${stage.total}…"
                            PipelineStage.Ranking -> "排序中…"
                            PipelineStage.Done -> "完成"
                        }
                    },
                    onPartial = { partial -> render(ctx, resultHost, partial, partial = true) },
                )
                render(ctx, resultHost, result, partial = false)
                status.text = if (result.fatalError != null) "失败" else "完成"
                runBtn.isEnabled = true
                runBtn.text = "分析这条消息"
            }
        }
        out += runBtn
        out += status
        out += resultHost
        return Ui.page(ctx, out)
    }

    private fun render(ctx: Context, host: LinearLayout, a: Analysis, partial: Boolean) {
        host.removeAllViews()
        val children = ArrayList<View>()

        val fatal = a.fatalError
        if (fatal != null) {
            val card = Ui.card(ctx)
            card.addView(Ui.label(ctx, "出错了", 15f, R.color.risk_max, bold = true))
            card.addView(Ui.space(ctx, 6))
            card.addView(Ui.label(ctx, fatal, 13f, R.color.text_primary))
            children += card
            host.addView(Ui.stack(ctx, vertical = true, spacingDp = 8, children = children))
            return
        }

        // 判断头
        val header = Ui.card(ctx)
        val jr = a.judge
        if (jr != null) {
            header.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.badge(ctx, jr.intent, Ui.color(ctx, R.color.brand)))
                addView(Ui.space(ctx, 6, horizontal = true))
                addView(Ui.badge(ctx, "风险 ${jr.risk.roundToInt()}/9", Ui.riskColor(ctx, jr.risk)))
                addView(Ui.space(ctx, 8, horizontal = true))
                addView(Ui.label(ctx, jr.riskLevelText, 12f, R.color.text_primary).apply {
                    setTextColor(Ui.riskColor(ctx, jr.risk))
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            if (jr.actions.isNotEmpty()) {
                header.addView(Ui.space(ctx, 4))
                header.addView(Ui.label(ctx, "建议：" + jr.actions.joinToString(" · "),
                    12f, R.color.text_secondary))
            }
            val probs = jr.intentProbs.entries.sortedByDescending { it.value }.take(3)
            if (probs.isNotEmpty()) {
                header.addView(Ui.space(ctx, 4))
                header.addView(Ui.label(
                    ctx,
                    "意图分布：" + probs.joinToString(" · ") {
                        "${it.key} ${(it.value * 100).roundToInt()}%"
                    },
                    11f,
                    R.color.text_secondary,
                ))
            }
        } else {
            header.addView(Ui.label(ctx, "未配置判断层，已盲起草（只出候选，无意图/风险）",
                12f, R.color.text_secondary))
        }
        header.addView(Ui.space(ctx, 4))
        val quoted = if (a.message.length > 60) a.message.take(60) + "…" else a.message
        header.addView(Ui.label(ctx, "「${quoted}」", 12f, R.color.text_secondary))
        children += header

        // 候选
        for (c in a.candidates) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(
                    Ui.color(ctx, R.color.card),
                    radiusPx = Ui.dp(ctx, 10),
                    stroke = Ui.color(ctx, R.color.card_border),
                )
                setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10))
                addView(Ui.badge(ctx, c.tone, Ui.color(ctx, R.color.brand), 11f))
                addView(Ui.space(ctx, 8, horizontal = true))
                addView(Ui.label(ctx, c.text, 14f, R.color.text_primary),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.space(ctx, 8, horizontal = true))
                addView(Ui.label(
                    ctx,
                    c.prob?.let { "%.0f%%".format(it * 100) } ?: "—",
                    11f,
                    R.color.text_secondary,
                ))
            }
            children += row
        }
        if (a.candidates.isEmpty() && !partial) {
            children += Ui.label(ctx, "这次没出候选", 13f, R.color.text_secondary)
        }

        for (n in a.notices) {
            children += Ui.label(ctx, "· ${n}", 11f, R.color.warn)
        }
        children += Ui.label(
            ctx,
            if (a.rankingPending) "候选已出 · 排序中…" else "%.1f 秒".format(a.elapsed),
            11f,
            R.color.text_secondary,
        )

        host.addView(Ui.stack(ctx, vertical = true, spacingDp = 8, children = children))
    }
}
