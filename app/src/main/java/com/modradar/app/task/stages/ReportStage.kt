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

        ctx.checkPause()
        onProgress(70, "汇总报告")
        val report = assemble(ctx::read, ctx.targetName, ctx.targetKind.name)
        ctx.write(ctx.artifactName(stage), json.encodeToString(report))

        onProgress(100, "报告生成完成 · ${report.score} 分（${report.level}）")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 从各阶段产物汇总报告；报告页「重新核验来源」后也用它重建 S6。 */
        fun assemble(read: (String) -> String?, targetName: String, targetKind: String): ReportArtifact {
            fun <T> load(name: String, de: (String) -> T): T? = read(name)?.let { runCatching { de(it) }.getOrNull() }
            return ReportBuilder.build(
                targetName = targetName,
                targetKind = targetKind,
                load = load("${Stage.LOAD.id}.json") { json.decodeFromString<LoadArtifact>(it) },
                extract = load("${Stage.EXTRACT.id}.json") { json.decodeFromString<ExtractArtifact>(it) },
                chain = load("${Stage.CHAIN.id}.json") { json.decodeFromString<ChainArtifact>(it) },
                score = load("${Stage.SCORE.id}.json") { json.decodeFromString<ScoreArtifact>(it) },
                ai = load("${Stage.AI.id}.json") { json.decodeFromString<AiReviewArtifact>(it) },
                generatedAt = System.currentTimeMillis(),
                provenance = load(ProvenanceArtifact.FILE) { json.decodeFromString<ProvenanceArtifact>(it) },
            )
        }
    }
}
