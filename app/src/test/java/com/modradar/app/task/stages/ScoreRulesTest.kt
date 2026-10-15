package com.modradar.app.task.stages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 评分规则：权重、封顶与等级换算必须与计划 §5 一致。 */
class ScoreRulesTest {

    private fun extract(
        manifest: ManifestSummary? = ManifestSummary(),
        v1: Boolean = true,
        block: Boolean = true,
        subject: String? = null,
    ) = ExtractArtifact(
        manifest = manifest,
        v1Signed = v1,
        apkSigningBlock = block,
        signerSubject = subject,
    )

    @Test
    fun `干净应用满分且等级 LOW`() {
        val r = ScoreRules.evaluate(extract(), null)
        assertEquals(100, r.score)
        assertEquals("LOW", r.level)
        assertEquals(0, r.totalDeduction)
    }

    @Test
    fun `无签名扣 25`() {
        val r = ScoreRules.evaluate(extract(v1 = false, block = false), null)
        assertEquals(75, r.score)
        assertTrue(r.findings.any { it.ruleId == "SIG_NONE" && it.deduction == -25 })
    }

    @Test
    fun `debug 证书扣 10`() {
        val r = ScoreRules.evaluate(extract(subject = "CN=Android Debug, O=Android, C=US"), null)
        assertEquals(90, r.score)
        assertTrue(r.findings.any { it.ruleId == "SIG_DEBUG" })
    }

    @Test
    fun `仅 v1 签名扣 5`() {
        val r = ScoreRules.evaluate(extract(block = false), null)
        assertEquals(95, r.score)
        assertTrue(r.findings.any { it.ruleId == "SIG_V1ONLY" })
    }

    @Test
    fun `exported service 每个 15 且封顶 30`() {
        val m = ManifestSummary(
            exportedWithoutPermission = listOf("service:A", "service:B", "receiver:C", "service:D"),
        )
        val r = ScoreRules.evaluate(extract(manifest = m), null)
        val f = r.findings.first { it.ruleId == "COMP_SR" }
        assertEquals(-30, f.deduction)
    }

    @Test
    fun `exported activity 每个 8 且封顶 16`() {
        val m = ManifestSummary(
            exportedWithoutPermission = listOf("activity:A", "activity:B", "provider:C"),
        )
        val r = ScoreRules.evaluate(extract(manifest = m), null)
        val f = r.findings.first { it.ruleId == "COMP_AP" }
        assertEquals(-16, f.deduction)
    }

    @Test
    fun `危险权限组合扣 20`() {
        val m = ManifestSummary(
            permissions = listOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.BIND_ACCESSIBILITY_SERVICE",
            ),
        )
        val r = ScoreRules.evaluate(extract(manifest = m), null)
        assertTrue(r.findings.any { it.ruleId == "PERM_COMBO" && it.deduction == -20 })
    }

    @Test
    fun `单个敏感权限不触发组合规则`() {
        val m = ManifestSummary(permissions = listOf("android.permission.CAMERA"))
        val r = ScoreRules.evaluate(extract(manifest = m), null)
        assertTrue(r.findings.none { it.ruleId == "PERM_COMBO" })
        assertTrue(r.findings.any { it.ruleId == "PERM_SENSITIVE" && it.deduction == -3 })
    }

    @Test
    fun `敏感权限封顶 12`() {
        val m = ManifestSummary(
            permissions = listOf(
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.SEND_SMS",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.READ_CONTACTS",
            ),
        )
        val r = ScoreRules.evaluate(extract(manifest = m), null)
        val f = r.findings.first { it.ruleId == "PERM_SENSITIVE" }
        assertEquals(-12, f.deduction)
    }

    @Test
    fun `debuggable 与 allowBackup`() {
        val m = ManifestSummary(debuggable = true, allowBackup = true)
        val r = ScoreRules.evaluate(extract(manifest = m), null)
        assertEquals(87, r.score)
    }

    @Test
    fun `明文流量扣 10`() {
        val m = ManifestSummary(usesCleartextTraffic = true)
        val r = ScoreRules.evaluate(extract(manifest = m), null)
        assertTrue(r.findings.any { it.ruleId == "NET_CLEARTEXT" && it.deduction == -10 })
    }

    @Test
    fun `tracker SDK 每个 2 且封顶 10`() {
        val chain = ChainArtifact(trackerHits = listOf("a", "b", "c", "d", "e", "f", "g"))
        val r = ScoreRules.evaluate(extract(), chain)
        val f = r.findings.first { it.ruleId == "SDK" }
        assertEquals(-10, f.deduction)
    }

    @Test
    fun `反调试扣 10`() {
        val chain = ChainArtifact(antiDebugHits = listOf("Frida 检测"))
        val r = ScoreRules.evaluate(extract(), chain)
        assertTrue(r.findings.any { it.ruleId == "ANTIDEBUG" && it.deduction == -10 })
    }

    @Test
    fun `能力链 C1 扣 35 并计入总分`() {
        val chain = ChainArtifact(
            hits = listOf(
                ChainHit(
                    ruleId = "C1",
                    title = "同方法内 取数据 → 执行",
                    deduction = -35,
                    sources = listOf("OkHttp"),
                    sinks = listOf("DexClassLoader"),
                    sameMethod = true,
                ),
            ),
        )
        val r = ScoreRules.evaluate(extract(), chain)
        assertEquals(65, r.score)
        assertEquals("MEDIUM", r.level)
        assertTrue(r.findings.any { it.ruleId == "C1" && it.severity == "HIGH" })
    }

    @Test
    fun `多项叠加后总分被夹在 0`() {
        val m = ManifestSummary(
            permissions = listOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.BIND_ACCESSIBILITY_SERVICE",
            ),
            debuggable = true,
            usesCleartextTraffic = true,
        )
        val chain = ChainArtifact(
            hits = listOf(
                ChainHit("C1", "同方法执行", -35, sameMethod = true),
                ChainHit("C5", "解密后执行", -30),
                ChainHit("C6", "壳不可审计", -25),
            ),
        )
        val r = ScoreRules.evaluate(extract(v1 = false, block = false, manifest = m), chain)
        assertEquals(0, r.score)
        assertEquals("CRITICAL", r.level)
    }

    @Test
    fun `等级边界`() {
        assertEquals("LOW", ScoreRules.levelOf(80))
        assertEquals("MEDIUM", ScoreRules.levelOf(79))
        assertEquals("MEDIUM", ScoreRules.levelOf(60))
        assertEquals("HIGH", ScoreRules.levelOf(59))
        assertEquals("HIGH", ScoreRules.levelOf(40))
        assertEquals("CRITICAL", ScoreRules.levelOf(39))
    }

    @Test
    fun `缺少 S2 产物时给出说明且不崩`() {
        val r = ScoreRules.evaluate(null, null)
        assertEquals(100, r.score)
        assertTrue(r.notes.any { it.contains("缺少 S2 产物") })
    }

    @Test
    fun `同一能力链规则多处命中只扣一次`() {
        val chain = ChainArtifact(
            hits = listOf(
                ChainHit("C1", "同方法执行", -35, sources = listOf("URL"), sinks = listOf("exec")),
                ChainHit("C1", "同方法执行", -35, sources = listOf("Socket"), sinks = listOf("exec")),
                ChainHit("C1", "同方法执行", -35),
                ChainHit("C2", "调用链可达", -25),
            ),
        )
        val r = ScoreRules.evaluate(null, chain)
        val c1 = r.findings.single { it.ruleId == "C1" }
        assertEquals(-35, c1.deduction)
        assertTrue(c1.detail.contains("共 3 处"))
        assertTrue(c1.detail.contains("URL") && c1.detail.contains("Socket"))
        assertEquals(40, r.score)
    }

    private val hookChain = ChainArtifact(
        hits = listOf(ChainHit("C3", "存在执行/加载能力", -10, sinks = listOf("DexClassLoader"))),
        antiDebugHits = listOf("Xposed 检测", "Magisk 检测"),
    )

    @Test
    fun `Xposed 模块的加载能力与 Xposed 关键字只提示不扣分`() {
        val r = ScoreRules.evaluate(extract(), hookChain, "XPOSED_MODULE")
        assertEquals(100, r.score)
        assertTrue(r.findings.filter { it.ruleId in setOf("C3", "ANTIDEBUG") }.all { it.expected && it.deduction == 0 })
        assertTrue(r.notes.any { it.contains("模块上下文降噪") })
    }

    @Test
    fun `普通 APK 的同样特征照常扣分`() {
        val r = ScoreRules.evaluate(extract(), hookChain, "APK")
        assertEquals(80, r.score)
        assertTrue(r.findings.none { it.expected })
    }

    @Test
    fun `Xposed 模块 native hook 库不按 C8 扣分`() {
        val ex = extract().copy(nativeImports = listOf(NativeLibImports("lib/arm64-v8a/liblsplant.so", c8 = true)))
        assertEquals(100, ScoreRules.evaluate(ex, null, "XPOSED_MODULE").score)
        assertEquals(80, ScoreRules.evaluate(ex, null, "APK").score)
    }
}
