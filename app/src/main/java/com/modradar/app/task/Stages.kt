package com.modradar.app.task

import com.modradar.app.scan.model.TargetKind
import java.io.File

/** 流水线阶段。顺序即执行顺序。 */
enum class Stage(val id: String, val label: String) {
    LOAD("S1", "装载"),
    EXTRACT("S2", "结构提取"),
    CHAIN("S3", "能力链分析"),
    SCORE("S4", "规则评分"),
    AI("S5", "AI 研判"),
    REPORT("S6", "生成报告");

    companion object {
        val order: List<Stage> = entries.toList()
        fun byId(id: String): Stage? = entries.firstOrNull { it.id == id }
    }
}

enum class TaskState { IDLE, RUNNING, PAUSED, FAILED, DONE }

enum class StageState { PENDING, RUNNING, DONE, FAILED }

/** 用户暂停：只在安全点抛出，不会中断写盘中途。 */
class PauseSignal : Exception("paused by user")

/**
 * 阶段运行上下文。
 * 阶段在可能长时间运行的位置调用 [checkPause]，保证暂停语义为
 * 「当前工作单元跑完再停」，而不是中断在原子提交中间。
 */
class StageContext(
    val taskId: String,
    val targetPath: String,
    val targetName: String,
    val targetKind: TargetKind,
    /** 进入当前阶段前已完成的阶段（供 S5 组装部分数据提示）。 */
    val completedStages: Set<Stage>,
    /** 尚未完成的阶段（含当前阶段）。 */
    val pendingStages: List<Stage>,
    val store: ArtifactStore,
    cacheRoot: File,
    private val isPauseRequested: () -> Boolean,
) {
    val cacheDir: File = store.cacheDir(cacheRoot, taskId)

    fun checkPause() {
        if (isPauseRequested()) throw PauseSignal()
    }

    /** 写结构化结果，返回 sha256。 */
    fun write(name: String, text: String): String = store.writeAtomic(taskId, name, text)

    fun read(name: String): String? = store.readText(taskId, name)

    fun exists(name: String): Boolean = store.exists(taskId, name)

    /** 阶段主产物文件名（用于 checkpoint 记录）。 */
    fun artifactName(stage: Stage): String = "${stage.id}.json"
}

interface StageRunner {
    val stage: Stage
    /** 阶段未实现时返回 false，流水线会标记为未实现而非静默通过。 */
    val implemented: Boolean get() = true

    suspend fun run(ctx: StageContext, onProgress: (percent: Int, message: String) -> Unit)
}
