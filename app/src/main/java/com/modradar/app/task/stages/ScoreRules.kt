package com.modradar.app.task.stages

/**
 * 评分规则（纯函数，便于单测）。
 *
 * 总分 = clamp(100 − Σ扣分, 0, 100)，越高越安全。
 * AI 结论不计入本分数。
 */
object ScoreRules {

    fun levelOf(score: Int): String = when {
        score >= 80 -> "LOW"
        score >= 60 -> "MEDIUM"
        score >= 40 -> "HIGH"
        else -> "CRITICAL"
    }

    fun evaluate(extract: ExtractArtifact?, chain: ChainArtifact?): ScoreArtifact {
        val findings = mutableListOf<ScoreFinding>()
        val notes = mutableListOf<String>()

        if (extract == null) notes += "缺少 S2 产物，结构类规则未参与评分"

        extract?.let { ex ->
            scoreSignature(ex, findings)
            scoreComponents(ex, findings)
            scorePermissions(ex, findings)
            scoreNetwork(ex, chain, findings)
            scoreDebugAndBackup(ex, findings)
            scoreTargetSdk(ex, findings)
            scoreKsuModule(ex, findings)
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
            if (ch.antiDebugHits.isNotEmpty()) {
                findings += ScoreFinding(
                    ruleId = "ANTIDEBUG",
                    title = "存在反调试/反注入特征",
                    severity = "MEDIUM",
                    deduction = -10,
                    detail = "命中：${ch.antiDebugHits.joinToString("、")}",
                    evidence = ch.antiDebugHits,
                )
            }
            for (h in ch.hits) {
                findings += ScoreFinding(
                    ruleId = h.ruleId,
                    title = h.title,
                    severity = when {
                        h.deduction <= -30 -> "HIGH"
                        h.deduction <= -15 -> "MEDIUM"
                        else -> "LOW"
                    },
                    deduction = h.deduction,
                    detail = buildString {
                        if (h.sources.isNotEmpty()) append("SOURCE: ${h.sources.joinToString(", ")}；")
                        if (h.transforms.isNotEmpty()) append("TRANSFORM: ${h.transforms.joinToString(", ")}；")
                        if (h.sinks.isNotEmpty()) append("SINK: ${h.sinks.joinToString(", ")}")
                    }.ifBlank { "见证据链" },
                    evidence = h.evidence.map { "${it.detail} @ ${it.location}" },
                )
            }
            if (ch.truncated) notes += "S3 扫描量超上限已截断，评分偏保守"
        }

        val totalDeduction = findings.sumOf { it.deduction }
        val score = (100 + totalDeduction).coerceIn(0, 100)

        notes += "权限↔API 一致性检查尚未实现，未参与评分"
        notes += "AI 结论不计入本分数"

        return ScoreArtifact(
            score = score,
            level = levelOf(score),
            totalDeduction = totalDeduction,
            findings = findings,
            notes = notes,
        )
    }

    // ---- 结构规则 ----

    private fun scoreSignature(ex: ExtractArtifact, out: MutableList<ScoreFinding>) {
        val signed = ex.v1Signed || ex.apkSigningBlock
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
        val cleartext = chain?.cleartextHttpCount ?: 0
        if (cleartext > 0) {
            out += ScoreFinding(
                ruleId = "NET_HTTP",
                title = "DEX 中存在 http:// 明文链接",
                severity = "LOW",
                deduction = -3,
                detail = "共 $cleartext 条",
                evidence = chain?.httpUrls?.filter { it.startsWith("http://") }?.take(5).orEmpty(),
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

    /** KSU / Magisk 模块规则：危险脚本 −30，module.prop 缺必备字段 −5。 */
    private fun scoreKsuModule(ex: ExtractArtifact, out: MutableList<ScoreFinding>) {
        val mod = ex.ksuModule ?: return

        if (ex.ksuScriptRisks.isNotEmpty()) {
            out += ScoreFinding(
                ruleId = "KSU_SCRIPT",
                title = "模块脚本含危险操作",
                severity = "HIGH",
                deduction = -30,
                detail = "共 ${ex.ksuScriptRisks.size} 处：${ex.ksuScriptRisks.joinToString("；")}",
                evidence = ex.ksuScriptRisks.take(10),
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

    private val SENSITIVE_PERMISSIONS = listOf(
        "READ_SMS", "RECEIVE_SMS", "SEND_SMS",
        "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        "RECORD_AUDIO", "READ_CONTACTS", "WRITE_CONTACTS",
        "CAMERA", "READ_PHONE_STATE",
    )
}
