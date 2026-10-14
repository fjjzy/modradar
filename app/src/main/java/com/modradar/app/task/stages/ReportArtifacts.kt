package com.modradar.app.task.stages

import kotlinx.serialization.Serializable

/** S5 产物：AI 研判结果。AI 结论**不计入总分**。 */
@Serializable
data class AiReviewArtifact(
    val status: String = "NOT_CONFIGURED",
    val summary: String? = null,
    val toolCalls: List<String> = emptyList(),
    val model: String? = null,
    val error: String? = null,
)

/** S6 产物：汇总报告。 */
@Serializable
data class ReportArtifact(
    val targetName: String = "",
    val targetKind: String = "",
    val score: Int = 100,
    val level: String = "LOW",
    val totalDeduction: Int = 0,
    val highCount: Int = 0,
    val mediumCount: Int = 0,
    val lowCount: Int = 0,
    val findings: List<ScoreFinding> = emptyList(),
    val chains: List<ChainHit> = emptyList(),
    val aiStatus: String = "NOT_CONFIGURED",
    val aiSummary: String? = null,
    val notes: List<String> = emptyList(),
    val generatedAt: Long = 0L,
    val aiCountedInScore: Boolean = false,
)
