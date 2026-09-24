package com.jev.simple.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.jev.simple.R
import com.jev.simple.core.JevStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 开始页：键盘装没装、怎么用、隐私边界。对应 iOS 版的 SetupView。 */
object SetupPage {

    fun build(activity: MainActivity): View {
        val ctx: Context = activity
        val out = ArrayList<View>()

        out += Ui.label(ctx, "Jev 简版 · 键盘版", 20f, R.color.text_primary, bold = true)
        out += Ui.label(
            ctx,
            "在聊天里长按对方消息 →「复制」→ 在 Jev 键盘上点「分析剪贴板」，" +
                "读出意图 / 风险并给出候选，点一条就进输入框。\n" +
                "不跳 App、不切后台、不申请无障碍、不改任何聊天软件。",
            13f,
            R.color.text_secondary,
        )

        // 状态卡
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val installed = imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
        val status = JevStore.loadImeStatus()
        val lastSeen = status?.let {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it.lastSeen))
        } ?: "从未（说明还没在键盘里切到过 Jev 键盘）"

        val card = Ui.card(ctx)
        card.addView(Ui.label(ctx, "键盘状态", 14f, R.color.text_primary, bold = true))
        card.addView(Ui.space(ctx, 6))
        card.addView(Ui.label(
            ctx,
            (if (installed) "✅ 已装进系统键盘列表" else "⚠️ 还没在系统设置里启用"),
            13f,
            if (installed) R.color.risk_low else R.color.warn,
        ))
        card.addView(Ui.space(ctx, 4))
        card.addView(Ui.label(ctx, "最近一次出现：${lastSeen}", 12f, R.color.text_secondary))
        out += card

        val enableBtn = Ui.button(ctx, "打开输入法设置", primary = true, sizeSp = 14f).apply {
            setOnClickListener {
                runCatching { activity.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 46))
        }
        val pickBtn = Ui.button(ctx, "立刻切换到 Jev 键盘", primary = false, sizeSp = 14f).apply {
            setOnClickListener { imm.showInputMethodPicker() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 46))
        }
        out += enableBtn
        out += pickBtn

        val steps = Ui.card(ctx)
        steps.addView(Ui.label(ctx, "怎么用", 14f, R.color.text_primary, bold = true))
        steps.addView(Ui.space(ctx, 6))
        steps.addView(Ui.label(
            ctx,
            "① 点「打开输入法设置」→ 在「虚拟键盘 / 管理键盘」里把「Jev 键盘」打开\n" +
                "② 回到任意聊天框点出键盘 → 用键盘面板右上角的「切换键盘」换成 Jev\n" +
                "③ 长按对方那条消息 →「复制」→ 点面板上的「分析剪贴板」\n" +
                "④ 点中意的候选 → 文字进输入框 → 发送你自己点（本项目永不自动发送）",
            13f,
            R.color.text_primary,
        ))
        steps.addView(Ui.space(ctx, 8))
        steps.addView(Ui.label(
            ctx,
            "另一个按钮「AI 分析输入框文字」分析的是你已经打到一半、拿不准要不要发的话。",
            12f,
            R.color.text_secondary,
        ))
        out += steps

        val privacy = Ui.card(ctx)
        privacy.addView(Ui.label(ctx, "隐私边界", 14f, R.color.text_primary, bold = true))
        privacy.addView(Ui.space(ctx, 6))
        privacy.addView(Ui.label(
            ctx,
            "· 聊天内容只在点「分析」那一刻发往你自己配置的模型接口；无自建服务器、不落盘、不进日志\n" +
                "· API Key 存在本 App 私有目录，只有主 App 和你启用的这个输入法能读\n" +
                "· 输入法不监听、不上传按键，也没申请无障碍 / 录屏 / 存储权限\n" +
                "· 候选只写进输入框，发送永远由你手动完成",
            12f,
            R.color.text_secondary,
        ))
        out += privacy

        return Ui.page(ctx, out)
    }
}
