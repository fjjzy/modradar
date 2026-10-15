package com.modradar.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 分析任务。state: IDLE / RUNNING / PAUSED / FAILED / DONE */
@Entity(tableName = "tasks")
data class AnalysisTaskEntity(
    @PrimaryKey val id: String,
    val targetPath: String,
    val targetName: String,
    val targetKind: String,
    val state: String,
    val currentStage: String,
    val progress: Int,
    val score: Int? = null,
    val riskLevel: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
)

/** 阶段检查点。(taskId, stage) 复合主键。state: PENDING / RUNNING / DONE / FAILED */
@Entity(tableName = "checkpoints", primaryKeys = ["taskId", "stage"])
data class StageCheckpointEntity(
    val taskId: String,
    val stage: String,
    val state: String,
    val artifactFile: String? = null,
    val sha256: String? = null,
    val completedAt: Long? = null,
    val detail: String? = null,
)

/** AI 会话（独立于任务状态：暂停/未完成也可对话）。 */
@Entity(tableName = "chat_sessions", indices = [Index("taskId")])
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 会话消息。role: user / assistant / tool */
@Entity(tableName = "chat_messages", indices = [Index("sessionId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val content: String,
    val toolCallsJson: String? = null,
    val createdAt: Long,
    /** 模型的思考过程（reasoning_content），只展示不回放。 */
    val reasoning: String? = null,
)
