package com.modradar.app.task.stages

/**
 * S6 报告聚合（纯函数，可单测）。
 *
 * 关键约束：**AI 结论不计入总分**（aiCountedInScore 恒为 false），
 * 且 AI 缺失时报告明确标注「未完成 AI 研判」，而不是显示为安全。
 */
object ReportBuilder {

    fun build(
        targetName: String,
        targetKind: String,
        load: LoadArtifact?,
        extract: ExtractArtifact?,
        chain: ChainArtifact?,
        score: ScoreArtifact?,
        ai: AiReviewArtifact?,
        generatedAt: Long,
    ): ReportArtifact {
        val findings = score?.findings.orEmpty()
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

        val high = findings.count { it.severity == "HIGH" }
        val medium = findings.count { it.severity == "MEDIUM" }
        val low = findings.count { it.severity == "LOW" }

        return ReportArtifact(
            targetName = targetName,
            targetKind = targetKind,
            score = score?.score ?: 100,
            level = score?.level ?: "LOW",
            totalDeduction = score?.totalDeduction ?: 0,
            highCount = high,
            mediumCount = medium,
            lowCount = low,
            findings = findings,
            chains = chain?.hits.orEmpty(),
            aiStatus = aiStatus,
            aiSummary = ai?.summary,
            notes = notes.distinct(),
            generatedAt = generatedAt,
            aiCountedInScore = false,
        )
    }
}

/** 与 [com.modradar.app.task.AiReviewResult] 保持一致的字符串常量，避免跨包依赖。 */
object AiReviewResultStatus {
    const val DONE = "DONE"
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val FAILED = "FAILED"
}
