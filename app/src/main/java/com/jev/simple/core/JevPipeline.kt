package com.jev.simple.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// MARK: - 端到端管线：判断 → 起草（每话术并发）→ 排序
//
// 阶段间是「带截止时间的串行」：判断通常 ~1 秒，等它出来再把意图喂给起草，
// 候选质量明显更好；判断超时或没配 key 时直接盲起草（与 iOS / Windows 版同款回退）。
// 排序失败按「每个话术的第 1 条（稳妥款）在前」的默认顺序展示。

data class Candidate(val text: String, val tone: String, val prob: Double?)

data class Analysis(
    val message: String,
    val judge: JudgeResult? = null,
    val candidates: List<Candidate> = emptyList(),
    /** 非致命错误（某话术失败、排序失败），界面黄条展示 */
    val notices: List<String> = emptyList(),
    /** 致命错误（生成层全挂），界面红条展示 */
    val fatalError: String? = null,
    val elapsed: Double = 0.0,
    /** 候选已经出来了、但排序还没回来（界面据此提示"排序中"，并允许先点候选） */
    val rankingPending: Boolean = false,
)

sealed class PipelineStage {
    object Judging : PipelineStage()
    data class Drafting(val done: Int, val total: Int) : PipelineStage()
    object Ranking : PipelineStage()
    object Done : PipelineStage()
}

class JevPipeline(private val cfg: JevConfig) {
    private val judge = JevJudge(cfg)
    private val draft = JevDraft(cfg)

    val generationConfigured: Boolean get() = draft.isConfigured

    /** 一轮起草的结果 */
    private data class DraftRound(
        val candidates: List<Candidate> = emptyList(),
        val notices: List<String> = emptyList(),
    )

    private class DraftPiece(val name: String, val texts: List<String>, val error: String?)

    /**
     * 完整分析。永不抛错：致命问题进 fatalError，其余进 notices。
     *
     * 时间预算上的取舍（实测：中转/模型每次请求光"出第一个字"就要 1.5 秒上下，
     * 而且两条并发请求是真并发、不被排队），所以**减少串行等待**比压缩单次耗时更有效：
     *   · 判断结果按消息缓存：同一条消息再分析（主要是「换一批」）不再花那次往返；
     *   · 每条话术的候选一到就通过 onPartial 交给界面，不等其余话术、更不等排序。
     */
    suspend fun analyze(
        message: String,
        context: String?,
        onStage: ((PipelineStage) -> Unit)? = null,
        onPartial: ((Analysis) -> Unit)? = null,
    ): Analysis = coroutineScope {
        val start = System.currentTimeMillis()
        fun elapsed() = (System.currentTimeMillis() - start) / 1000.0

        var out = Analysis(message = message)
        val msg = message.trim()
        if (msg.isEmpty()) {
            return@coroutineScope out.copy(
                fatalError = "消息内容为空：请先长按消息点「复制」，或把要回的话输进输入框",
            )
        }
        if (!draft.isConfigured) {
            return@coroutineScope out.copy(
                fatalError = "还没配置生成层：打开 Jev 简版 App →「模型」页填 API Key",
            )
        }

        // 1) 判断层：起跑，但**不阻塞起草**。
        //
        // 为什么不串行等它：实测每次请求光"出第一个字"就要 1.5 秒上下，而喂给起草模型的那句
        // "意图"只是一个标签（真正决定候选风格的是话术指令）——A/B 实测盲起草与带意图起草的
        // 候选几乎没差别。所以让判断与起草重叠，意图的作用由「排序」这一层体现；万一判断给出的
        // 是高风险消息，再用意图重写一版候选替换（见下面的 refine）。
        //
        // 同一条消息的判断结果带缓存：换一批时连这一次都不用跑。
        var judgeResult: JudgeResult? =
            if (judge.isConfigured) JevJudgeCache.get(msg, context) else null
        var judgeDeferred: Deferred<JudgeResult?>? = null
        if (judge.isConfigured && judgeResult == null) {
            onStage?.invoke(PipelineStage.Judging)
            judgeDeferred = async {
                val r = runCatching { judge.judge(msg, context) }.getOrNull()
                if (r != null) JevJudgeCache.put(r, msg, context)
                r
            }
        }

        // 2) 起草：一个话术一次请求，并发；不等判断、也不等齐——每完成一个就先交给界面
        val tones = allTones(cfg.customTones)
        val active = cfg.activeSlots.mapNotNull { name -> tones[name]?.let { name to it } }
        if (active.isEmpty()) {
            return@coroutineScope out.copy(
                fatalError = "所有话术槽都是「不用」：打开 App →「话术」页至少启用一个",
                elapsed = elapsed(),
            )
        }
        val activeNames = active.map { it.first }

        fun partial(round: DraftRound, jr: JudgeResult?, extra: List<String> = emptyList()) = Analysis(
            message = out.message,
            judge = jr,
            candidates = ordered(activeNames, round.candidates),
            notices = out.notices + round.notices + extra,
            elapsed = elapsed(),
            rankingPending = true,
        )

        // 判断命中缓存时，第一轮就直接带意图（不用等，也没损失）
        val firstIntent = judgeResult?.intent

        onStage?.invoke(PipelineStage.Drafting(0, active.size))
        var round = draftRound(active, msg, firstIntent, context, onStage) { r ->
            onPartial?.invoke(partial(r, JevJudgeCache.get(msg, context)))
        }
        out = out.copy(notices = out.notices + round.notices)
        var drafted = round.candidates

        // 判断落地：给排序用。等它有时间上限，别让一个卡住的上游拖住整条链路。
        val jd = judgeDeferred
        if (jd != null && judgeResult == null) {
            judgeResult = withTimeoutOrNull(4_000) { jd.await() }
        }
        out = out.copy(judge = judgeResult)
        if (judge.isConfigured && judgeResult == null) {
            out = out.copy(notices = out.notices + "判断层没响应，已盲起草（不影响出候选）")
        }

        // 2b) 高风险消息才用意图重写一版：盲起草在平常用消息上够用，风险高的才值得多花一次往返。
        val jr = judgeResult
        if (jr != null && firstIntent == null && jr.risk >= REFINE_RISK_THRESHOLD && drafted.isNotEmpty()) {
            val note = "风险 ${jr.risk.roundToInt()}/9：已按判断重写一版候选"
            round = draftRound(active, msg, jr.intent, context, onStage) { r ->
                onPartial?.invoke(partial(r, jr, listOf(note)))
            }
            if (round.candidates.isNotEmpty()) {
                drafted = round.candidates
                out = out.copy(notices = out.notices + round.notices + note)
            }
        }

        if (drafted.isEmpty()) {
            return@coroutineScope out.copy(
                fatalError = out.notices.firstOrNull()
                    ?: "候选生成失败：请到 App「模型」页点「测试连接」检查配置",
                elapsed = elapsed(),
            )
        }

        // 3) 排序（可选）。失败按默认顺序：同话术的稳妥款在前。
        val orderedCandidates = ordered(activeNames, drafted)
        val jr2 = judgeResult
        if (judge.isConfigured && jr2 != null) {
            onStage?.invoke(PipelineStage.Ranking)
            val ranked = withTimeoutOrNull(10_000) {
                runCatching {
                    judge.rank(msg, jr2.intent, orderedCandidates.map { it.text })
                }.getOrNull()
            }
            if (ranked != null) {
                // 同一句话可能被两个话术各出一条，映射时保留首次出现的那个话术。
                val toneBy = HashMap<String, String>()
                for (c in orderedCandidates) toneBy.putIfAbsent(c.text, c.tone)
                val mapped = ranked.mapNotNull { r -> toneBy[r.text]?.let { Candidate(r.text, it, r.prob) } }
                out = if (mapped.isEmpty()) {
                    // 排序层回传的文本和候选对不上（模型改写了标点/空格）——宁可退回未排序的全量候选，
                    // 也不能让界面变成「判断头 + 一片空白」。
                    out.copy(
                        notices = out.notices + "排序结果和候选对不上，已按默认顺序展示",
                        candidates = orderedCandidates,
                    )
                } else {
                    out.copy(candidates = mapped)
                }
            } else {
                out = out.copy(
                    notices = out.notices + "排序失败，按默认顺序展示",
                    candidates = orderedCandidates,
                )
            }
        } else {
            out = out.copy(candidates = orderedCandidates)
        }

        onStage?.invoke(PipelineStage.Done)
        out.copy(elapsed = elapsed())
    }

    /** 按话术槽的顺序整理候选（每个槽内部保持模型给出的顺序：前稳后放） */
    private fun ordered(tones: List<String>, drafted: List<Candidate>): List<Candidate> =
        tones.flatMap { name -> drafted.filter { it.tone == name } }

    /**
     * 跑一轮起草：每个话术一次请求（并发），每完成一个就把"到目前为止的候选"交给界面。
     * 用 Channel 而不是 async+awaitAll，是为了拿到**完成顺序**——先出来的一条先展示。
     */
    private suspend fun draftRound(
        active: List<Pair<String, String>>,
        msg: String,
        intent: String?,
        context: String?,
        onStage: ((PipelineStage) -> Unit)?,
        onPartial: (DraftRound) -> Unit,
    ): DraftRound = coroutineScope {
        var round = DraftRound()
        val ch = Channel<DraftPiece>(active.size)
        for ((name, instruction) in active) {
            launch {
                val piece = try {
                    DraftPiece(name, draft.draft(msg, intent, context, name, instruction), null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DraftPiece(name, emptyList(), e.message ?: e.toString())
                }
                ch.send(piece)
            }
        }
        var done = 0
        repeat(active.size) {
            val piece = ch.receive()
            done++
            onStage?.invoke(PipelineStage.Drafting(done, active.size))
            if (piece.error != null) {
                round = round.copy(notices = round.notices + "「${piece.name}」失败：${piece.error}")
            }
            if (piece.texts.isNotEmpty()) {
                round = round.copy(
                    candidates = round.candidates + piece.texts.map { Candidate(it, piece.name, null) },
                )
            }
            // 先出一条是一条：不等其余话术、更不等排序
            if (round.candidates.isNotEmpty()) onPartial(round)
        }
        ch.close()
        round
    }

    companion object {
        /**
         * 风险达到这个分数才值得用意图重写候选（0-9 分制，6 起是"需要谨慎"那一档）。
         * 平常用消息不花这一次往返——实测盲起草与带意图起草的候选差别很小。
         */
        private const val REFINE_RISK_THRESHOLD = 6.0
    }
}
