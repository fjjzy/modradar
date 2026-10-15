package com.modradar.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.modradar.app.ModRadarApp
import com.modradar.app.task.PipelineOutcome
import com.modradar.app.task.Stage
import com.modradar.app.task.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 分析前台服务。
 *
 * - 类型 dataSync：Android 15+ 有 6 小时上限，超时前提交 checkpoint 并优雅停止
 * - 暂停：置位 pauseRequested，流水线在安全点停下并落盘（不在写盘中途中断）
 * - 继续：重新进入流水线，已完成阶段直接跳过
 * - 进程死亡：START_REDELIVER_INTENT + 每次 checkpoint 落库，重开后可从断点恢复
 */
class AnalysisService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pauseRequested = AtomicBoolean(false)
    private var job: Job? = null
    private var currentTaskId: String? = null

    private val app: ModRadarApp get() = ModRadarApp.of(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID) ?: currentTaskId
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> if (taskId != null) enqueueOrBegin(taskId)
            ACTION_PAUSE -> {
                if (taskId != null && taskId != currentTaskId) {
                    dequeue(taskId)
                } else {
                    pauseRequested.set(true)
                    taskId?.let { refreshNotification(it, Stage.LOAD, -1, paused = true, message = "正在暂停…") }
                }
            }
            ACTION_CANCEL -> if (taskId != null) cancelTask(taskId)
            else -> if (taskId != null) enqueueOrBegin(taskId)
        }
        return START_REDELIVER_INTENT
    }

    /** 重操作全局串行：已有任务在跑时排队，跑完自动接下一个。 */
    private fun enqueueOrBegin(taskId: String) {
        if (job?.isActive == true && currentTaskId != taskId) {
            if (taskId !in queue.value) queue.value = queue.value + taskId
            promoteToForeground(currentTaskId ?: taskId)
            return
        }
        begin(taskId)
    }

    private fun dequeue(taskId: String) {
        queue.value = queue.value - taskId
    }

    /** 当前任务结束后：有排队则继续，否则退出前台。 */
    private fun next() {
        val nextId = queue.value.firstOrNull()
        if (nextId != null) {
            queue.value = queue.value.drop(1)
            begin(nextId)
        } else {
            currentTaskId = null
            running.value = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun begin(taskId: String) {
        currentTaskId = taskId
        running.value = taskId
        dequeue(taskId)
        pauseRequested.set(false)

        // 必须尽快进入前台；通知权限被拒时仍可运行，只是通知不可见
        promoteToForeground(taskId)

        job?.cancel()
        job = scope.launch {
            val outcome = app.pipeline.run(
                taskId = taskId,
                onProgress = { stage, pct, msg ->
                    appendLog(taskId, "${stage.id} · $msg")
                    refreshNotification(taskId, stage, pct, paused = false, message = msg)
                },
                isPauseRequested = { pauseRequested.get() },
            )
            when (outcome) {
                is PipelineOutcome.Done -> {
                    refreshNotification(taskId, Stage.REPORT, 100, paused = false, message = "完成")
                }
                is PipelineOutcome.Paused -> {
                    refreshNotification(taskId, outcome.atStage, -1, paused = true, message = outcome.reason)
                }
                is PipelineOutcome.Failed -> {
                    refreshNotification(taskId, outcome.atStage ?: Stage.LOAD, -1, paused = false, message = "失败：${outcome.message}")
                }
            }
            next()
        }
    }

    private fun cancelTask(taskId: String) {
        dequeue(taskId)
        val wasCurrent = taskId == currentTaskId
        if (wasCurrent) job?.cancel()
        scope.launch {
            app.repo.delete(taskId)
            app.store.deleteTask(taskId)
        }
        if (wasCurrent) next()
    }

    /** Android 15+：dataSync 前台服务超时回调，先落盘再停，不留半截状态。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        pauseRequested.set(true)
        currentTaskId?.let {
            refreshNotification(it, Stage.LOAD, -1, paused = true, message = "已达系统时长上限，可稍后继续")
        }
    }

    private fun promoteToForeground(taskId: String) {
        val notif = AnalysisNotifications.build(
            ctx = this,
            taskId = taskId,
            targetName = currentTaskId ?: "任务",
            stageLabel = "准备中",
            progress = 0,
            paused = false,
            message = null,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    AnalysisNotifications.NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(AnalysisNotifications.NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            // 后台启动前台服务受限（Android 12+）或通知被禁用：降级为仅前台分析
            stopSelf()
        }
    }

    private fun refreshNotification(
        taskId: String,
        stage: Stage,
        progress: Int,
        paused: Boolean,
        message: String?,
    ) {
        val notif = AnalysisNotifications.build(
            ctx = this,
            taskId = taskId,
            targetName = taskId.take(8),
            stageLabel = "${stage.id} ${stage.label}",
            progress = progress,
            paused = paused,
            message = message,
        )
        runCatching {
            NotificationManagerCompat.from(this).notify(AnalysisNotifications.NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.modradar.app.action.START"
        const val ACTION_PAUSE = "com.modradar.app.action.PAUSE"
        const val ACTION_RESUME = "com.modradar.app.action.RESUME"
        const val ACTION_CANCEL = "com.modradar.app.action.CANCEL"
        const val EXTRA_TASK_ID = "taskId"

        /** 排队中的任务 ID（UI 展示「排队中」）。 */
        val queue = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())

        /** 正在执行的任务 ID。 */
        val running = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

        /** 运行日志（仅内存，供进度页实时展示）：taskId → 行。 */
        val logs = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<String>>>(emptyMap())

        private val logTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

        fun appendLog(taskId: String, line: String) {
            val cur = logs.value[taskId].orEmpty()
            if (cur.lastOrNull()?.endsWith(line) == true) return
            val stamped = "${logTime.format(java.util.Date())}  $line"
            logs.value = logs.value + (taskId to (cur + stamped).takeLast(200))
        }

        fun start(ctx: Context, taskId: String) = send(ctx, ACTION_START, taskId)
        fun pause(ctx: Context, taskId: String) = send(ctx, ACTION_PAUSE, taskId)
        fun resume(ctx: Context, taskId: String) = send(ctx, ACTION_RESUME, taskId)
        fun cancel(ctx: Context, taskId: String) = send(ctx, ACTION_CANCEL, taskId)

        private fun send(ctx: Context, action: String, taskId: String) {
            val intent = Intent(ctx, AnalysisService::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId)
            runCatching { ContextCompat.startForegroundService(ctx, intent) }
        }
    }
}
