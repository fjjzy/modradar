package com.modradar.app.scan.ksu

import kotlinx.serialization.Serializable

/** KSU / Magisk 模块信息。 */
@Serializable
data class KsuModuleInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val versionCode: String? = null,
    val author: String? = null,
    val description: String? = null,
    /** root 模式下的模块目录（zip 模式为 null）。 */
    val dir: String? = null,
    /** disable 标志存在即停用；null 表示未知（zip 模式）。 */
    val enabled: Boolean? = null,
    /** 模块目录下的脚本文件名。 */
    val scripts: List<String> = emptyList(),
    /** 缺失的必备字段。 */
    val missingFields: List<String> = emptyList(),
)

/** module.prop 解析（KSU 与 Magisk 通用格式）。 */
object ModuleProp {

    val REQUIRED_FIELDS = listOf("id", "name", "version", "versionCode", "author", "description")

    fun parse(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()

    fun missingFields(props: Map<String, String>): List<String> =
        REQUIRED_FIELDS.filter { props[it].isNullOrBlank() }

    fun toInfo(props: Map<String, String>, dir: String? = null, enabled: Boolean? = null, scripts: List<String> = emptyList()): KsuModuleInfo =
        KsuModuleInfo(
            id = props["id"].orEmpty().ifBlank { dir?.substringAfterLast('/') ?: "unknown" },
            name = props["name"].orEmpty().ifBlank { props["id"].orEmpty() },
            version = props["version"],
            versionCode = props["versionCode"],
            author = props["author"],
            description = props["description"],
            dir = dir,
            enabled = enabled,
            scripts = scripts,
            missingFields = missingFields(props),
        )
}

/** 模块脚本危险模式检测（对应能力链规则 C7）。 */
object ScriptRisk {

    data class Hit(val label: String, val line: String)

    /** 模块常见脚本文件名。 */
    val SCRIPT_NAMES = listOf(
        "customize.sh", "install.sh", "post-fs-data.sh", "service.sh",
        "uninstall.sh", "boot-completed.sh", "action.sh", "late_start.sh",
    )

    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""(curl|wget)\s+[^|]*\|\s*(sh|bash)""", RegexOption.IGNORE_CASE) to "下载后直接执行（管道）",
        Regex("""(curl|wget)\s+.*-o\s+\S+.*&&.*(sh|bash)\s""", RegexOption.IGNORE_CASE) to "下载文件后执行",
        Regex("""busybox\s+wget""", RegexOption.IGNORE_CASE) to "busybox 下载",
        Regex("""resetprop""", RegexOption.IGNORE_CASE) to "修改系统属性",
        Regex("""mount\s+(-o\s+)?(--bind|bind)""", RegexOption.IGNORE_CASE) to "bind 挂载系统路径",
        Regex("""rm\s+-rf\s+/(\s|$)""", RegexOption.IGNORE_CASE) to "删除根目录",
        Regex("""crontab""", RegexOption.IGNORE_CASE) to "定时任务",
        Regex("""(insmod|modprobe)\s""", RegexOption.IGNORE_CASE) to "加载内核模块",
        Regex("""setenforce\s+0""", RegexOption.IGNORE_CASE) to "关闭 SELinux",
        Regex("""iptables""", RegexOption.IGNORE_CASE) to "修改防火墙规则",
        Regex("""dd\s+if=""", RegexOption.IGNORE_CASE) to "写入块设备",
        Regex(""">\s*/system/""", RegexOption.IGNORE_CASE) to "直接写入系统分区",
        Regex("""chmod\s+777""", RegexOption.IGNORE_CASE) to "授予 777 权限",
        Regex("""/data/adb/.*\.sh""", RegexOption.IGNORE_CASE) to "引用 /data/adb 下脚本",
        Regex("""nohup|&\s*$""", RegexOption.IGNORE_CASE) to "后台常驻进程",
    )

    fun scan(content: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            for ((re, label) in PATTERNS) {
                if (re.containsMatchIn(line)) {
                    hits += Hit(label, line.take(200))
                    break
                }
            }
        }
        return hits
    }

    fun scanAll(scripts: Map<String, String>): Map<String, List<Hit>> =
        scripts.mapValues { (_, content) -> scan(content) }.filterValues { it.isNotEmpty() }
}
