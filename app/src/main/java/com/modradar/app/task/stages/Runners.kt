package com.modradar.app.task.stages

import com.modradar.app.task.AiReviewer
import com.modradar.app.task.StageRunner

/**
 * 已实现的阶段：S1–S6 全部注册。
 *
 * [reviewer] 为 null 时 S5 写入 NOT_CONFIGURED 并正常完成，
 * 不会把任务卡住——符合「无 AI 时本地分析照常跑完」的要求。
 */
fun defaultStageRunners(
    reviewer: AiReviewer?,
    provenance: com.modradar.app.provenance.ProvenanceStep? = null,
): List<StageRunner> = listOf(
    LoadStage(),
    ExtractStage(),
    ChainStage(),
    ScoreStage(),
    AiStage(reviewer, provenance),
    ReportStage(),
)
