package com.modradar.app.task

import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.data.db.ModRadarDb
import com.modradar.app.data.db.StageCheckpointEntity
import com.modradar.app.scan.model.TargetKind
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** 任务 / 检查点 / 会话的统一入口。 */
class TaskRepository(private val db: ModRadarDb) {

    private val taskDao get() = db.taskDao()
    private val cpDao get() = db.checkpointDao()

    suspend fun task(id: String): AnalysisTaskEntity? = taskDao.byId(id)

    suspend fun observeAll(): Flow<List<AnalysisTaskEntity>> = taskDao.observeAll()

    suspend fun unfinished(): List<AnalysisTaskEntity> = taskDao.unfinished()

    suspend fun running(): List<AnalysisTaskEntity> = taskDao.running()

    suspend fun create(targetPath: String, targetName: String, kind: TargetKind): AnalysisTaskEntity {
        val now = System.currentTimeMillis()
        val entity = AnalysisTaskEntity(
            id = UUID.randomUUID().toString(),
            targetPath = targetPath,
            targetName = targetName,
            targetKind = kind.name,
            state = TaskState.IDLE.name,
            currentStage = Stage.LOAD.id,
            progress = 0,
            createdAt = now,
            updatedAt = now,
        )
        taskDao.upsert(entity)
        Stage.order.forEach { s ->
            cpDao.upsert(StageCheckpointEntity(entity.id, s.id, StageState.PENDING.name))
        }
        return entity
    }

    suspend fun setState(id: String, state: TaskState, error: String? = null) {
        val t = taskDao.byId(id) ?: return
        taskDao.upsert(
            t.copy(
                state = state.name,
                updatedAt = System.currentTimeMillis(),
                lastError = error ?: if (state == TaskState.RUNNING) null else t.lastError,
            )
        )
    }

    suspend fun setCurrentStage(id: String, stage: Stage, progress: Int = 0) {
        val t = taskDao.byId(id) ?: return
        taskDao.upsert(
            t.copy(currentStage = stage.id, progress = progress, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun setProgress(id: String, progress: Int) {
        val t = taskDao.byId(id) ?: return
        taskDao.upsert(t.copy(progress = progress, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setStage(
        taskId: String,
        stage: Stage,
        state: StageState,
        artifact: String? = null,
        sha: String? = null,
        detail: String? = null,
    ) {
        val prev = cpDao.one(taskId, stage.id)
        cpDao.upsert(
            StageCheckpointEntity(
                taskId = taskId,
                stage = stage.id,
                state = state.name,
                artifactFile = artifact ?: prev?.artifactFile,
                sha256 = sha ?: prev?.sha256,
                completedAt = if (state == StageState.DONE) System.currentTimeMillis() else prev?.completedAt,
                detail = detail,
            )
        )
    }

    suspend fun checkpoints(taskId: String): List<StageCheckpointEntity> = cpDao.byTask(taskId)

    suspend fun completedStages(taskId: String): Set<Stage> =
        cpDao.byTask(taskId)
            .filter { it.state == StageState.DONE.name }
            .mapNotNull { Stage.byId(it.stage) }
            .toSet()

    suspend fun setResult(id: String, score: Int?, level: String?) {
        val t = taskDao.byId(id) ?: return
        taskDao.upsert(t.copy(score = score, riskLevel = level, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setKind(id: String, kind: String) {
        val t = taskDao.byId(id) ?: return
        if (t.targetKind != kind) taskDao.upsert(t.copy(targetKind = kind))
    }

    suspend fun delete(id: String) {
        cpDao.deleteByTask(id)
        db.chatDao().deleteSessionsByTask(id)
        taskDao.delete(id)
    }
}
