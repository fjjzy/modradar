package com.modradar.app.task.stages

/**
 * S6 报告聚合（纯函数，可单测）。
 *
 * 全 App 只有**一套**分数：`score = 100 − Σ扣分（AI 复核修正后）`，等级恒由分数换算。
 * AI 不输出数值分，只能通过三条通道影响扣分：
 * 1. 误报（rule_review.judgement="误报"）→ 该规则扣分清零；
 * 2. 加重（judgement="加重"）→ 在原扣分上追加，幅度 ≤ 原扣分 50%，单条封顶 −15（可附 severity 指定档位）；
 * 3. 补充（findings 中 status=已确认 且有 evidence 的规则外发现）→ 按严重度固定档位
 *    HIGH −20 / MEDIUM −10 / LOW −3，同档最多 2 条，总封顶 −40。
 * AI 缺失或未研判时，分数恒等于本地规则基线。
 */
object ReportBuilder {

    /** 补充发现：按严重度的固定扣分档位。 */
    internal val SUPPLEMENT_DEDUCTION = mapOf("HIGH" to -20, "MEDIUM" to -10, "LOW" to -3)
    private const val SUPPLEMENT_TOTAL_CAP = -40
    private const val AGGRAVATE_PER_RULE_CAP = -15

    fun build(
        targetName: String,
        targetKind: String,
        load: LoadArtifact?,
        extract: ExtractArtifact?,
        chain: ChainArtifact?,
        score: ScoreArtifact?,
        ai: AiReviewArtifact?,
        generatedAt: Long,
        provenance: ProvenanceArtifact? = null,
    ): ReportArtifact {
        val raw = score?.findings.orEmpty() + provenanceFindings(provenance)
        val notes = mutableListOf<String>()

        notes += score?.notes.orEmpty()

        if (score == null) notes += "缺少 S4 评分产物，报告不含规则评分"
        if (chain == null) notes += "缺少 S3 能力链产物，报告不含能力链结论"
        if (extract?.shellHints?.isNotEmpty() == true) {
            notes += "包内存在加固/壳特征，静态分析覆盖有限"
        }
        if (load != null && !load.isZip) {
            notes += "目标不是可解析的 zip 容器，深度分析受限"
        }

        val aiStatus = ai?.status ?: AiReviewResultStatus.NOT_CONFIGURED
        when (aiStatus) {
            AiReviewResultStatus.NOT_CONFIGURED ->
                notes += "未完成 AI 研判：尚未配置 AI 接口，本报告仅含本地规则结论"
            AiReviewResultStatus.FAILED ->
                notes += "未完成 AI 研判：AI 调用失败（${ai?.error ?: "未知原因"}）"
        }

        val verdict = if (aiStatus == AiReviewResultStatus.DONE) ai?.verdict ?: AiVerdictParser.parse(ai?.summary) else null
        val reviews = verdict?.ruleReview.orEmpty()

        // ---- AI 修正：误报清零（保留原扣分）----
        val dismissedIds = reviews.filter { it.judgement == "误报" }.map { it.ruleId }.toSet()
        val findings = raw.map { f ->
            when {
                f.ruleId in dismissedIds && f.deduction != 0 ->
                    f.copy(aiDismissed = true, originalDeduction = f.deduction, deduction = 0)
                f.ruleId in dismissedIds -> f.copy(aiDismissed = true)
                else -> f
            }
        }.toMutableList()

        // ---- AI 修正：加重追加（≤ 原扣分 50%，单条封顶 −15）----
        val reviewById = reviews.associateBy { it.ruleId }
        for (i in findings.indices) {
            val f = findings[i]
            val r = reviewById[f.ruleId] ?: continue
            if (r.judgement != "加重" || f.deduction >= 0) continue
            val bySeverity = r.severity?.let { SUPPLEMENT_DEDUCTION[it.uppercase()] }
            val extra = bySeverity ?: (f.deduction / 2).coerceAtLeast(AGGRAVATE_PER_RULE_CAP)
            if (extra >= 0) continue
            findings[i] = f.copy(aiAggravated = true, aiAdjustment = extra, deduction = f.deduction + extra)
        }

        // ---- AI 修正：规则外补充发现（固定档位，封顶 −40）----
        val supplements = mutableListOf<ScoreFinding>()
        if (verdict != null) {
            val candidates = verdict.findings.filter {
                it.status == "已确认" && it.evidence.isNotBlank() && it.severity.uppercase() in SUPPLEMENT_DEDUCTION
            }
            // 同一档位最多 2 条，总扣分封顶 −40；超出部分仍进报告但不计分
            val used = mutableMapOf<String, Int>()
            var budget = 0
            for ((idx, fnd) in candidates.withIndex()) {
                val sev = fnd.severity.uppercase()
                val ded = SUPPLEMENT_DEDUCTION[sev]!!
                val id = "AI_SUPP_${sev}_$idx"
                if (used.getOrDefault(sev, 0) >= 2 || budget + ded < SUPPLEMENT_TOTAL_CAP) {
                    supplements += ScoreFinding(
                        ruleId = id,
                        title = fnd.title.ifBlank { "AI 发现（超出计分上限，仅提示）" },
                        severity = sev,
                        deduction = 0,
                        detail = "已确认但超出补充计分上限（同档最多 2 条 / 总封顶 −40），只提示不扣分；${fnd.impact}",
                        evidence = fnd.evidence.take(200).let(::listOf),
                        aiAdjustment = 0,
                    )
                    continue
                }
                used[sev] = used.getOrDefault(sev, 0) + 1
                budget += ded
                supplements += ScoreFinding(
                    ruleId = id,
                    title = fnd.title.ifBlank { "AI 发现" },
                    severity = sev,
                    deduction = ded,
                    detail = buildString {
                        append("AI 复核的规则外发现（已确认）；")
                        if (fnd.impact.isNotBlank()) append(fnd.impact)
                    },
                    evidence = fnd.evidence.take(200).let(::listOf),
                    aiAdjustment = ded,
                )
            }
        }
        findings += supplements

        // ---- 汇总：单一分数 ----
        val totalDeduction = findings.sumOf { it.deduction }
        val aiAdjustment = findings.sumOf { it.aiAdjustment } -
            findings.filter { it.aiDismissed }.sumOf { it.originalDeduction }  // 误报退回：originalDeduction 为负，取负得正
        val finalScore = (100 + totalDeduction).coerceIn(0, 100)

        if (verdict != null) {
            val restoredPts = -findings.filter { it.aiDismissed }.sumOf { it.originalDeduction }
            val aggravatedPts = findings.filter { it.aiAggravated }.sumOf { it.aiAdjustment }
            val supplementPts = supplements.sumOf { it.aiAdjustment }
            val parts = buildList {
                if (restoredPts != 0) add("误报退回 ${restoredPts} 分")
                if (aggravatedPts != 0) add("加重 ${aggravatedPts} 分")
                if (supplementPts != 0) add("规则外发现扣 ${supplementPts} 分")
            }
            if (parts.isNotEmpty()) notes += "AI 复核（不计独立分）：${parts.joinToString("，")}"
        }

        when (provenance?.status) {
            ProvenanceArtifact.FAILED -> notes += "联网来源核验失败（${provenance.error ?: "网络错误"}），可在「包信息与签名」重新核验"
            ProvenanceArtifact.DISABLED -> notes += "联网来源核验已关闭，未与 GitHub 官方发布比对"
        }

        val counted = findings.filter { it.deduction != 0 }
        val high = counted.count { it.severity == "HIGH" }
        val medium = counted.count { it.severity == "MEDIUM" }
        val low = counted.count { it.severity == "LOW" }

        return ReportArtifact(
            targetName = targetName,
            targetKind = targetKind,
            score = finalScore,
            level = ScoreRules.levelOf(finalScore),
            totalDeduction = totalDeduction,
            aiAdjustment = aiAdjustment,
            aiDismissedAdjustment = 0,
            highCount = high,
            mediumCount = medium,
            lowCount = low,
            findings = findings,
            chains = chain?.hits.orEmpty(),
            aiStatus = aiStatus,
            aiSummary = ai?.summary,
            notes = notes.distinct(),
            generatedAt = generatedAt,
            aiVerdict = verdict,
            aiModel = ai?.model,
            aiError = ai?.error,
            provenance = provenance,
        )
    }

    /** 与可靠来源（updateJson / LSPosed 仓库 / 包内链接）比对不一致：疑似修改版。 */
    internal fun provenanceFindings(p: ProvenanceArtifact?): List<ScoreFinding> {
        if (p == null || !p.modified) return emptyList()
        return listOf(
            ScoreFinding(
                ruleId = "SRC_MODIFIED",
                title = "与官方发布不一致（疑似修改版）",
                severity = "MEDIUM",
                deduction = -15,
                detail = "${p.repo?.fullName ?: "官方仓库"}（${p.matchedBy ?: "来源核验"}）：${p.detail}",
                evidence = listOfNotNull(p.officialSigner?.let { "官方证书 SHA256: $it" }, p.release?.let { "比对 release: ${it.tag} ${it.asset.orEmpty()}" }) +
                    p.diffFiles.take(10).map { "不同: $it" },
            ),
        )
    }
}

/** 与 [com.modradar.app.task.AiReviewResult] 保持一致的字符串常量，避免跨包依赖。 */
object AiReviewResultStatus {
    const val DONE = "DONE"
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val FAILED = "FAILED"
}
