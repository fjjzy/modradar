package com.modradar.app.task.stages

import com.modradar.app.task.Stage
import com.modradar.app.task.StageContext
import com.modradar.app.task.StageRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * S4 规则评分：读取 S2 / S3 产物 → [ScoreRules] 计算 → 写 S4.json。
 * 规则细节见 [ScoreRules]（纯函数，可单测）。
 */
class ScoreStage : StageRunner {

    override val stage: Stage = Stage.SCORE

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun run(
        ctx: StageContext,
        onProgress: (Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        onProgress(10, "读取 S2 / S3 产物")

        val extract = ctx.read("${Stage.EXTRACT.id}.json")
            ?.let { runCatching { json.decodeFromString<ExtractArtifact>(it) }.getOrNull() }
        val chain = ctx.read("${Stage.CHAIN.id}.json")
            ?.let { runCatching { json.decodeFromString<ChainArtifact>(it) }.getOrNull() }

        ctx.checkPause()
        onProgress(60, "按规则表计算评分")

        val artifact = ScoreRules.evaluate(extract, chain, ctx.targetKind.name)
        ctx.write(ctx.artifactName(stage), json.encodeToString(artifact))

        onProgress(100, "评分完成 · ${artifact.score} 分（${artifact.level}）")
    }
}
