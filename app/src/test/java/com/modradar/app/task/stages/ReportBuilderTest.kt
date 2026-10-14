package com.modradar.app.task.stages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 报告聚合：AI 不计分、缺失阶段必须显式标注。 */
class ReportBuilderTest {

    private val score = ScoreArtifact(
        score = 62,
        level = "MEDIUM",
        totalDeduction = -38,
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
    fun `AI 结论不计入分数`() {
        val ai = AiReviewArtifact(status = "DONE", summary = "AI 说很危险")
        val r = ReportBuilder.build("t", "APK", null, null, null, score, ai, 1L)
        assertEquals(62, r.score)
        assertEquals(-38, r.totalDeduction)
        assertFalse(r.aiCountedInScore)
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
}
