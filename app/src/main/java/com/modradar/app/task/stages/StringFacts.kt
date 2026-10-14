package com.modradar.app.task.stages

/** DEX 字符串层面的特征表：追踪 SDK、反调试、明文流量。 */
object StringFacts {

    /** 统计/广告/崩溃上报 SDK 关键词（命中即视为该 SDK 存在）。 */
    val TRACKER_KEYWORDS: List<Pair<String, String>> = listOf(
        "com/umeng" to "友盟",
        "com/tencent/bugly" to "Bugly",
        "com/uc/crash" to "UC Crash",
        "com/pangle" to "穿山甲",
        "com/qq/e/comm" to "优量汇",
        "com/kwad" to "快手广告",
        "com/baidu/mobads" to "百度联盟",
        "com/baidu/autmon" to "百度统计",
        "com/beizi" to "倍孜",
        "com/bytedance/sdk/openadsdk" to "穿山甲 SDK",
        "com/huawei/hms/ads" to "华为广告",
        "com/alibaba/analytics" to "阿里统计",
        "com/sensorsdata" to "神策",
        "com/thinkingdata" to "数数科技",
        "com/google/firebase/analytics" to "Firebase Analytics",
        "com/google/android/gms/analytics" to "Google Analytics",
        "com/facebook/appevents" to "Facebook Events",
        "com/appsflyer" to "AppsFlyer",
        "com/adjust/sdk" to "Adjust",
        "com/igexin" to "个推",
        "com/getui" to "个推",
        "com/jpush" to "极光推送",
        "com/xiaomi/mipush" to "小米推送",
        "com/apminsight" to "腾讯 APM",
        "com/volcengine/apm" to "火山 APM",
    )

    /** 反调试 / 反注入 / 环境检测特征。 */
    val ANTIDEBUG_KEYWORDS: List<Pair<String, String>> = listOf(
        "frida" to "Frida 检测",
        "xposed" to "Xposed 检测",
        "substrate" to "Substrate 检测",
        "/proc/self/maps" to "maps 自检",
        "/proc/self/status" to "status 自检",
        "ptrace" to "ptrace 自检",
        "isDebuggerConnected" to "调试器检测",
        "ro.debuggable" to "ro.debuggable 检测",
        "ro.secure" to "ro.secure 检测",
        "test-keys" to "test-keys 检测",
        "su binary" to "su 检测",
        "magisk" to "Magisk 检测",
        "qemu" to "模拟器检测(qemu)",
        "goldfish" to "模拟器检测(goldfish)",
        "ro.kernel.qemu" to "模拟器检测",
    )

    /** 已知 tracker 域名片段（明文直连）。 */
    val TRACKER_DOMAINS: List<Pair<String, String>> = listOf(
        "umeng.com" to "友盟",
        "umengcloud.com" to "友盟",
        "bugly.qq.com" to "Bugly",
        "pangolin-sdk-toutiao.com" to "穿山甲",
        "pangle.io" to "穿山甲",
        "gdt.qq.com" to "优量汇",
        "e.qq.com" to "腾讯广告",
        "kwad.com" to "快手广告",
        "mobads.baidu.com" to "百度联盟",
        "pos.baidu.com" to "百度统计",
        "hm.baidu.com" to "百度统计",
        "sensorsdata.cn" to "神策",
        "appsflyer.com" to "AppsFlyer",
        "adjust.com" to "Adjust",
        "jpush.cn" to "极光推送",
        "getui.com" to "个推",
        "firebaseio.com" to "Firebase",
    )

    fun matchTracker(text: String): Set<String> =
        TRACKER_KEYWORDS.filter { text.contains(it.first) }.map { it.second }.toSet()

    fun matchAntiDebug(text: String): Set<String> =
        ANTIDEBUG_KEYWORDS.filter { text.contains(it.first, ignoreCase = true) }.map { it.second }.toSet()

    fun matchTrackerDomain(url: String): String? =
        TRACKER_DOMAINS.firstOrNull { url.contains(it.first, ignoreCase = true) }?.second
}
