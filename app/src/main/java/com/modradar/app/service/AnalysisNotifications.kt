package com.modradar.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.modradar.app.MainActivity
import com.modradar.app.R

/** 分析期常驻通知：进度 + 暂停/继续/取消 操作。 */
object AnalysisNotifications {

    const val CHANNEL_ID = "modradar.analysis"
    const val NOTIF_ID = 1001

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "分析任务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "模块分析进度与操作"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    fun build(
        ctx: Context,
        taskId: String,
        targetName: String,
        stageLabel: String,
        progress: Int,
        paused: Boolean,
        message: String?,
    ): Notification {
        ensureChannel(ctx)

        val contentIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AnalysisService.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleAction = if (paused) AnalysisService.ACTION_RESUME else AnalysisService.ACTION_PAUSE
        val toggleLabel = if (paused) "继续" else "暂停"
        val toggleIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        val toggleIntent = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, AnalysisService::class.java)
                .setAction(toggleAction)
                .putExtra(AnalysisService.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = PendingIntent.getService(
            ctx, 2,
            Intent(ctx, AnalysisService::class.java)
                .setAction(AnalysisService.ACTION_CANCEL)
                .putExtra(AnalysisService.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = buildString {
            append(stageLabel)
            if (progress in 0..100) append(" · $progress%")
            if (!message.isNullOrBlank()) append(" · ").append(message)
        }

        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (paused) "已暂停：$targetName" else "正在分析：$targetName")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .setContentIntent(contentIntent)
            .addAction(toggleIcon, toggleLabel, toggleIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
