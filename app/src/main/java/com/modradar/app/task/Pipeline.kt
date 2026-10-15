package com.modradar.app.task

import com.modradar.app.scan.model.TargetKind
import com.modradar.app.task.stages.ReportArtifact
import com.modradar.app.task.stages.ScoreArtifact
import kotlinx.serialization.json.Json
import java.io.File

sealed interface PipelineOutcome {
    data object Done : PipelineOutcome

    /** 未跑完：用户暂停，或后续阶段尚未实现。可续跑。 */
    data class Paused(val atStage: Stage, val reason: String) : PipelineOutcome

    data class Failed(val atStage: Stage?, val message: String) : PipelineOutcome
}

/**
 * S1–S6 状态机。
 *
 * 续跑语义：已 DONE 的阶段直接跳过；从第一个非 DONE 阶段继续。
 * 暂停语义：仅在安全点抛出 [PauseSignal]（不会中断原子提交），
 * 被打断的阶段保持非 DONE，续跑时重新进入（阶段内部应按子产物幂等）。
 */
class Pipeline(
    private val store: ArtifactStore,
    private val repo: TaskRepository,
    runners: List<StageRunner>,
    private val cacheRoot: File,
) {
    private val runnerByStage: Map<Stage, StageRunner> = runners.associateBy { it.stage }

    suspend fun run(
        taskId: String,
        onProgress: (stage: Stage, percent: Int, message: String) -> Unit,
        isPauseRequested: () -> Boolean,
    ): PipelineOutcome {
        val task = repo.task(taskId)
            ?: return PipelineOutcome.Failed(null, "任务不存在")

        // S1 会把真实类型写回任务；后续阶段（S4 上下文降噪、S6 报告）必须用识别后的类型
        var kind = runCatching { TargetKind.valueOf(task.targetKind) }.getOrDefault(TargetKind.UNKNOWN)
        repo.setState(taskId, TaskState.RUNNING)

        val alreadyDone = repo.completedStages(taskId)
        val completedSoFar = alreadyDone.toMutableSet()

        for (stage in Stage.order) {
            if (stage in alreadyDone) {
                onProgress(stage, 100, "已完成，跳过")
                continue
            }
            if (isPauseRequested()) {
                repo.setState(taskId, TaskState.PAUSED)
                return PipelineOutcome.Paused(stage, "用户暂停")
            }

            val runner = runnerByStage[stage]
            if (runner == null || !runner.implemented) {
                repo.setStage(taskId, stage, StageState.PENDING, detail = "阶段尚未实现")
                repo.setState(taskId, TaskState.PAUSED, error = null)
                return PipelineOutcome.Paused(stage, "${stage.label} 尚未实现（等待后续版本）")
            }

            repo.setCurrentStage(taskId, stage)
            repo.setStage(taskId, stage, StageState.RUNNING)

            val ctx = StageContext(
                taskId = taskId,
                targetPath = task.targetPath,
                targetName = task.targetName,
                targetKind = kind,
                completedStages = completedSoFar.toSet(),
                pendingStages = Stage.order.filter { it !in completedSoFar },
                store = store,
                cacheRoot = cacheRoot,
                isPauseRequested = isPauseRequested,
            )

            try {
                runner.run(ctx) { pct, msg ->
                    onProgress(stage, pct, msg)
                }
                val name = ctx.artifactName(stage)
                val sha = store.readBytes(taskId, name)?.let { ArtifactStore.sha256(it) }
                repo.setStage(
                    taskId, stage, StageState.DONE,
                    artifact = if (sha != null) name else null,
                    sha = sha,
                    detail = null,
                )
                completedSoFar.add(stage)
                if (stage == Stage.SCORE) {
                    persistScore(taskId, name)
                }
                if (stage == Stage.REPORT) {
                    persistFinal(taskId, name)
                }
                if (stage == Stage.LOAD) {
                    persistKind(taskId, name)
                    repo.task(taskId)?.let { t -> runCatching { kind = TargetKind.valueOf(t.targetKind) } }
                }
            } catch (p: PauseSignal) {
                repo.setStage(taskId, stage, StageState.PENDING, detail = "已暂停，续跑时重入")
                repo.setState(taskId, TaskState.PAUSED)
                return PipelineOutcome.Paused(stage, "用户暂停")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.java.simpleName
                repo.setStage(taskId, stage, StageState.FAILED, detail = msg)
                repo.setState(taskId, TaskState.FAILED, error = msg)
                return PipelineOutcome.Failed(stage, msg)
            }
        }

        repo.setState(taskId, TaskState.DONE)
        return PipelineOutcome.Done
    }

    /** S4 完成后把分数写回任务，供首页与报告展示。 */
    private suspend fun persistScore(taskId: String, artifactName: String) {
        runCatching {
            val text = store.readText(taskId, artifactName) ?: return@runCatching
            val art = json.decodeFromString<ScoreArtifact>(text)
            repo.setResult(taskId, art.score, art.level)
        }
    }

    /** S6 完成后把统一分写回任务（分数恒为 100 − Σ扣分，AI 复核已修正）。 */
    private suspend fun persistFinal(taskId: String, artifactName: String) {
        runCatching {
            val text = store.readText(taskId, artifactName) ?: return@runCatching
            val rep = json.decodeFromString<ReportArtifact>(text)
            repo.setResult(taskId, rep.score, rep.level)
        }
    }

    /**
     * 用户退回单条 AI 修正（AI 判错时使用）：
     * - 误报清零 → 恢复原扣分；
     * - 加重 → 清掉追加，回到原扣分；
     * - 规则外补充发现 → 整条改为仅提示（扣分清零）。
     * 重算 score/level 后原子写回 S6.json 并同步任务表。
     */
    suspend fun revertAiAdjustment(taskId: String, ruleId: String): Boolean {
        val name = Stage.REPORT.let { "${it.id}.json" }
        val text = store.readText(taskId, name) ?: return false
        val rep = runCatching { json.decodeFromString<ReportArtifact>(text) }.getOrNull() ?: return false

        val restored = when {
            rep.findings.none { it.ruleId == ruleId } -> return false
            else -> rep.findings.map { f ->
                when {
                    f.ruleId != ruleId -> f
                    f.aiDismissed -> f.copy(aiDismissed = false, deduction = f.originalDeduction, originalDeduction = 0)
                    f.aiAggravated -> f.copy(aiAggravated = false, deduction = f.deduction - f.aiAdjustment, aiAdjustment = 0)
                    f.deduction != 0 && f.aiAdjustment != 0 -> f.copy(deduction = 0, aiAdjustment = 0) // 补充发现 → 仅提示
                    else -> f
                }
            }
        }
        val reverted = restored.sumOf { it.deduction } - rep.findings.sumOf { it.deduction } // 负=分数下降，正=退回
        val dismissedPts = rep.aiDismissedAdjustment + when {
            reverted < 0 -> -reverted
            else -> 0
        }
        val total = restored.sumOf { it.deduction }
        val score = (100 + total).coerceIn(0, 100)
        val aiAdj = restored.sumOf { it.aiAdjustment } + restored.filter { it.aiDismissed }.sumOf { it.originalDeduction }

        val notes = rep.notes.filterNot { it.startsWith("AI 复核") || it.startsWith("已退回") }.toMutableList()
        notes += "已退回 1 条 AI 修正（${-reverted} 分恢复为本地规则结果）"

        val updated = rep.copy(
            findings = restored,
            totalDeduction = total,
            score = score,
            level = com.modradar.app.task.stages.ScoreRules.levelOf(score),
            aiAdjustment = aiAdj,
            aiDismissedAdjustment = dismissedPts,
            notes = notes.distinct(),
        )
        store.writeAtomic(taskId, name, json.encodeToString(updated))
        repo.setResult(taskId, score, updated.level)
        return true
    }

    /** S1 识别出的真实类型写回任务（导入时只按扩展名猜测）。 */
    private suspend fun persistKind(taskId: String, artifactName: String) {
        runCatching {
            val text = store.readText(taskId, artifactName) ?: return@runCatching
            val art = json.decodeFromString<com.modradar.app.task.stages.LoadArtifact>(text)
            if (art.kind != "UNKNOWN") repo.setKind(taskId, art.kind)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** 续跑起点：第一个未完成阶段；全部完成返回 null。 */
        fun nextStage(completed: Set<Stage>): Stage? =
            Stage.order.firstOrNull { it !in completed }

        /** 已完成后仍待跑的阶段列表（用于部分数据模式提示）。 */
        fun pendingStages(completed: Set<Stage>): List<Stage> =
            Stage.order.filter { it !in completed }
    }
}
