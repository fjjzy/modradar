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
            val meta = app.metaData
            if (!isModule(app)) return@mapNotNull null

            XposedModuleInfo(
                packageName = app.packageName,
                label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName),
                versionName = runCatching {
                    pm.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull(),
                minVersion = meta?.get("xposedminversion")?.toString(),
                description = meta?.get("xposeddescription")?.toString(),
                apkPath = app.sourceDir,
                enabled = null,
            )
        }.sortedBy { it.label }
    }.getOrDefault(emptyList())

    /**
     * 旧式模块写 meta-data `xposedmodule`；新式模块（libxposed API 100+）不写 meta-data，
     * 只在包内放 `META-INF/xposed/java_init.list`，需要看 APK 条目才能认出来。
     */
    fun isModule(app: ApplicationInfo): Boolean {
        val legacy = when (val v = app.metaData?.get("xposedmodule")) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }
        // 系统应用不会是新式模块，跳过开 zip，避免列表加载变慢
        return legacy || ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && isModernModuleApk(app.sourceDir))
    }

    fun isModernModuleApk(path: String?): Boolean = path != null && runCatching {
        java.util.zip.ZipFile(path).use { z -> MODERN_ENTRIES.any { z.getEntry(it) != null } }
    }.getOrDefault(false)

    val MODERN_ENTRIES = listOf("META-INF/xposed/java_init.list", "assets/xposed_init")

    /** 统计包名列表，便于与 root 侧数据对照。 */
    fun packageNames(ctx: Context): Set<String> = scan(ctx).map { it.packageName }.toSet()
}
