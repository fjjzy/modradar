package com.modradar.app

import android.app.Application
import com.modradar.app.ai.AiSettings
import com.modradar.app.ai.LmAutoReviewer
import com.modradar.app.ai.tools.ToolRegistry
import com.modradar.app.data.db.ModRadarDb
import com.modradar.app.task.ArtifactStore
import com.modradar.app.task.Pipeline
import com.modradar.app.task.TaskRepository
import com.modradar.app.task.stages.defaultStageRunners
import java.io.File

class ModRadarApp : Application() {

    val db: ModRadarDb by lazy { ModRadarDb.get(this) }

    val repo: TaskRepository by lazy { TaskRepository(db) }

    /** checkpoint 与结构化结果：必须落在 filesDir，cacheDir 会被系统清理。 */
    val store: ArtifactStore by lazy { ArtifactStore(File(filesDir, "tasks")) }

    /** 大体积中间产物：可重算。 */
    val cacheRoot: File get() = File(cacheDir, "tasks").also { it.mkdirs() }

    val tools: ToolRegistry by lazy { ToolRegistry(repo, store) }

    /** 自动研判：设置每次调用时实时读取，未配置时返回 NOT_CONFIGURED。 */
    private val autoReviewer by lazy {
        LmAutoReviewer(tools, settingsProvider = { AiSettings.load(this) })
    }

    val pipeline: Pipeline by lazy {
        Pipeline(store, repo, defaultStageRunners(autoReviewer), cacheRoot)
    }

    companion object {
        fun of(context: android.content.Context): ModRadarApp =
            context.applicationContext as ModRadarApp
    }
}
