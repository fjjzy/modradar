package com.modradar.app.service

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 保活四项的状态快照。 */
data class KeepAliveState(
    val notificationsGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val backgroundRestricted: Boolean,
    val vendorPages: List<VendorAutostart.Candidate>,
) {
    val allGood: Boolean
        get() = notificationsGranted && batteryOptimizationIgnored && !backgroundRestricted
}

/**
 * 后台执行能力检测与引导。
 *
 * 说明：厂商「后台运行白名单」与「最近任务锁定」都没有公开 API，
 * 只能检测「能否跳到对应页面」+ 图文引导，不能真实检测是否已加入白名单。
 */
object BackgroundGuard {

    fun check(ctx: Context): KeepAliveState {
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignored = power?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            am?.isBackgroundRestricted ?: false
        } else {
            false
        }

        return KeepAliveState(
            notificationsGranted = notificationsGranted,
            batteryOptimizationIgnored = ignored,
            backgroundRestricted = restricted,
            vendorPages = runCatching { VendorAutostart.resolvable(ctx) }.getOrDefault(emptyList()),
        )
    }

    /** 直接申请忽略电池优化；部分 ROM 会拒绝，调用方需回退到设置页。 */
    fun requestIgnoreBatteryOptimization(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))

    /** 回退：电池优化设置列表页。 */
    fun batteryOptimizationSettings(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 通用兜底：应用详情页。 */
    fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))

    /** 厂商后台运行页；解析不到时回退应用详情页。 */
    fun backgroundRunPage(ctx: Context): Intent =
        VendorAutostart.best(ctx) ?: appDetails(ctx)
}
