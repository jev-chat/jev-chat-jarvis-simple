package com.jev.simple.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.jev.simple.R
import com.jev.simple.core.Analysis
import com.jev.simple.core.Candidate
import com.jev.simple.core.ImeStatus
import com.jev.simple.core.JevPipeline
import com.jev.simple.core.JevStore
import com.jev.simple.core.MAX_SLOTS
import com.jev.simple.core.NONE_LABEL
import com.jev.simple.core.PER_TONE
import com.jev.simple.core.PipelineStage
import com.jev.simple.core.orderedToneNames
import com.jev.simple.ui.MainActivity
import com.jev.simple.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Jev 键盘：一个「回复面板」输入法，不是打字输入法。
 *
 * 交互闭环（不跳出聊天 App）：
 *   ① 在聊天里长按对方消息 → 复制
 *   ② 键盘上点「分析剪贴板」→ 意图/风险 + 每话术 2 条候选
 *   ③ 点候选 → 直接 commitText 进当前输入框（发送永远由用户手动完成）
 *
 * 与 iOS 键盘版的差别只有一处：Android 的输入法天生就有联网和读剪贴板的权限，
 * 不需要「允许完全访问」那道门禁，所以面板上没有 gate 页，配置也跟主 App 同进程共享。
 */
class JevImeService : InputMethodService() {

    private enum class Mode { IDLE, TONES, LOADING, RESULT, ERROR }

    private var mode = Mode.IDLE
    private var lastMessage = ""
    private var analysis: Analysis? = null
    private var errorText = ""
    private var stageLabel: TextView? = null
    private var flashTarget: TextView? = null
    private var job: Job? = null

    private lateinit var root: PanelLayout
    private lateinit var contentHost: LinearLayout
    private var topBar: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var fitBlocks: List<View> = emptyList()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 面板高度由内容决定，所以直接接管 onMeasure：输入法窗口是拿测量结果定键盘高度的。 */
    private class PanelLayout(ctx: Context) : LinearLayout(ctx) {
        var desiredHeightPx: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (desiredHeightPx > 0) {
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(desiredHeightPx, MeasureSpec.EXACTLY),
                )
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    // MARK: 生命周期

    override fun onCreate() {
        super.onCreate()
        JevStore.init(this)
    }

    override fun onCreateInputView(): View {
        if (!::root.isInitialized) {
            root = PanelLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Ui.color(this@JevImeService, R.color.panel_bg))
                setPadding(Ui.dp(this@JevImeService, 10), Ui.dp(this@JevImeService, 8),
                    Ui.dp(this@JevImeService, 10), Ui.dp(this@JevImeService, 8))
            }
            // 键盘容器自己那层底衬也刷成同一个色，深色模式下不会露出上下两条异色带
            runCatching {
                window?.window?.setBackgroundDrawable(
                    ColorDrawable(Ui.color(this, R.color.panel_bg)),
                )
            }

            val bar = buildTopBar()
            topBar = bar
            root.addView(
                bar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            contentHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            root.addView(
                contentHost,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        render()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 回写状态：主 App 的「开始」页据此显示键盘最近出现过没有
        JevStore.saveImeStatus(ImeStatus(System.currentTimeMillis()))
        prewarm()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 预热生成层连接。实测同一条起草请求，第二次能从 ~1.9 秒降到 ~0.5 秒——
     * 连接和中转上游都要热身。键盘一出现就用一个不消耗额度的 `GET /models` 把连接建起来，
     * 结果直接丢掉（失败也无所谓，真分析时该走的路径照走）。
     */
    private fun prewarm() {
        val g = JevStore.loadConfig().generation
        if (g.key.isEmpty() || g.base.isEmpty()) return
        val base = g.base.trimEnd('/')
        Thread {
            runCatching {
                val conn = (URL("${base}/models").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Authorization", "Bearer ${g.key}")
                }
                try {
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
        }.start()
    }

    // MARK: 顶栏：品牌 + 状态 + 切换键盘 + 删除

    private fun buildTopBar(): LinearLayout {
        val dot = View(this).apply {
            background = Ui.rounded(
                Ui.color(this@JevImeService, R.color.risk_low),
                radiusPx = Ui.dp(this@JevImeService, 4),
            )
            layoutParams = LinearLayout.LayoutParams(
                Ui.dp(this@JevImeService, 8),
                Ui.dp(this@JevImeService, 8),
            )
        }
        val status = Ui.label(this, "Jev · 就绪", 12f, R.color.text_secondary)

        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot)
            addView(Ui.space(this@JevImeService, 6, horizontal = true))
            addView(status)
        }

        val switchBtn = Ui.button(this, "切换键盘", false, 12f).apply {
            setOnClickListener { switchKeyboard() }
        }
        val backspace = Ui.button(this, "⌫ 删除", false, 12f).apply {
            setOnClickListener { backspace() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                title,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                switchBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Ui.dp(this@JevImeService, 32),
                ),
            )
            addView(Ui.space(this@JevImeService, 6, horizontal = true))
            addView(
                backspace,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Ui.dp(this@JevImeService, 32),
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    // MARK: 高度按内容实测定

    /**
     * 面板高度按内容实测定，而不是把某个值写死：写死高度 + 多余空间会把卡片拉伸出一大片空白，
     * 该占空间的候选滚动区反被挤成一条。这里把各块在真实宽度下的高度加起来定高，
     * 190dp 起、半屏封顶，超出的部分才交给滚动（与 iOS 版同一取舍）。
     */
    private fun refit() {
        if (!::root.isInitialized || fitBlocks.isEmpty()) return
        val dm = resources.displayMetrics
        val contentWidth = dm.widthPixels - Ui.dp(this, 20)
        if (contentWidth <= 0) return

        topBar?.measure(
            View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        var fixed = 0
        var scrollNatural = 0
        for (b in fitBlocks) {
            b.measure(
                View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            if (b === scrollView) scrollNatural = b.measuredHeight else fixed += b.measuredHeight
        }

        // 栈里 n 块之间有 n-1 个 6dp 占位，外加顶栏与内容之间的一个
        val gaps = Ui.dp(this, 6) * fitBlocks.size
        val chrome = (topBar?.measuredHeight ?: Ui.dp(this, 36)) + Ui.dp(this, 16) + gaps
        val total = chrome + fixed + scrollNatural

        val minH = Ui.dp(this, 190)
        val maxH = (dm.heightPixels * 0.5).toInt()
        val target = total.coerceIn(minH, maxH)

        scrollView?.let { sv ->
            val overflow = (total - target).coerceAtLeast(0)
            val h = (scrollNatural - overflow).coerceAtLeast(Ui.dp(this, 48))
            val lp = sv.layoutParams
            if (lp != null && lp.height != h) {
                lp.height = h
                sv.layoutParams = lp
            }
        }

        if (root.desiredHeightPx != target) {
            root.desiredHeightPx = target
            root.requestLayout()
        }
    }

    // MARK: 状态渲染

    private fun render() {
        if (!::contentHost.isInitialized) return
        contentHost.removeAllViews()
        scrollView = null
        stageLabel = null
        flashTarget = null

        val blocks: List<View> = when (mode) {
            Mode.IDLE -> buildIdle()
            Mode.TONES -> buildTones()
            Mode.LOADING -> buildLoading()
            Mode.RESULT -> buildResult()
            Mode.ERROR -> buildError()
        }
        contentHost.addView(
            Ui.stack(this, vertical = true, spacingDp = 6, children = blocks),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        fitBlocks = blocks
        root.post { refit() }
    }

    private fun setMode(m: Mode) {
        mode = m
        render()
    }

    // MARK: 待机视图

    private fun buildIdle(): List<View> {
        val cfg = JevStore.loadConfig()
        val out = ArrayList<View>()

        out += Ui.label(this, "长按对方消息 → 复制，再点下面的按钮", 12f, R.color.text_secondary)

        val clipBtn = Ui.button(this, "分析剪贴板", primary = true, sizeSp = 14f).apply {
            setOnClickListener { analyzeClipboard() }
        }
        val inputBtn = Ui.button(this, "AI 分析输入框文字", primary = false, sizeSp = 14f).apply {
            setOnClickListener { analyzeInputField() }
        }
        out += LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clipBtn, LinearLayout.LayoutParams(0, Ui.dp(this@JevImeService, 44), 1f))
            addView(Ui.space(this@JevImeService, 8, horizontal = true))
            addView(inputBtn, LinearLayout.LayoutParams(0, Ui.dp(this@JevImeService, 44), 1f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this@JevImeService, 44),
            )
        }

        // 话术：点进去直接在键盘上选（写回共享配置，App 的「话术」页看到的是同一份）
        out += Ui.button(
            this,
            if (cfg.activeSlots.isEmpty()) "话术：都没选（点这里选）"
            else "话术：" + cfg.activeSlots.joinToString(" · "),
            primary = false,
            sizeSp = 13f,
        ).apply {
            setOnClickListener { setMode(Mode.TONES) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this@JevImeService, 36),
            )
        }

        // 待机页**不放**发送键：这一页还没有候选，没有可发的东西；而输入框一旦有字，
        // 宿主 App 自己的发送按钮就出来了，键盘下方再挂一个只是添乱。发送键只在结果页。
        out += Ui.label(this, "配置模型 / 话术 →", 12f, R.color.text_secondary).apply {
            setPadding(0, Ui.dp(this@JevImeService, 2), 0, 0)
            setOnClickListener { openSettings() }
        }

        if (cfg.generation.key.isEmpty()) {
            out += Ui.label(
                this,
                "⚠️ 还没配置生成层：打开 Jev 简版 App →「模型」页填 API Key",
                12f,
                R.color.warn,
            )
        }
        return out
    }

    // MARK: 话术选择视图

    /** 话术选择：内置 + 自定义全列出来，点一下选中/取消，最多 MAX_SLOTS 个槽。
     *  每次从共享配置重新读（App 那边改过也能立刻看到），选中即落盘，下一次分析就生效。 */
    private fun buildTones(): List<View> {
        val cfg = JevStore.loadConfig()
        val names = orderedToneNames(cfg.customTones)
        val active = cfg.activeSlots
        val out = ArrayList<View>()

        out += Ui.label(
            this,
            "选话术（最多 ${MAX_SLOTS} 个 · 每个每次出 ${PER_TONE} 条）",
            12f,
            R.color.text_secondary,
        )

        // 每行 3 个等宽格子：话术名长短不一，等宽比按内容排更好点、也更整齐
        // 内置话术有 12 种，格子会超过面板高度上限——所以格子区自己滚，别把「好了」顶出去
        val gridBlocks = ArrayList<View>()
        var row = ArrayList<View>()
        for (name in names) {
            row.add(
                Ui.button(this, name, primary = active.contains(name), sizeSp = 13f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@JevImeService, 34), 1f)
                    setOnClickListener { toggleTone(name) }
                },
            )
            if (row.size == 3) {
                gridBlocks += gridRow(row)
                row = ArrayList()
            }
        }
        if (row.isNotEmpty()) {
            // 补齐到 3 个：不加空位的话，最后一行的单个话术会被权重拉成整行宽
            while (row.size < 3) {
                row.add(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@JevImeService, 34), 1f)
                })
            }
            gridBlocks += gridRow(row)
        }

        val gridScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(
                Ui.stack(this@JevImeService, vertical = true, spacingDp = 6, children = gridBlocks),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this@JevImeService, 140),
            )
        }
        scrollView = gridScroll
        out += gridScroll

        out += Ui.button(this, "好了", primary = true, sizeSp = 14f).apply {
            setOnClickListener { setMode(Mode.IDLE) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this@JevImeService, 38),
            )
        }
        return out
    }

    private fun gridRow(cells: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        cells.forEachIndexed { i, c ->
            if (i > 0) addView(Ui.space(this@JevImeService, 6, horizontal = true))
            addView(c)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Ui.dp(this@JevImeService, 34),
        )
    }

    private fun toggleTone(name: String) {
        val cfg = JevStore.loadConfig()
        val slots = cfg.slots.toMutableList()
        while (slots.size < MAX_SLOTS) slots.add(NONE_LABEL)
        val i = slots.indexOf(name)
        if (i >= 0) {
            slots[i] = NONE_LABEL                      // 再点一下 = 取消
        } else {
            val free = slots.indexOfFirst { it.isEmpty() || it == NONE_LABEL }
            if (free >= 0) slots[free] = name          // 填进第一个空槽
            else slots[MAX_SLOTS - 1] = name           // 槽满了就顶掉最后一个
        }
        JevStore.saveConfig(cfg.copy(slots = slots.take(MAX_SLOTS)))
        render()                                       // 重画刷新高亮
    }

    // MARK: 加载视图

    private fun buildLoading(): List<View> {
        val card = Ui.card(this)
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        stageLabel = Ui.label(this, "判断中…", 14f, R.color.text_secondary)
        val hstack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(spinner, LinearLayout.LayoutParams(Ui.dp(this@JevImeService, 22), Ui.dp(this@JevImeService, 22)))
            addView(Ui.space(this@JevImeService, 10, horizontal = true))
            addView(stageLabel)
        }
        card.addView(
            hstack,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 64),
            ),
        )
        return listOf(card)
    }

    // MARK: 结果视图

    private fun buildResult(): List<View> {
        val a = analysis ?: return listOf(Ui.label(this, "没有结果", 13f, R.color.text_secondary))
        val out = ArrayList<View>()

        // 判断头
        val header = Ui.card(this)
        val hcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val jr = a.judge
        if (jr != null) {
            // 风险等级文案跟徽章同一行——它单独占一行太浪费高度（键盘面板寸土寸金）
            val riskText = Ui.label(this, jr.riskLevelText, 12f, R.color.text_primary).apply {
                setTextColor(Ui.riskColor(this@JevImeService, jr.risk))
                maxLines = 1
            }
            hcol.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.badge(this@JevImeService, jr.intent, Ui.color(this@JevImeService, R.color.brand)))
                addView(Ui.space(this@JevImeService, 6, horizontal = true))
                addView(
                    Ui.badge(
                        this@JevImeService,
                        "风险 ${jr.risk.roundToInt()}/9",
                        Ui.riskColor(this@JevImeService, jr.risk),
                    ),
                )
                addView(Ui.space(this@JevImeService, 8, horizontal = true))
                addView(riskText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            if (jr.actions.isNotEmpty()) {
                hcol.addView(Ui.space(this, 4))
                hcol.addView(Ui.label(this, "建议：" + jr.actions.joinToString(" · "),
                    12f, R.color.text_secondary))
            }
        } else {
            hcol.addView(Ui.label(this, "未配置判断层，直接生成（可在 App 里开启）",
                12f, R.color.text_secondary))
        }
        hcol.addView(Ui.space(this, 4))
        val quoted = if (a.message.length > 40) a.message.take(40) + "…" else a.message
        hcol.addView(Ui.label(this, "「${quoted}」", 12f, R.color.text_secondary, lines = 1))
        header.addView(
            hcol,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        out += header

        // 候选列表（可滚动）。真没有候选时也要说一句话，别留给用户一片空白。
        val rows = ArrayList<View>()
        if (a.candidates.isEmpty()) {
            rows += Ui.label(this, "这次没出候选，点「换一批」再试一次", 13f, R.color.text_secondary)
        }
        for (c in a.candidates) rows += candidateRow(c)
        for (n in a.notices.take(2)) rows += Ui.label(this, "· ${n}", 11f, R.color.warn)

        val list = Ui.stack(this, vertical = true, spacingDp = 6, children = rows)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(
                list,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this@JevImeService, 90),
            )
        }
        scrollView = scroll
        out += scroll

        // 底部操作：发送放最右（像微信那样），左边留给换一批/返回
        val regen = Ui.button(this, "换一批", false, 13f).apply {
            setOnClickListener { regenerate() }
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@JevImeService, 38), 1f)
        }
        val close = Ui.button(this, "返回", false, 13f).apply {
            setOnClickListener { setMode(Mode.IDLE) }
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@JevImeService, 38), 1f)
        }
        val send = Ui.button(this, "发送", true, 13f).apply {
            setOnClickListener { sendMessage() }
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@JevImeService, 38), 1f)
        }
        out += LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(regen)
            addView(Ui.space(this@JevImeService, 8, horizontal = true))
            addView(close)
            addView(Ui.space(this@JevImeService, 8, horizontal = true))
            addView(send)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this@JevImeService, 38),
            )
        }

        // 时间脚注（插入/发送的反馈要临时改它）
        val footer = Ui.label(
            this,
            if (a.rankingPending) "候选已出 · 排序中…（现在就能点）"
            else "%.1f 秒 · 点候选插入，点「发送」发出".format(a.elapsed),
            10f,
            R.color.text_secondary,
        )
        flashTarget = footer
        out += footer
        return out
    }

    /** 一行候选：话术徽章 + 正文 +（可选）排序概率。点按整行插入。 */
    private fun candidateRow(c: Candidate): View {
        val chip = Ui.badge(this, c.tone, Ui.color(this, R.color.brand), 11f)
        val text = Ui.label(this, c.text, 14f, R.color.text_primary)
        val trailing = Ui.label(
            this,
            c.prob?.let { "%.0f%%".format(it * 100) } ?: "点按插入",
            11f,
            R.color.text_secondary,
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(
                Ui.color(this@JevImeService, R.color.card),
                radiusPx = Ui.dp(this@JevImeService, 10),
                stroke = Ui.color(this@JevImeService, R.color.card_border),
            )
            setPadding(Ui.dp(this@JevImeService, 8), Ui.dp(this@JevImeService, 8),
                Ui.dp(this@JevImeService, 8), Ui.dp(this@JevImeService, 8))
            isClickable = true
            addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Ui.space(this@JevImeService, 6, horizontal = true))
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.space(this@JevImeService, 6, horizontal = true))
            addView(trailing, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { insertCandidate(c) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    /** 即时反馈：目标那行小字短暂变色改字。用户要一眼能确认「点到了 / 插进去了 / 发出去了」。 */
    private fun flash(text: String, @androidx.annotation.ColorRes colorId: Int) {
        val target = flashTarget ?: return
        val base = target.text
        val baseColor = target.currentTextColor
        target.text = text
        target.setTextColor(Ui.color(this, colorId))
        root.postDelayed({
            target.text = base
            target.setTextColor(baseColor)
        }, 2500)
    }

    // MARK: 错误视图

    private fun buildError(): List<View> {
        val card = Ui.card(this)
        val vcol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.label(this@JevImeService, "出错了", 15f, R.color.risk_max, bold = true))
            addView(Ui.space(this@JevImeService, 6))
            addView(Ui.label(this@JevImeService, errorText, 13f, R.color.text_primary))
            addView(Ui.space(this@JevImeService, 8))
            addView(LinearLayout(this@JevImeService).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.button(this@JevImeService, "重试", true, 13f).apply {
                    setOnClickListener { regenerate() }
                    layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@JevImeService, 38), 1f)
                })
                addView(Ui.space(this@JevImeService, 8, horizontal = true))
                addView(Ui.button(this@JevImeService, "返回", false, 13f).apply {
                    setOnClickListener { setMode(Mode.IDLE) }
                    layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@JevImeService, 38), 1f)
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@JevImeService, 38)))
        }
        card.addView(
            vcol,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        // 错误文案（比如「这个 App 不吃键盘发送键」那段）可能很长，同样交给滚动
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this@JevImeService, 130),
            )
        }
        scrollView = scroll
        return listOf(scroll)
    }

    // MARK: 动作

    private fun analyzeClipboard() {
        val text = runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) ""
            else clip.getItemAt(0).coerceToText(this).toString()
        }.getOrDefault("").trim()

        if (text.isEmpty()) {
            errorText = "剪贴板是空的。先在聊天里长按要回的消息 →「复制」，再回来点分析。"
            setMode(Mode.ERROR)
            return
        }
        run(text)
    }

    private fun analyzeInputField() {
        val ic = currentInputConnection
        val before = ic?.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        val after = ic?.getTextAfterCursor(2000, 0)?.toString().orEmpty()
        val text = (before + after).trim()
        if (text.isEmpty()) {
            errorText = "输入框里没有文字。这个按钮分析的是当前输入框里已输入的内容（比如你打了一半拿不准的话）。"
            setMode(Mode.ERROR)
            return
        }
        run(text)
    }

    private fun regenerate() {
        if (lastMessage.isEmpty()) {
            setMode(Mode.IDLE)
            return
        }
        run(lastMessage)
    }

    private fun insertCandidate(c: Candidate) {
        val ic = currentInputConnection ?: return
        ic.commitText(c.text, 1)
        flash("已插入 · 点「发送」发出", R.color.risk_low)
    }

    /**
     * 发送。点的是用户，不是程序——本项目永不自作主张发送。
     * Android 比 iOS 强的地方在这里：输入法可以直接把宿主输入框的 editor action 触发掉
     * （微信这类「回车即发送」的输入框会真的发出去），iOS 只能塞一个换行碰运气。
     * 但仍有不吃这套的 App，所以发完回读输入框、按实际结果如实反馈，不假装成功。
     */
    private fun sendMessage() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        if (before.isEmpty()) {
            flash("输入框是空的：先点一条候选", R.color.warn)
            return
        }
        val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        root.postDelayed({
            val after = currentInputConnection?.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
            if (after.length < before.length) {
                flash("已发送 ✓", R.color.risk_low)
            } else {
                flash("这个 App 不吃键盘发送键，请点它自己的发送按钮", R.color.warn)
            }
        }, 600)
    }

    private fun run(message: String) {
        lastMessage = message
        setMode(Mode.LOADING)
        stageLabel?.text = "判断中…"
        val pipeline = JevPipeline(JevStore.loadConfig())

        job?.cancel()
        job = scope.launch {
            val result = pipeline.analyze(
                message = message,
                context = null,
                onStage = { stage ->
                    when (stage) {
                        PipelineStage.Judging -> stageLabel?.text = "判断中…"
                        is PipelineStage.Drafting -> stageLabel?.text = "生成中 ${stage.done}/${stage.total}…"
                        PipelineStage.Ranking -> stageLabel?.text = "排序中…"
                        PipelineStage.Done -> stageLabel?.text = "完成"
                    }
                },
                onPartial = { partial ->
                    // 第一条话术的候选一到就先出面板，不等其余话术、更不等排序。
                    // 用消息文本挡一下，别让上一轮的迟到结果盖掉新一轮。
                    if (lastMessage == partial.message) {
                        analysis = partial
                        setMode(Mode.RESULT)
                    }
                },
            )
            analysis = result
            val fatal = result.fatalError
            if (fatal != null) {
                errorText = fatal
                setMode(Mode.ERROR)
            } else {
                setMode(Mode.RESULT)
            }
        }
    }

    // MARK: 输入法系统交互

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    private fun backspace() {
        // IME 标准做法：发按键事件，宿主自己的删除逻辑（含它自己的联想/候选）才会正确跑起来
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun openSettings() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
