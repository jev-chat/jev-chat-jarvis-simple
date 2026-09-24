package com.jev.simple.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.jev.simple.R
import com.jev.simple.core.BUILTIN_TONES
import com.jev.simple.core.JevStore
import com.jev.simple.core.MAX_SLOTS
import com.jev.simple.core.NONE_LABEL
import com.jev.simple.core.PER_TONE
import com.jev.simple.core.allTones
import com.jev.simple.core.orderedToneNames

/** 话术页：选槽位 + 维护自定义话术。与输入法面板里的「话术」页读写同一份配置。 */
object TonesPage {

    fun build(activity: MainActivity): View {
        val ctx: Context = activity
        val out = ArrayList<View>()

        out += Ui.label(ctx, "话术", 20f, R.color.text_primary, bold = true)
        out += Ui.label(
            ctx,
            "每个选中的话术各出 ${PER_TONE} 条候选（前一条稳妥、后一条把语气做足）。" +
                "最多 ${MAX_SLOTS} 个槽位——屏幕就这么大，槽位再多候选就滚不完了。",
            12f,
            R.color.text_secondary,
        )

        // ---------- 槽位 ----------
        val cfg = JevStore.loadConfig()
        val active = cfg.activeSlots
        val chips = LinkedHashMap<String, TextView>()
        val names = orderedToneNames(cfg.customTones)

        val chipChildren = ArrayList<View>()
        var row = ArrayList<View>()
        for (name in names) {
            val chip = Ui.button(ctx, name, primary = active.contains(name), sizeSp = 12f).apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(ctx, 40), 1f)
            }
            chips[name] = chip
            row.add(chip)
            if (row.size == 2) {
                chipChildren.add(row[0])
                chipChildren.add(row[1])
                row = ArrayList()
            }
        }
        if (row.isNotEmpty()) {
            while (row.size < 2) {
                row.add(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, Ui.dp(ctx, 40), 1f)
                })
            }
            chipChildren.add(row[0])
            chipChildren.add(row[1])
        }
        // 两列一格：话术名通常 4-6 个字，两列比三列更不容易被挤成省略号
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var i = 0
        while (i < chipChildren.size) {
            val pair = listOf(chipChildren[i], chipChildren[i + 1])
            grid.addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEachIndexed { idx, c ->
                        if (idx > 0) addView(Ui.space(ctx, 8, horizontal = true))
                        addView(c)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 40))
                },
            )
            grid.addView(Ui.space(ctx, 8))
            i += 2
        }
        out += grid

        for ((name, chip) in chips) {
            chip.setOnClickListener { toggleSlot(ctx, name, chips, cfg) }
        }

        // ---------- 自定义话术 ----------
        out += Ui.label(ctx, "自定义话术", 15f, R.color.text_primary, bold = true).apply {
            setPadding(0, Ui.dp(ctx, 6), 0, 0)
        }
        out += Ui.label(
            ctx,
            "说明写成「什么语气 + 别变成什么」最管用（内置话术就是按这个写法调出来的）。" +
                "同名会覆盖内置话术。",
            12f,
            R.color.text_secondary,
        )

        val nameEdit = Ui.edit(ctx, "话术名（如：佛系同事）", "")
        val descEdit = Ui.edit(ctx, "说明（人设 + 口头禅 + 上限约束）", "")
        descEdit.maxLines = 4
        descEdit.minLines = 3
        out += nameEdit
        out += descEdit

        val addBtn = Ui.button(ctx, "保存这个话术", primary = true, sizeSp = 14f).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 44))
            setOnClickListener {
                val n = nameEdit.text.toString().trim()
                val d = descEdit.text.toString().trim()
                if (n.isEmpty() || d.isEmpty()) return@setOnClickListener
                val cur = JevStore.loadConfig()
                JevStore.saveConfig(cur.copy(customTones = cur.customTones + (n to d)))
                activity.refresh()
            }
        }
        out += addBtn

        val custom = cfg.customTones
        if (custom.isEmpty()) {
            out += Ui.label(ctx, "（还没有自定义话术）", 12f, R.color.text_secondary)
        } else {
            val merged = allTones(custom)
            for ((n, d) in custom) {
                val overrides = BUILTIN_TONES.containsKey(n)
                val card = Ui.card(ctx)
                card.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Ui.label(ctx, n, 14f, R.color.text_primary, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Ui.label(
                        ctx,
                        if (overrides) "覆盖内置" else "自定义",
                        11f,
                        if (overrides) R.color.warn else R.color.text_secondary,
                    ))
                })
                card.addView(Ui.space(ctx, 4))
                card.addView(Ui.label(ctx, merged[n] ?: d, 12f, R.color.text_secondary, lines = 4))
                card.addView(Ui.space(ctx, 6))
                card.addView(Ui.button(ctx, "删除", false, 12f).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 32))
                    setOnClickListener {
                        val cur = JevStore.loadConfig()
                        JevStore.saveConfig(cur.copy(customTones = cur.customTones - n))
                        activity.refresh()
                    }
                })
                out += card
            }
        }

        out += Ui.label(
            ctx,
            "内置话术共 ${BUILTIN_TONES.size} 种，口径与 macOS / Windows / iOS 版一致。",
            11f,
            R.color.text_secondary,
        )

        return Ui.page(ctx, out)
    }

    /** 点一下选中/取消，最多 MAX_SLOTS 个槽；槽满了顶掉最后一个。写完立刻落盘，输入法下次分析就用新槽位。 */
    private fun toggleSlot(
        ctx: Context,
        name: String,
        chips: Map<String, TextView>,
        snapshot: com.jev.simple.core.JevConfig,
    ) {
        val cfg = JevStore.loadConfig()
        val slots = cfg.slots.toMutableList()
        while (slots.size < MAX_SLOTS) slots.add(NONE_LABEL)
        val i = slots.indexOf(name)
        if (i >= 0) {
            slots[i] = NONE_LABEL
        } else {
            val free = slots.indexOfFirst { it.isEmpty() || it == NONE_LABEL }
            if (free >= 0) slots[free] = name else slots[MAX_SLOTS - 1] = name
        }
        val saved = cfg.copy(slots = slots.take(MAX_SLOTS))
        JevStore.saveConfig(saved)

        // 只改颜色，不重建页面：重建会把滚动位置丢掉，手感很差
        val now = saved.activeSlots
        for ((n, chip) in chips) Ui.styleButton(ctx, chip, now.contains(n))
    }
}
