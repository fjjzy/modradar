package com.modradar.app.task

import com.modradar.app.scan.model.TargetKind
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

        val kind = runCatching { TargetKind.valueOf(task.targetKind) }.getOrDefault(TargetKind.UNKNOWN)
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
            } catch (p: PauseSignal) {
                repo.setStage(taskId, stage, StageState.PENDING, detail = "已暂停，续跑时重入")
                repo.setState(taskId, TaskState.PAUSED)
                return PipelineOutcome.Paused(stage, "用户暂停")
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
