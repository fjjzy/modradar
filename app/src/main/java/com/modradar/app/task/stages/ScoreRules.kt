package com.modradar.app.task.stages

import com.modradar.app.scan.ksu.ScriptRisk

/**
 * 评分规则（纯函数，便于单测）。
 *
 * 总分 = clamp(100 − Σ扣分, 0, 100)，越高越安全。
 * 这是「本地规则基线」：AI 研判完成后报告以 AI 综合分为主，本分数作对照。
 *
 * 上下文降噪：Xposed / KSU 模块的本职就是 Hook、改系统，
 * 对它们来说动态加载、反调试关键字、native 改内存、改属性挂载等属于预期行为，
 * 记为 expected（不扣分，只提示）。
 */
object ScoreRules {

    fun levelOf(score: Int): String = when {
        score >= 80 -> "LOW"
        score >= 60 -> "MEDIUM"
        score >= 40 -> "HIGH"
        else -> "CRITICAL"
    }

    fun evaluate(extract: ExtractArtifact?, chain: ChainArtifact?, kind: String? = null): ScoreArtifact {
        val findings = mutableListOf<ScoreFinding>()
        val isXposed = kind == "XPOSED_MODULE" || extract?.isXposedModule == true
        val isKsu = kind == "KSU_MODULE" || extract?.ksuModule != null
        val isModule = isXposed || isKsu
        val notes = mutableListOf<String>()

        if (extract == null) notes += "缺少 S2 产物，结构类规则未参与评分"

        extract?.let { ex ->
            scoreSignature(ex, findings, isKsu)
            scoreLsPatch(ex, findings)
            scoreComponents(ex, findings)
            scorePermissions(ex, findings)
            scoreNetwork(ex, chain, findings)
            scoreDebugAndBackup(ex, findings)
            scoreTargetSdk(ex, findings)
            scoreKsuModule(ex, findings)
            scoreNative(ex, findings, isXposed)
        }

        chain?.let { ch ->
            if (ch.trackerHits.isNotEmpty()) {
                findings += ScoreFinding(
                    ruleId = "SDK",
                    title = "命中统计/广告 SDK",
                    severity = "MEDIUM",
                    deduction = -(ch.trackerHits.size * 2).coerceAtMost(10),
                    detail = "命中 ${ch.trackerHits.size} 类：${ch.trackerHits.joinToString("、")}",
                    evidence = ch.trackerHits,
                )
            }
        }

        // 反调试：DEX 字符串特征 + native ptrace 等导入，合并为一条（-10）
        val antiDebug = chain?.antiDebugHits.orEmpty() + extract?.nativeImports.orEmpty()
            .filter { it.antiDebug.isNotEmpty() }
            .map { "${it.path} 导入 ${it.antiDebug.joinToString(", ")}" }
        if (antiDebug.isNotEmpty()) {
            findings += if (isModule) ScoreFinding(
                ruleId = "ANTIDEBUG",
                title = "引用 Xposed / Magisk / 调试检测相关关键字",
                severity = "INFO",
                deduction = 0,
                detail = "模块本身运行在 Hook / Root 环境中，出现这些关键字属于正常（共 ${antiDebug.size} 处）",
                evidence = antiDebug.take(10),
                expected = true,
            ) else ScoreFinding(
                ruleId = "ANTIDEBUG",
                title = "存在反调试/反注入特征",
                severity = "MEDIUM",
                deduction = -10,
                detail = "命中 ${antiDebug.size} 处",
                evidence = antiDebug.take(10),
            )
        }

        chain?.let { ch ->
            // 同一规则多处命中只扣一次（取最重的一条），证据合并；否则大包会被重复扣到 0 分
            for ((_, group) in ch.hits.groupBy { it.ruleId }) {
                val h = group.minBy { it.deduction }
                // 模块里「有加载/执行能力但无远端来源」是 Hook 框架的常态
                val expected = isModule && h.ruleId in MODULE_EXPECTED_CHAINS
                findings += ScoreFinding(
                    ruleId = h.ruleId,
                    title = h.title,
                    severity = when {
                        expected -> "INFO"
                        h.deduction <= -30 -> "HIGH"
                        h.deduction <= -15 -> "MEDIUM"
                        else -> "LOW"
                    },
                    deduction = if (expected) 0 else h.deduction,
                    expected = expected,
                    detail = buildString {
                        if (expected) append("模块加载/执行代码属于 Hook 框架常态，不扣分；")
                        if (group.size > 1) append("共 ${group.size} 处命中，只计一次；")
                        val src = group.flatMap { it.sources }.distinct()
                        val tr = group.flatMap { it.transforms }.distinct()
                        val sk = group.flatMap { it.sinks }.distinct()
                        if (src.isNotEmpty()) append("SOURCE: ${src.joinToString(", ")}；")
                        if (tr.isNotEmpty()) append("TRANSFORM: ${tr.joinToString(", ")}；")
                        if (sk.isNotEmpty()) append("SINK: ${sk.joinToString(", ")}")
                    }.ifBlank { "见证据链" },
                    evidence = group.flatMap { g -> g.evidence.map { "${it.detail} @ ${it.location}" } }.distinct().take(20),
                )
            }
            if (ch.truncated) notes += "S3 扫描量超上限已截断，评分偏保守"
        }

        val totalDeduction = findings.sumOf { it.deduction }
        val score = (100 + totalDeduction).coerceIn(0, 100)

        notes += "权限↔API 一致性检查尚未实现，未参与评分"
        if (isModule) notes += "已按${if (isXposed) " Xposed " else " KSU "}模块上下文降噪：Hook / 改系统等预期行为只提示不扣分"

        return ScoreArtifact(
            score = score,
            level = levelOf(score),
            totalDeduction = totalDeduction,
            findings = findings,
            notes = notes,
        )
    }

    // ---- 结构规则 ----

    private fun scoreSignature(ex: ExtractArtifact, out: MutableList<ScoreFinding>, isKsu: Boolean) {
        val signed = ex.v1Signed || ex.apkSigningBlock
        // KSU / Magisk 模块 zip 本身没有签名机制，无签名是常态；来源可信度交给联网来源核验
        if (!signed && isKsu) {
            out += ScoreFinding(
                ruleId = "SIG_NONE",
                title = "模块 zip 无签名（KSU / Magisk 常态）",
                severity = "INFO",
                deduction = 0,
                detail = "模块格式不支持签名，无法据此确认作者；请参考「来源核验」是否与官方 release 一致",
                expected = true,
            )
            return
        }
        if (!signed) {
            out += ScoreFinding(
                ruleId = "SIG_NONE",
                title = "无签名或签名无法验证",
                severity = "HIGH",
                deduction = -25,
                detail = "未发现 v1 签名文件，也未发现 APK Signing Block（v2/v3）",
            )
            return
        }
        val subject = ex.signerSubject.orEmpty()
        if (subject.contains("Android Debug", ignoreCase = true)) {
            out += ScoreFinding(
                ruleId = "SIG_DEBUG",
                title = "使用 debug 证书签名",
                severity = "MEDIUM",
                deduction = -10,
                detail = "证书主体：$subject",
                evidence = listOfNotNull(ex.signerSha256?.let { "SHA256: $it" }),
            )
        }
        if (ex.v1Signed && !ex.apkSigningBlock) {
            out += ScoreFinding(
                ruleId = "SIG_V1ONLY",
                title = "仅 v1 签名",
                severity = "LOW",
                deduction = -5,
                detail = "缺少 v2/v3 签名，完整性保护较弱",
            )
        }
    }

    private fun scoreLsPatch(ex: ExtractArtifact, out: MutableList<ScoreFinding>) {
        val lp = ex.lspatch ?: return
        val replaced = lp.originSignerSha256 != null && ex.signerSha256 != null && lp.originSignerSha256 != ex.signerSha256
        out += ScoreFinding(
            ruleId = "LSPATCH",
            title = "经 LSPatch 重打包（非原版）",
            severity = "MEDIUM",
            deduction = -15,
            detail = buildString {
                append("包内含 LSPatch 加载器")
                if (lp.modules.isNotEmpty()) append("，内嵌 ${lp.modules.size} 个 Xposed 模块：${lp.modules.take(5).joinToString("、")}")
                if (replaced) append("；签名已由原作者证书替换为重打包者证书")
                append("。内嵌模块拥有该应用的全部权限与数据")
            },
            evidence = lp.evidence + listOfNotNull(lp.originSignerSha256?.let { "原包证书 SHA256: $it" }),
        )
    }

    private fun scoreComponents(ex: ExtractArtifact, out: MutableList<ScoreFinding>) {
        val exposed = ex.manifest?.exportedWithoutPermission.orEmpty()
        if (exposed.isEmpty()) return

        val (sr, rest) = exposed.partition {
            it.startsWith("service:") || it.startsWith("receiver:")
        }
        if (sr.isNotEmpty()) {
            out += ScoreFinding(
                ruleId = "COMP_SR",
                title = "exported 的 service/receiver 无权限保护",
                severity = "HIGH",
                deduction = -(sr.size * 15).coerceAtMost(30),
                detail = "共 ${sr.size} 个，扣分封顶 30",
                evidence = sr.take(10),
            )
        }
        if (rest.isNotEmpty()) {
            out += ScoreFinding(
                ruleId = "COMP_AP",
                title = "exported 的 activity/provider 可被外部调用",
                severity = "MEDIUM",
                deduction = -(rest.size * 8).coerceAtMost(16),
                detail = "共 ${rest.size} 个，扣分封顶 16",
                evidence = rest.take(10),
            )
        }
    }

    private fun scorePermissions(ex: ExtractArtifact, out: MutableList<ScoreFinding>) {
        val perms = ex.manifest?.permissions.orEmpty().toSet()

        val trojanCombo = listOf(
            "android.permission.SYSTEM_ALERT_WINDOW" in perms,
            "android.permission.BIND_ACCESSIBILITY_SERVICE" in perms,
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" in perms,
        ).count { it } >= 2

        val dropperCombo = "android.permission.REQUEST_INSTALL_PACKAGES" in perms &&
            "android.permission.INTERNET" in perms &&
            "android.permission.BIND_DEVICE_ADMIN" in perms

        if (trojanCombo || dropperCombo) {
            out += ScoreFinding(
                ruleId = "PERM_COMBO",
                title = "危险权限组合",
                severity = "HIGH",
                deduction = -20,
                detail = buildList {
                    if (trojanCombo) add("悬浮窗 + 无障碍/通知监听（典型木马组合）")
                    if (dropperCombo) add("安装应用 + 网络 + 设备管理（典型投放器组合）")
                }.joinToString("；"),
                evidence = perms.filter {
                    it.contains("ALERT_WINDOW") || it.contains("ACCESSIBILITY") ||
                        it.contains("NOTIFICATION_LISTENER") || it.contains("INSTALL_PACKAGES") ||
                        it.contains("DEVICE_ADMIN")
                },
            )
        }

        val sensitive = perms.filter { p -> SENSITIVE_PERMISSIONS.any { p.endsWith(it) } }
        if (sensitive.isNotEmpty()) {
            out += ScoreFinding(
                ruleId = "PERM_SENSITIVE",
                title = "声明敏感权限",
                severity = "LOW",
                deduction = -(sensitive.size * 3).coerceAtMost(12),
                detail = "共 ${sensitive.size} 个，扣分封顶 12",
                evidence = sensitive,
            )
        }
    }

    private fun scoreNetwork(
        ex: ExtractArtifact,
        chain: ChainArtifact?,
        out: MutableList<ScoreFinding>,
    ) {
        if (ex.manifest?.usesCleartextTraffic == true) {
            out += ScoreFinding(
                ruleId = "NET_CLEARTEXT",
                title = "允许明文流量",
                severity = "MEDIUM",
                deduction = -10,
                detail = "AndroidManifest 中 usesCleartextTraffic=true",
            )
        }
        // 旧产物可能含命名空间、空 URL 等噪声，评分时再过滤一遍
        val urls = chain?.httpUrls.orEmpty().filter { it.startsWith("http://") }
        val real = urls.count { StringFacts.isMeaningfulUrl(it) }
        val cleartext = if (real == urls.size) chain?.cleartextHttpCount ?: 0 else real
        if (cleartext > 0) {
            out += ScoreFinding(
                ruleId = "NET_HTTP",
                title = "DEX 中存在 http:// 明文链接",
                severity = "LOW",
                deduction = -3,
                detail = "共 $cleartext 条",
                evidence = chain?.httpUrls?.filter { it.startsWith("http://") && StringFacts.isMeaningfulUrl(it) }?.take(5).orEmpty(),
            )
        }
        val domains = chain?.trackerDomainHits.orEmpty()
        if (domains.isNotEmpty()) {
            out += ScoreFinding(
                ruleId = "NET_TRACKER",
                title = "直连已知 tracker 域名",
                severity = "LOW",
                deduction = -5,
                detail = "命中：${domains.joinToString("、")}",
                evidence = domains,
            )
        }
    }

    private fun scoreDebugAndBackup(ex: ExtractArtifact, out: MutableList<ScoreFinding>) {
        if (ex.manifest?.debuggable == true) {
            out += ScoreFinding(
                ruleId = "DBG",
                title = "debuggable=true",
                severity = "MEDIUM",
                deduction = -10,
                detail = "发布包不应开启调试标志，可被任意调试与注入",
            )
        }
        if (ex.manifest?.allowBackup == true) {
            out += ScoreFinding(
                ruleId = "BACKUP",
                title = "allowBackup=true",
                severity = "LOW",
                deduction = -3,
                detail = "应用数据可被 adb backup 导出",
            )
        }
    }

    private fun scoreTargetSdk(ex: ExtractArtifact, out: MutableList<ScoreFinding>) {
        val t = ex.manifest?.targetSdk ?: return
        if (t < 26) {
            out += ScoreFinding(
                ruleId = "SDK_OLD",
                title = "targetSdk 过低",
                severity = "LOW",
                deduction = -3,
                detail = "targetSdk=$t（< 26），无法享受新版本安全限制",
            )
        }
    }

    /** KSU / Magisk 模块规则：危险脚本 −30（改属性/挂载等日常操作只提示），module.prop 缺必备字段 −5。 */
    private fun scoreKsuModule(ex: ExtractArtifact, out: MutableList<ScoreFinding>) {
        val mod = ex.ksuModule ?: return

        val (normal, danger) = ex.ksuScriptRisks.partition { ScriptRisk.isNormal(it) }
        if (danger.isNotEmpty()) {
            out += ScoreFinding(
                ruleId = "KSU_SCRIPT",
                title = "模块脚本含危险操作",
                severity = "HIGH",
                deduction = -30,
                detail = "共 ${danger.size} 处：${danger.joinToString("；")}",
                evidence = danger.take(10),
            )
        }
        if (normal.isNotEmpty()) {
            out += ScoreFinding(
                ruleId = "KSU_OPS",
                title = "模块脚本的常规系统操作",
                severity = "INFO",
                deduction = 0,
                detail = "改属性、bind 挂载、后台常驻等是模块的本职工作，共 ${normal.size} 处",
                evidence = normal.take(10),
                expected = true,
            )
        }

        if (mod.missingFields.isNotEmpty()) {
            out += ScoreFinding(
                ruleId = "KSU_PROP",
                title = "module.prop 缺少必备字段",
                severity = "LOW",
                deduction = -5,
                detail = "缺失：${mod.missingFields.joinToString("、")}",
                evidence = mod.dir?.let { listOf("模块目录：$it") }.orEmpty(),
            )
        }
    }

    /** C8：native 同时导入动态加载 + mprotect + 执行/ptrace + 网络符号（−20，只计一次）。 */
    private fun scoreNative(ex: ExtractArtifact, out: MutableList<ScoreFinding>, isXposed: Boolean) {
        val hits = ex.nativeImports.filter { it.c8 }
        if (hits.isEmpty()) return
        // Xposed 模块常带 native Hook 库（LSPlant / Dobby 等），dlopen + mprotect 是 inline hook 的必需品
        out += ScoreFinding(
            ruleId = "C8",
            title = "native 层具备动态加载 + 改内存权限 + 执行 + 联网能力",
            severity = if (isXposed) "INFO" else "MEDIUM",
            deduction = if (isXposed) 0 else -20,
            expected = isXposed,
            detail = "共 ${hits.size} 个库同时导入 dlopen / mprotect / exec 或 ptrace / 网络符号",
            evidence = hits.take(8).map { "${it.path} 导入 ${it.interesting.joinToString(", ")}" },
        )
    }

    /** 模块场景下只提示不扣分的能力链规则。 */
    private val MODULE_EXPECTED_CHAINS = setOf("C3", "C4")

    private val SENSITIVE_PERMISSIONS = listOf(
        "READ_SMS", "RECEIVE_SMS", "SEND_SMS",
        "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        "RECORD_AUDIO", "READ_CONTACTS", "WRITE_CONTACTS",
        "CAMERA", "READ_PHONE_STATE",
    )
}
