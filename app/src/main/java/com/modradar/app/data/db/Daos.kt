package com.modradar.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Upsert
    suspend fun upsert(task: AnalysisTaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: String): AnalysisTaskEntity?

    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AnalysisTaskEntity>>

    @Query("SELECT * FROM tasks WHERE state IN ('RUNNING','PAUSED') ORDER BY updatedAt DESC")
    suspend fun unfinished(): List<AnalysisTaskEntity>

    @Query("SELECT * FROM tasks WHERE state = 'RUNNING' ORDER BY updatedAt DESC")
    suspend fun running(): List<AnalysisTaskEntity>

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CheckpointDao {

    @Upsert
    suspend fun upsert(cp: StageCheckpointEntity)

    @Query("SELECT * FROM checkpoints WHERE taskId = :taskId ORDER BY stage ASC")
    suspend fun byTask(taskId: String): List<StageCheckpointEntity>

    @Query("SELECT * FROM checkpoints WHERE taskId = :taskId AND stage = :stage")
    suspend fun one(taskId: String, stage: String): StageCheckpointEntity?

    @Query("DELETE FROM checkpoints WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}

@Dao
interface ChatDao {

    @Upsert
    suspend fun upsertSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE taskId = :taskId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestSessionForTask(taskId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun session(id: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(msg: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun messages(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM chat_sessions WHERE taskId = :taskId")
    suspend fun deleteSessionsByTask(taskId: String)
}
