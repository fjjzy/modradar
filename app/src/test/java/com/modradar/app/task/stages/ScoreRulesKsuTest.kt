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
                risks = listOf("service.sh: 下载后直接执行（管道）", "post-fs-data.sh: 关闭 SELinux"),
            ),
            null,
        )
        assertEquals(65, r.score)
    }

    @Test
    fun `改属性与挂载是模块本职只提示不扣分`() {
        val r = ScoreRules.evaluate(
            ex(
                mod = KsuModuleInfo("m", "M"),
                risks = listOf("post-fs-data.sh: 修改系统属性", "service.sh: bind 挂载系统路径", "service.sh: 后台常驻进程"),
            ),
            null,
            "KSU_MODULE",
        )
        assertEquals(100, r.score)
        assertTrue(r.findings.none { it.ruleId == "KSU_SCRIPT" })
        val ops = r.findings.single { it.ruleId == "KSU_OPS" }
        assertTrue(ops.expected)
        assertEquals(0, ops.deduction)
    }

    @Test
    fun `KSU 模块 zip 无签名只提示不扣分`() {
        val r = ScoreRules.evaluate(ExtractArtifact(ksuModule = KsuModuleInfo("m", "M")), null, "KSU_MODULE")
        assertEquals(100, r.score)
        val f = r.findings.single { it.ruleId == "SIG_NONE" }
        assertTrue(f.expected && f.deduction == 0)
    }

    @Test
    fun `APK 无签名仍扣 25`() {
        val r = ScoreRules.evaluate(ExtractArtifact(), null, "APK")
        assertTrue(r.findings.any { it.ruleId == "SIG_NONE" && it.deduction == -25 })
    }

    @Test
    fun `LSPatch 重打包扣 15 并列出内嵌模块`() {
        val r = ScoreRules.evaluate(
            ExtractArtifact(
                v1Signed = true, apkSigningBlock = true, signerSha256 = "bb",
                lspatch = LsPatchInfo(modules = listOf("com.example.mod.apk"), originSignerSha256 = "aa"),
            ),
            null, "APK",
        )
        val f = r.findings.single { it.ruleId == "LSPATCH" }
        assertEquals(-15, f.deduction)
        assertTrue(f.detail.contains("com.example.mod.apk") && f.detail.contains("签名已"))
    }
}
