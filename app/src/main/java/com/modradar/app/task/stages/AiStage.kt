package com.modradar.app.task.stages

import com.modradar.app.task.AiReviewRequest
import com.modradar.app.task.AiReviewResult
import com.modradar.app.task.AiReviewer
import com.modradar.app.task.Stage
import com.modradar.app.task.StageContext
import com.modradar.app.task.StageRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * S5 AI 研判。
 *
 * 注意：**未配置 AI 不算失败**——写入 status=NOT_CONFIGURED 后正常完成，
 * 保证「本地分析照常跑完，报告标注未完成 AI 研判」。
 * 输出按 P7 协议解析为 [AiVerdict]；解析失败时保留原文，报告退化为 Markdown 展示。
 */
class AiStage(
    private val reviewer: AiReviewer?,
    /** 联网来源核验：与 AI 研判并发，结果单独写 provenance.json 供 S6 使用。 */
    private val provenance: com.modradar.app.provenance.ProvenanceStep? = null,
) : StageRunner {

    override val stage: Stage = Stage.AI

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun run(
        ctx: StageContext,
        onProgress: (Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) { coroutineScope {
        onProgress(10, "准备 AI 研判")
        val prov = async {
            runCatching {
                provenance?.run(ctx.targetPath, ctx.targetName, ctx.targetKind.name, ctx::read, ctx::write)
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }

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

        if (!prov.isCompleted) onProgress(90, "等待联网来源核验")
        prov.await()
        ctx.checkPause()

        val artifact = AiReviewArtifact(
            status = result.status,
            summary = result.summary,
            toolCalls = result.toolCalls,
            model = result.model,
            error = result.error,
            verdict = if (result.status == AiReviewResult.DONE) AiVerdictParser.parse(result.summary) else null,
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
    } }
}
