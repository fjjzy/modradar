package com.modradar.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 厂商「自启动 / 后台运行」管理页跳转。
 *
 * 这些页面没有公开 API，只能按厂商包名硬跳；解析不到时由调用方回退到应用详情页。
 * 依赖 QUERY_ALL_PACKAGES 提供的包可见性（Android 11+）。
 */
object VendorAutostart {

    data class Candidate(val vendor: String, val component: String)

    private val CANDIDATES = listOf(
        Candidate("小米 / 红米", "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Candidate("华为 / 荣耀", "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity"),
        Candidate("OPPO / realme", "com.coloros.safecenter/.permission.startup.StartupAppListActivity"),
        Candidate("OPPO / realme", "com.oppo.safe/.permission.startup.StartupAppListActivity"),
        Candidate("vivo / iQOO", "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity"),
        Candidate("vivo / iQOO", "com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity"),
        Candidate("三星", "com.samsung.android.lool/.activity.SmpsMainActivity"),
        Candidate("一加", "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity"),
        Candidate("魅族", "com.meizu.safe/.permission.SmartBGActivity"),
        Candidate("联想", "com.lenovo.security/.purebackground.PureBackgroundActivity"),
        Candidate("华硕", "com.asus.mobilemanager/.MainActivity"),
        Candidate("中兴", "com.zte.heartyservice/.autorun.AppAutoRunManager"),
    )

    fun manufacturer(): String =
        (Build.MANUFACTURER ?: "").ifBlank { Build.BRAND ?: "" }.lowercase()

    /** 返回本机可解析到的厂商页面（按厂商匹配优先排序）。 */
    fun resolvable(ctx: Context): List<Candidate> {
        val mfr = manufacturer()
        return CANDIDATES
            .filter { candidateIntent(it.component)?.let { i -> exists(ctx, i) } == true }
            .sortedByDescending { mfr.isNotBlank() && it.vendor.lowercase().let { v -> mfr in v || v in mfr } }
    }

    /** 首选跳转：厂商页面，取不到则返回 null（调用方回退应用详情页）。 */
    fun best(ctx: Context): Intent? =
        resolvable(ctx).firstOrNull()?.let { candidateIntent(it.component) }

    fun intentOf(candidate: Candidate): Intent? = candidateIntent(candidate.component)

    private fun candidateIntent(component: String): Intent? {
        val parts = component.split('/')
        if (parts.size != 2) return null
        val pkg = parts[0]
        val raw = parts[1]
        val cls = if (raw.startsWith(".")) pkg + raw else raw
        return Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun exists(ctx: Context, intent: Intent): Boolean {
        val cn = intent.component ?: return false
        return runCatching { ctx.packageManager.getActivityInfo(cn, 0) }.isSuccess
    }
}
