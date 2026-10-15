package com.modradar.app.task.stages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告聚合（单一分数体系）：分数恒为 100 − Σ扣分；
 * AI 只能通过 误报退回 / 加重 / 补充发现 影响扣分，不输出独立数值分。
 */
class ReportBuilderTest {

    private val score = ScoreArtifact(
        score = 52,
        level = "MEDIUM",
        totalDeduction = -48,
        findings = listOf(
            ScoreFinding("C1", "同方法执行", "HIGH", -35, "d"),
            ScoreFinding("DBG", "debuggable", "MEDIUM", -10, "d"),
            ScoreFinding("BACKUP", "allowBackup", "LOW", -3, "d"),
        ),
    )

    @Test
    fun `按严重度统计数量`() {
        val r = ReportBuilder.build("t", "APK", null, null, null, score, null, 1L)
        assertEquals(1, r.highCount)
        assertEquals(1, r.mediumCount)
        assertEquals(1, r.lowCount)
    }

    @Test
    fun `无 AI 时分数恒等于本地基线`() {
        val r = ReportBuilder.build("t", "APK", null, null, null, score, null, 1L)
        assertEquals(52, r.score)
        assertEquals("HIGH", r.level)
        assertEquals(-48, r.totalDeduction)
        assertEquals(0, r.aiAdjustment)
    }

    @Test
    fun `未配置 AI 时明确标注未完成研判`() {
        val ai = AiReviewArtifact(status = "NOT_CONFIGURED")
        val r = ReportBuilder.build("t", "APK", null, null, null, score, ai, 1L)
        assertTrue(r.notes.any { it.contains("未完成 AI 研判") })
        assertNull(r.aiSummary)
    }

    @Test
    fun `AI 调用失败时标注失败原因`() {
        val ai = AiReviewArtifact(status = "FAILED", error = "HTTP 401")
        val r = ReportBuilder.build("t", "APK", null, null, null, score, ai, 1L)
        assertTrue(r.notes.any { it.contains("HTTP 401") })
    }

    @Test
    fun `缺少评分产物时说明且不崩`() {
        val r = ReportBuilder.build("t", "APK", null, null, null, null, null, 1L)
        assertEquals(100, r.score)
        assertTrue(r.notes.any { it.contains("缺少 S4 评分产物") })
    }

    @Test
    fun `存在壳特征时提示静态覆盖有限`() {
        val ex = ExtractArtifact(shellHints = listOf("lib/arm64-v8a/libjiagu.so"))
        val r = ReportBuilder.build("t", "APK", null, ex, null, score, null, 1L)
        assertTrue(r.notes.any { it.contains("加固") })
    }

    @Test
    fun `非 zip 目标提示深度分析受限`() {
        val load = LoadArtifact("x.bin", 10, "sha", "UNKNOWN", isZip = false, entryCount = 0, hasManifest = false, hasModuleProp = false)
        val r = ReportBuilder.build("t", "UNKNOWN", load, null, null, score, null, 1L)
        assertTrue(r.notes.any { it.contains("不是可解析的 zip") })
    }

    @Test
    fun `能力链命中进入报告`() {
        val chain = ChainArtifact(hits = listOf(ChainHit("C2", "调用链可达", -25, reachable = true)))
        val r = ReportBuilder.build("t", "APK", null, null, chain, score, null, 1L)
        assertEquals(1, r.chains.size)
        assertEquals("C2", r.chains.first().ruleId)
    }

    @Test
    fun `notes 去重`() {
        val s = score.copy(notes = listOf("重复项", "重复项"))
        val r = ReportBuilder.build("t", "APK", null, null, null, s, null, 1L)
        assertEquals(r.notes.size, r.notes.distinct().size)
    }

    // ---- AI 修正通道 ----

    private fun done(v: AiVerdict) = AiReviewArtifact(status = "DONE", verdict = v)

    @Test
    fun `AI 判误报的规则退回扣分`() {
        val v = AiVerdict(
            headline = "h",
            ruleReview = listOf(AiRuleReview("DBG", "误报", reason = "调试包"), AiRuleReview("C1", "成立", "")),
        )
        val r = ReportBuilder.build("t", "APK", null, null, null, score, done(v), 1L)
        val dbg = r.findings.single { it.ruleId == "DBG" }
        assertTrue(dbg.aiDismissed)
        assertEquals(0, dbg.deduction)
        assertEquals(-10, dbg.originalDeduction)
        // 52 + 10 = 62：单一分数，误报退回直接体现
        assertEquals(62, r.score)
        assertEquals(-38, r.totalDeduction)
        assertEquals(10, r.aiAdjustment)
        assertEquals(0, r.mediumCount)
        assertEquals(-35, r.findings.single { it.ruleId == "C1" }.deduction)
    }

    @Test
    fun `AI 加重按原扣分 50 追加且单条封顶 15`() {
        val v = AiVerdict(headline = "h", ruleReview = listOf(AiRuleReview("DBG", "加重", "")))
        val r = ReportBuilder.build("t", "APK", null, null, null, score, done(v), 1L)
        val dbg = r.findings.single { it.ruleId == "DBG" }
        assertTrue(dbg.aiAggravated)
        assertEquals(-5, dbg.aiAdjustment) // −10 / 2
        assertEquals(-15, dbg.deduction)   // 原 −10 + 追加 −5
        assertEquals(47, r.score)          // 52 − 5

        // 原扣分 −35 的一半是 −17.5 → 整除 −17，封顶 −15
        val v2 = AiVerdict(headline = "h", ruleReview = listOf(AiRuleReview("C1", "加重", "")))
        val r2 = ReportBuilder.build("t", "APK", null, null, null, score, done(v2), 1L)
        val c1 = r2.findings.single { it.ruleId == "C1" }
        assertEquals(-15, c1.aiAdjustment)
        assertEquals(-50, c1.deduction)
    }

    @Test
    fun `AI 加重可指定严重度档位`() {
        val v = AiVerdict(headline = "h", ruleReview = listOf(AiRuleReview("BACKUP", "加重", "HIGH", "")))
        val r = ReportBuilder.build("t", "APK", null, null, null, score, done(v), 1L)
        val backup = r.findings.single { it.ruleId == "BACKUP" }
        assertEquals(-20, backup.aiAdjustment)
        assertEquals(-23, backup.deduction)
    }

    @Test
    fun `规则外已确认发现按严重度扣分`() {
        val v = AiVerdict(
            headline = "h",
            findings = listOf(
                AiFinding(title = "密钥硬编码", severity = "HIGH", status = "已确认", evidence = "libx.so 0x1F", impact = "可伪造升级通道"),
                AiFinding(title = "猜测项", severity = "HIGH", status = "未验证", evidence = "", impact = "不计分"),
            ),
        )
        val r = ReportBuilder.build("t", "APK", null, null, null, score, done(v), 1L)
        val supp = r.findings.single { it.ruleId.startsWith("AI_SUPP") }
        assertEquals(-20, supp.deduction)
        assertTrue(supp.evidence.isNotEmpty())
        // 未确认的不扣分也不进扣分列表
        assertEquals(1, r.findings.count { it.ruleId.startsWith("AI_SUPP") })
        assertEquals(32, r.score) // 52 − 20
    }

    @Test
    fun `补充发现同档最多 2 条且总封顶 40`() {
        val findings = (1..3).map { AiFinding(title = "发现$it", severity = "HIGH", status = "已确认", evidence = "e$it") } +
            listOf(AiFinding(title = "中危1", severity = "MEDIUM", status = "已确认", evidence = "e4"))
        val v = AiVerdict(headline = "h", findings = findings)
        val r = ReportBuilder.build("t", "APK", null, null, null, score, done(v), 1L)
        val supps = r.findings.filter { it.ruleId.startsWith("AI_SUPP") }
        assertEquals(4, supps.size)
        // HIGH 前 2 条扣分（−40 已达总封顶），第 3 条与 MEDIUM 均仅提示
        assertEquals(-40, supps.sumOf { it.deduction })
        assertEquals(2, supps.count { it.deduction != 0 })
        assertEquals(12, r.score) // 52 − 40
    }

    @Test
    fun `AI 净调整量汇总三条通道`() {
        val v = AiVerdict(
            headline = "h",
            ruleReview = listOf(AiRuleReview("DBG", "误报", ""), AiRuleReview("BACKUP", "加重", "")),
            findings = listOf(AiFinding(title = "新发现", severity = "MEDIUM", status = "已确认", evidence = "e")),
        )
        val r = ReportBuilder.build("t", "APK", null, null, null, score, done(v), 1L)
        // +10（误报退回） −1.5→ −1（BACKUP 加重，−3/2 整除） −10（补充） = −1
        assertEquals(10 - 1 - 10, r.aiAdjustment)
        assertEquals(51, r.score) // 52 − 1
        assertTrue(r.notes.any { it.contains("AI 复核") })
    }
}
