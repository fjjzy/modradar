package com.modradar.app.task.stages

import com.modradar.app.task.AiReviewRequest
import com.modradar.app.task.AiReviewResult
import com.modradar.app.task.AiReviewer
import com.modradar.app.task.Stage
import com.modradar.app.task.StageContext
import com.modradar.app.task.StageRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * S5 AI 研判。
 *
 * 注意：**未配置 AI 不算失败**——写入 status=NOT_CONFIGURED 后正常完成，
 * 保证「本地分析照常跑完，报告标注未完成 AI 研判」。
 * AI 结论不计入总分（由 S6 与 ScoreRules 保证）。
 */
class AiStage(private val reviewer: AiReviewer?) : StageRunner {

    override val stage: Stage = Stage.AI

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun run(
        ctx: StageContext,
        onProgress: (Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        onProgress(10, "准备 AI 研判")

        val result: AiReviewResult = if (reviewer == null) {
            AiReviewResult(
                status = AiReviewResult.NOT_CONFIGURED,
                error = "未注入 AI 研判器",
            )
        } else {
            runCatching {
                reviewer.review(
                    AiReviewRequest(
                        taskId = ctx.taskId,
                        targetName = ctx.targetName,
                        targetKind = ctx.targetKind.name,
                        completed = ctx.completedStages,
                        pending = ctx.pendingStages,
                    )
                )
            }.getOrElse {
                AiReviewResult(status = AiReviewResult.FAILED, error = it.message ?: "未知错误")
            }
        }

        ctx.checkPause()

        val artifact = AiReviewArtifact(
            status = result.status,
            summary = result.summary,
            toolCalls = result.toolCalls,
            model = result.model,
            error = result.error,
        )
        ctx.write(ctx.artifactName(stage), json.encodeToString(artifact))

        onProgress(
            100,
            when (result.status) {
                AiReviewResult.NOT_CONFIGURED -> "未配置 AI，跳过研判"
                AiReviewResult.DONE -> "AI 研判完成"
                else -> "AI 研判失败：${result.error.orEmpty()}"
            },
        )
    }
}
