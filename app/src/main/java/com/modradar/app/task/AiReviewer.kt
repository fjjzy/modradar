package com.modradar.app.task

/**
 * AI 研判抽象：放在 task 层，避免 task 包依赖 ai 包。
 * 未配置 AI 时实现方返回 status = NOT_CONFIGURED，**不算失败**，
 * 本地分析照常跑完，报告标注「未完成 AI 研判」。
 */
data class AiReviewResult(
    val status: String,
    val summary: String? = null,
    val toolCalls: List<String> = emptyList(),
    val model: String? = null,
    val error: String? = null,
) {
    companion object {
        const val DONE = "DONE"
        const val NOT_CONFIGURED = "NOT_CONFIGURED"
        const val FAILED = "FAILED"
    }
}

data class AiReviewRequest(
    val taskId: String,
    val targetName: String,
    val targetKind: String,
    val completed: Set<Stage>,
    val pending: List<Stage>,
)

fun interface AiReviewer {
    suspend fun review(request: AiReviewRequest): AiReviewResult
}
