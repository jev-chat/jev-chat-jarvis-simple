package com.jev.simple.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.jev.simple.R
import kotlin.math.roundToInt

/**
 * 视图工厂。输入法与主 App 共用，全部用系统控件、无第三方依赖、无图片资源；
 * 颜色走 values-night 限定符支持深色模式（对应 iOS 版的动态 UIColor）。
 */
object Ui {

    fun dp(ctx: Context, v: Number): Int =
        (v.toFloat() * ctx.resources.displayMetrics.density).roundToInt()

    fun color(ctx: Context, @ColorRes id: Int): Int = ContextCompat.getColor(ctx, id)

    /** 与 iOS 版 KB.riskColor 同口径：0-2 绿、3-5 黄、6-7 橙、8-9 红。 */
    fun riskColorRes(score: Double): Int = when {
        score < 3 -> R.color.risk_low
        score < 6 -> R.color.risk_mid
        score < 8 -> R.color.risk_high
        else -> R.color.risk_max
    }

    fun riskColor(ctx: Context, score: Double): Int = color(ctx, riskColorRes(score))

    fun withAlpha(c: Int, factor: Float): Int =
        Color.argb((255 * factor).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))

    fun rounded(fill: Int, radiusPx: Int, stroke: Int? = null, strokePx: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusPx.toFloat()
            if (stroke != null) setStroke(strokePx, stroke)
        }

    fun cardBg(ctx: Context): GradientDrawable = rounded(
        fill = color(ctx, R.color.card),
        radiusPx = dp(ctx, 12),
        stroke = color(ctx, R.color.card_border),
    )

    fun label(
        ctx: Context,
        text: String = "",
        sizeSp: Float = 14f,
        @ColorRes colorId: Int = R.color.text_primary,
        bold: Boolean = false,
        lines: Int = Int.MAX_VALUE,
        align: Int = Gravity.START,
    ): TextView {
        val v = TextView(ctx)
        v.text = text
        v.textSize = sizeSp
        v.setTextColor(color(ctx, colorId))
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        v.maxLines = lines
        v.gravity = align
        return v
    }

    /** 卡片：圆角 + 1px 描边，对应 iOS 的 KB.cardView()。内部纵向排布。 */
    fun card(ctx: Context): LinearLayout {
        val v = LinearLayout(ctx)
        v.orientation = LinearLayout.VERTICAL
        v.background = cardBg(ctx)
        val p = dp(ctx, 10)
        v.setPadding(p, p, p, p)
        return v
    }

    fun button(ctx: Context, title: String, primary: Boolean = false, sizeSp: Float = 15f): TextView {
        val v = TextView(ctx)
        v.text = title
        v.textSize = sizeSp
        v.gravity = Gravity.CENTER
        v.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        styleButton(ctx, v, primary)
        v.isClickable = true
        v.isFocusable = true
        return v
    }

    /** 就地切换主/次样式：话术格子点一下只改颜色，不重建视图（重建会把滚动位置和触摸状态丢掉）。 */
    fun styleButton(ctx: Context, v: TextView, primary: Boolean) {
        val fill = if (primary) color(ctx, R.color.brand) else color(ctx, R.color.btn_bg)
        v.background = rounded(fill, radiusPx = dp(ctx, 10))
        v.setTextColor(color(ctx, if (primary) R.color.on_brand else R.color.text_primary))
    }

    /** 小圆角徽章：意图、话术、风险。 */
    fun badge(ctx: Context, text: String, colorInt: Int, sizeSp: Float = 12f): TextView {
        val v = TextView(ctx)
        v.text = "  ${text}  "
        v.textSize = sizeSp
        v.setTextColor(colorInt)
        v.background = rounded(
            fill = withAlpha(colorInt, 0.10f),
            radiusPx = dp(ctx, 5),
            stroke = withAlpha(colorInt, 0.5f),
        )
        v.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
        v.maxLines = 1
        return v
    }

    fun space(ctx: Context, sizeDp: Int, horizontal: Boolean = false): View {
        val v = View(ctx)
        val px = dp(ctx, sizeDp)
        v.layoutParams = if (horizontal) {
            LinearLayout.LayoutParams(px, 1)
        } else {
            // 纵向占位必须给高度：两个参数都写死，免得被 LinearLayout 按 0 算
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
        }
        return v
    }

    /** 纵向/横向排布一组块，块之间插占位视图。对应 iOS 的 UIStackView(spacing:)。 */
    fun stack(ctx: Context, vertical: Boolean, spacingDp: Int, children: List<View>): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        children.forEachIndexed { i, c ->
            if (i > 0 && spacingDp > 0) l.addView(space(ctx, spacingDp, horizontal = !vertical))
            l.addView(c)
        }
        return l
    }

    /** 整页骨架：可滚动的纵向容器，块之间留 spacingDp。 */
    fun page(ctx: Context, children: List<View>, spacingDp: Int = 10): ScrollView {
        val col = stack(ctx, vertical = true, spacingDp = spacingDp, children = children)
        val p = dp(ctx, 14)
        col.setPadding(p, p, p, p)
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(
            col,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return sv
    }

    fun edit(
        ctx: Context,
        hint: String,
        value: String,
        mono: Boolean = false,
        numeric: Boolean = false,
        multiline: Boolean = false,
    ): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.textSize = 13f
        if (mono) e.typeface = Typeface.MONOSPACE
        e.inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            // 多行必须真的带上 MULTI_LINE 标志：只设 maxLines 的话输入法拿到的仍是单行语义，
            // 回车会被当成 DONE 把键盘收掉（而不是换行）
            multiline -> InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        e.maxLines = if (multiline) 6 else 1
        if (multiline) e.gravity = Gravity.TOP or Gravity.START
        e.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
        e.background = rounded(color(ctx, R.color.btn_bg), radiusPx = dp(ctx, 8))
        e.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return e
    }
}
