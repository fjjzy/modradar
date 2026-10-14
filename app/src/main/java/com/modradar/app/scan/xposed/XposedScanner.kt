package com.modradar.app.scan.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class XposedModuleInfo(
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val minVersion: String? = null,
    val description: String? = null,
    val apkPath: String? = null,
    /**
     * 激活状态。无公开 API：需要 LSPosed 的接口，
     * 拿不到时一律为 null（界面显示「未知」），不猜测。
     */
    val enabled: Boolean? = null,
)

/** 已安装 Xposed 模块识别（免 root，基于 PackageManager meta-data）。 */
object XposedScanner {

    fun scan(ctx: Context): List<XposedModuleInfo> = runCatching {
        val pm = ctx.packageManager
        val apps: List<ApplicationInfo> = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        apps.mapNotNull { app ->
            val meta = app.metaData ?: return@mapNotNull null
            val isModule = when (val v = meta.get("xposedmodule")) {
                is Boolean -> v
                is String -> v.equals("true", ignoreCase = true)
                else -> false
            }
            if (!isModule) return@mapNotNull null

            XposedModuleInfo(
                packageName = app.packageName,
                label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName),
                versionName = runCatching {
                    pm.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull(),
                minVersion = meta.get("xposedminversion")?.toString(),
                description = meta.get("xposeddescription")?.toString(),
                apkPath = app.sourceDir,
                enabled = null,
            )
        }.sortedBy { it.label }
    }.getOrDefault(emptyList())

    /** 统计包名列表，便于与 root 侧数据对照。 */
    fun packageNames(ctx: Context): Set<String> = scan(ctx).map { it.packageName }.toSet()
}
