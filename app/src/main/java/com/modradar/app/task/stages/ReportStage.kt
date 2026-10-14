package com.modradar.app.task.stages

import com.modradar.app.task.Stage
import com.modradar.app.task.StageContext
import com.modradar.app.task.StageRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** S6 报告：汇总 S1–S5 产物为一份报告。 */
class ReportStage : StageRunner {

    override val stage: Stage = Stage.REPORT

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun run(
        ctx: StageContext,
        onProgress: (Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        onProgress(15, "读取各阶段产物")

        val load = ctx.read("${Stage.LOAD.id}.json")
            ?.let { runCatching { json.decodeFromString<LoadArtifact>(it) }.getOrNull() }
        val extract = ctx.read("${Stage.EXTRACT.id}.json")
            ?.let { runCatching { json.decodeFromString<ExtractArtifact>(it) }.getOrNull() }
        val chain = ctx.read("${Stage.CHAIN.id}.json")
            ?.let { runCatching { json.decodeFromString<ChainArtifact>(it) }.getOrNull() }
        val score = ctx.read("${Stage.SCORE.id}.json")
            ?.let { runCatching { json.decodeFromString<ScoreArtifact>(it) }.getOrNull() }
        val ai = ctx.read("${Stage.AI.id}.json")
            ?.let { runCatching { json.decodeFromString<AiReviewArtifact>(it) }.getOrNull() }

        ctx.checkPause()
        onProgress(70, "汇总报告")

        val report = ReportBuilder.build(
            targetName = ctx.targetName,
            targetKind = ctx.targetKind.name,
            load = load,
            extract = extract,
            chain = chain,
            score = score,
            ai = ai,
            generatedAt = System.currentTimeMillis(),
        )
        ctx.write(ctx.artifactName(stage), json.encodeToString(report))

        onProgress(100, "报告生成完成 · ${report.score} 分（${report.level}）")
    }
}
