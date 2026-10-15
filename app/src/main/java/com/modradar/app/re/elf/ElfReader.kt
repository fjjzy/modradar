package com.modradar.app.re.elf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ELF 动态导入信息。 */
data class ElfInfo(
    val is64: Boolean,
    val machine: Int,
    /** 未定义的动态符号（即从其他库导入的函数/变量）。 */
    val imports: List<String>,
    /** DT_NEEDED 依赖库。 */
    val needed: List<String>,
)

/**
 * 最小 ELF 解析器：只读 section header → .dynsym / .dynstr / .dynamic。
 * 支持 32/64 位、大小端；任何越界都返回 null，不抛异常。
 */
object ElfReader {

    private const val SHT_DYNSYM = 11
    private const val SHT_DYNAMIC = 6
    private const val DT_NEEDED = 1L

    fun parse(bytes: ByteArray): ElfInfo? = runCatching { parseInternal(bytes) }.getOrNull()

    private fun parseInternal(b: ByteArray): ElfInfo? {
        if (b.size < 52) return null
        if (b[0] != 0x7F.toByte() || b[1] != 'E'.code.toByte() || b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()) return null
        val is64 = b[4].toInt() == 2
        val order = if (b[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val buf = ByteBuffer.wrap(b).order(order)

        val machine = buf.getShort(0x12).toInt() and 0xFFFF
        val shoff: Long
        val shentsize: Int
        val shnum: Int
        if (is64) {
            if (b.size < 64) return null
            shoff = buf.getLong(0x28)
            shentsize = buf.getShort(0x3A).toInt() and 0xFFFF
            shnum = buf.getShort(0x3C).toInt() and 0xFFFF
        } else {
            shoff = buf.getInt(0x20).toLong() and 0xFFFFFFFFL
            shentsize = buf.getShort(0x2E).toInt() and 0xFFFF
            shnum = buf.getShort(0x30).toInt() and 0xFFFF
        }
        if (shoff <= 0 || shnum == 0 || shentsize == 0) return null
        if (shoff + shnum.toLong() * shentsize > b.size) return null

        data class Sh(val type: Int, val offset: Long, val size: Long, val link: Int, val entsize: Long)

        val sections = (0 until shnum).map { i ->
            val o = (shoff + i.toLong() * shentsize).toInt()
            if (is64) Sh(
                type = buf.getInt(o + 4),
                offset = buf.getLong(o + 24),
                size = buf.getLong(o + 32),
                link = buf.getInt(o + 40),
                entsize = buf.getLong(o + 56),
            ) else Sh(
                type = buf.getInt(o + 4),
                offset = buf.getInt(o + 16).toLong() and 0xFFFFFFFFL,
                size = buf.getInt(o + 20).toLong() and 0xFFFFFFFFL,
                link = buf.getInt(o + 24),
                entsize = buf.getInt(o + 36).toLong() and 0xFFFFFFFFL,
            )
        }

        fun inBounds(s: Sh) = s.offset >= 0 && s.size >= 0 && s.offset + s.size <= b.size

        fun str(tab: Sh, off: Long): String? {
            if (off < 0 || off >= tab.size) return null
            val start = (tab.offset + off).toInt()
            val end = (tab.offset + tab.size).toInt()
            var i = start
            while (i < end && b[i] != 0.toByte()) i++
            return String(b, start, i - start, Charsets.UTF_8)
        }

        val imports = linkedSetOf<String>()
        sections.filter { it.type == SHT_DYNSYM && inBounds(it) }.forEach { sym ->
            val strtab = sections.getOrNull(sym.link)?.takeIf { inBounds(it) } ?: return@forEach
            val ent = if (sym.entsize > 0) sym.entsize else if (is64) 24L else 16L
            val count = (sym.size / ent).toInt()
            for (i in 1 until count) { // 0 号为空符号
                val o = (sym.offset + i * ent).toInt()
                val nameOff = buf.getInt(o).toLong() and 0xFFFFFFFFL
                val shndx = if (is64) buf.getShort(o + 6).toInt() and 0xFFFF else buf.getShort(o + 14).toInt() and 0xFFFF
                if (shndx != 0) continue
                str(strtab, nameOff)?.takeIf { it.isNotEmpty() }?.let { imports += it.substringBefore('@') }
            }
        }

        val needed = mutableListOf<String>()
        sections.filter { it.type == SHT_DYNAMIC && inBounds(it) }.forEach { dyn ->
            val strtab = sections.getOrNull(dyn.link)?.takeIf { inBounds(it) } ?: return@forEach
            val ent = if (is64) 16 else 8
            val count = (dyn.size / ent).toInt()
            for (i in 0 until count) {
                val o = (dyn.offset + i.toLong() * ent).toInt()
                val tag = if (is64) buf.getLong(o) else buf.getInt(o).toLong()
                val v = if (is64) buf.getLong(o + 8) else buf.getInt(o + 4).toLong() and 0xFFFFFFFFL
                if (tag == 0L) break
                if (tag == DT_NEEDED) str(strtab, v)?.let { needed += it }
            }
        }

        return ElfInfo(is64 = is64, machine = machine, imports = imports.toList(), needed = needed)
    }
}

/** native 能力判定（规则 C8 与反调试）。纯函数，便于单测。 */
object NativeCapability {

    val DYNLOAD = setOf("dlopen", "android_dlopen_ext")
    val MPROTECT = setOf("mprotect", "mmap")
    val EXEC = setOf("execve", "execv", "execvp", "execl", "execlp", "system", "popen", "posix_spawn", "fork")
    val NETWORK = setOf("socket", "connect", "getaddrinfo", "gethostbyname", "sendto", "recvfrom", "send", "recv")
    val ANTI_DEBUG = setOf("ptrace", "inotify_add_watch")

    data class Verdict(val c8: Boolean, val hits: List<String>, val antiDebug: List<String>)

    fun judge(imports: Collection<String>): Verdict {
        val s = imports.toSet()
        fun pick(g: Set<String>) = g.filter { it in s }
        val dl = pick(DYNLOAD)
        val mp = pick(setOf("mprotect"))
        val ex = pick(EXEC + "ptrace")
        val net = pick(NETWORK)
        val c8 = dl.isNotEmpty() && mp.isNotEmpty() && ex.isNotEmpty() && net.isNotEmpty()
        return Verdict(c8 = c8, hits = (dl + mp + ex + net).distinct(), antiDebug = pick(ANTI_DEBUG))
    }

    /** 只保留与安全判定相关的符号，避免产物过大。 */
    fun interesting(imports: Collection<String>): List<String> {
        val all = DYNLOAD + MPROTECT + EXEC + NETWORK + ANTI_DEBUG + setOf("dlsym", "fopen", "opendir", "kill", "prctl", "syscall")
        return imports.filter { it in all }.distinct().sorted()
    }
}
