package com.modradar.app.task.stages

import com.modradar.app.scan.ksu.KsuModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** KSU 模块评分规则。 */
class ScoreRulesKsuTest {

    private fun ex(
        mod: KsuModuleInfo? = null,
        risks: List<String> = emptyList(),
    ) = ExtractArtifact(
        v1Signed = true,
        apkSigningBlock = true,
        ksuModule = mod,
        ksuScriptRisks = risks,
    )

    @Test
    fun `危险脚本扣 30`() {
        val r = ScoreRules.evaluate(
            ex(mod = KsuModuleInfo("m", "M"), risks = listOf("service.sh: 下载后直接执行（管道）")),
            null,
        )
        assertEquals(70, r.score)
        assertTrue(r.findings.any { it.ruleId == "KSU_SCRIPT" && it.deduction == -30 })
    }

    @Test
    fun `module_prop 缺字段扣 5`() {
        val r = ScoreRules.evaluate(
            ex(mod = KsuModuleInfo("m", "M", missingFields = listOf("author", "description"))),
            null,
        )
        assertEquals(95, r.score)
        assertTrue(r.findings.any { it.ruleId == "KSU_PROP" && it.deduction == -5 })
    }

    @Test
    fun `干净模块不扣分`() {
        val r = ScoreRules.evaluate(ex(mod = KsuModuleInfo("m", "M")), null)
        assertEquals(100, r.score)
        assertTrue(r.findings.none { it.ruleId.startsWith("KSU") })
    }

    @Test
    fun `非模块目标不触发 KSU 规则`() {
        val r = ScoreRules.evaluate(ExtractArtifact(v1Signed = true, apkSigningBlock = true), null)
        assertTrue(r.findings.none { it.ruleId.startsWith("KSU") })
    }

    @Test
    fun `危险脚本与缺字段可叠加`() {
        val r = ScoreRules.evaluate(
            ex(
                mod = KsuModuleInfo("m", "M", missingFields = listOf("versionCode")),
                risks = listOf("post-fs-data.sh: 修改系统属性", "service.sh: bind 挂载系统路径"),
            ),
            null,
        )
        assertEquals(65, r.score)
    }
}
