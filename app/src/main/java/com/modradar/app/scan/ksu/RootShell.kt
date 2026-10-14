package com.modradar.app.scan.ksu

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 只读 root 访问。
 *
 * **安全约束（重要）**：
 * - 只执行 `ls` / `cat` / `test` 三类只读命令，绝不写、删、启停、挂载
 * - 传入路径必须通过 [isSafePath] 校验，避免命令注入
 * - 全部命令带超时，避免 su 卡住
 */
object RootShell {

    private const val TIMEOUT_SEC = 10

    /** 允许的模块根目录。 */
    val MODULE_ROOTS = listOf("/data/adb/modules", "/data/adb/ksu/modules")

    /**
     * 路径白名单校验：
     * - 必须以 [MODULE_ROOTS] 之一开头（或相等）
     * - 只含 `[A-Za-z0-9._/-]`（杜绝空格、分号、反引号、$ 等注入字符）
     * - **禁止 `..` / `.` 路径段与连续斜杠**，防止以 root 读取任意文件
     */
    fun isSafePath(path: String): Boolean {
        if (path.contains("//")) return false
        if (!MODULE_ROOTS.any { path == it || path.startsWith("$it/") }) return false
        if (!path.all { it.isLetterOrDigit() || it in "._/-" }) return false
        return path.split('/').none { it == ".." || it == "." }
    }

    /** root 是否可用（`su -c id` 输出含 uid=0）。 */
    fun isRootAvailable(): Boolean = runCatching {
        val out = exec(listOf("id")) ?: return false
        out.contains("uid=0")
    }.getOrDefault(false)

    /**
     * 列出模块目录**完整路径**（KSU 与 Magisk 两处根目录分别枚举）。
     * 返回完整路径是为了后续 cat 时能通过 [isSafePath] 校验。
     */
    fun listModuleDirs(): List<String> = runCatching {
        val out = mutableListOf<String>()
        for (root in MODULE_ROOTS) {
            val listing = exec(listOf("ls", "-1", root)) ?: continue
            listing.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("ls:") }
                .forEach { name ->
                    val full = "$root/$name"
                    if (isSafePath(full)) out += full
                }
        }
        out.distinct().sorted()
    }.getOrDefault(emptyList())

    fun readFile(path: String): String? {
        if (!isSafePath(path)) return null
        return runCatching { exec(listOf("cat", path)) }.getOrNull()
    }

    fun exists(path: String): Boolean {
        if (!isSafePath(path)) return false
        return runCatching { exec(listOf("test", "-e", path, "&&", "echo", "yes"))?.contains("yes") == true }
            .getOrDefault(false)
    }

    fun listFiles(dir: String): List<String> {
        if (!isSafePath(dir)) return emptyList()
        return runCatching {
            exec(listOf("ls", "-1", dir))?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 执行 `su -c "<args joined>"`，返回 stdout；失败返回 null。 */
    private fun exec(args: List<String>): String? {
        val cmd = "su -c " + shellQuote(args.joinToString(" "))
        return runCatching {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
            if (p.isAlive) p.destroy()
            out
        }.getOrNull()
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 模块目录 → KsuModuleInfo（root 模式）。 */
    fun loadModule(dir: String): KsuModuleInfo? {
        if (!isSafePath(dir)) return null
        val propText = readFile("$dir/module.prop") ?: return null
        val props = ModuleProp.parse(propText)
        val files = listFiles(dir)
        val scripts = files.filter { it in ScriptRisk.SCRIPT_NAMES }
        val disabled = files.contains("disable") || exists("$dir/disable")
        return ModuleProp.toInfo(
            props = props,
            dir = dir,
            enabled = !disabled,
            scripts = scripts,
        )
    }

    /** 读取模块脚本内容（只读）。 */
    fun readScripts(dir: String, names: List<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (n in names) {
            if (n !in ScriptRisk.SCRIPT_NAMES) continue
            readFile("$dir/$n")?.let { out[n] = it }
        }
        return out
    }

    /** 从 zip 文件读取 module.prop 与脚本（免 root 模式）。 */
    fun loadFromZip(zip: File): Pair<KsuModuleInfo?, Map<String, String>> {
        return runCatching {
            java.util.zip.ZipFile(zip).use { z ->
                val propEntry = z.entries().toList().firstOrNull { it.name == "module.prop" }
                val props = propEntry?.let { ModuleProp.parse(z.getInputStream(it).readBytes().toString(Charsets.UTF_8)) }
                    ?: emptyMap()

                val scripts = mutableMapOf<String, String>()
                for (e in z.entries()) {
                    val base = e.name.substringAfterLast('/')
                    if (base in ScriptRisk.SCRIPT_NAMES) {
                        scripts[base] = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                    }
                }
                val info = if (props.isEmpty()) null else ModuleProp.toInfo(props, scripts = scripts.keys.toList())
                info to scripts
            }
        }.getOrDefault(null to emptyMap())
    }
}
