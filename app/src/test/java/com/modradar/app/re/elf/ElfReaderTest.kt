package com.modradar.app.re.elf

import com.modradar.app.task.stages.ExtractArtifact
import com.modradar.app.task.stages.NativeLibImports
import com.modradar.app.task.stages.ScoreRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ElfReaderTest {

    @Test fun rejectsNonElf() {
        assertNull(ElfReader.parse(ByteArray(10)))
        assertNull(ElfReader.parse("not an elf at all, just some text padding padding padding".toByteArray()))
    }

    @Test fun rejectsTruncatedHeader() {
        val b = ByteArray(64).also { it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte(); it[4] = 2; it[5] = 1 }
        assertNull(ElfReader.parse(b))
    }

    /** 用真实样本（LSPilot_1.1.2.apk）里的 .so 验证导入表解析。 */
    @Test fun parsesRealSharedObjects() {
        val apk = listOf(
            File(System.getProperty("user.home"), "Documents/Dsh/LSPilot_1.1.2.apk"),
            File("../LSPilot_1.1.2.apk"),
        ).firstOrNull { it.isFile }
        assumeTrue("缺少样本 LSPilot_1.1.2.apk，跳过", apk != null)
        ZipFile(apk!!).use { z ->
            val libs = z.entries().toList().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            assumeTrue("样本内无 native 库", libs.isNotEmpty())
            var parsed = 0
            var withImports = 0
            libs.forEach { e ->
                val info = ElfReader.parse(z.getInputStream(e).readBytes()) ?: return@forEach
                parsed++
                if ("arm64" in e.name || "x86_64" in e.name) assertTrue(info.is64)
                // 静态链接的 loader 没有 .dynsym，导入为空是正常的
                if (info.imports.isEmpty()) return@forEach
                withImports++
                if (e.name == "lib/arm64-v8a/libdexkit.so") {
                    // 与 llvm-readelf --dyn-syms / -d 的结果对照
                    assertEquals(61, info.imports.size)
                    assertEquals(listOf("libz.so", "liblog.so", "libm.so", "libdl.so", "libc.so"), info.needed)
                }
                assertTrue("${e.name} 应有 DT_NEEDED", info.needed.isNotEmpty())
                assertTrue("${e.name} 导入不应含版本后缀", info.imports.none { '@' in it })
            }
            assertTrue("至少解析一个 .so", parsed > 0)
            assertTrue("至少一个 .so 有动态导入", withImports > 0)
        }
    }

    @Test fun c8RequiresAllFourGroups() {
        assertTrue(NativeCapability.judge(listOf("dlopen", "mprotect", "execve", "connect")).c8)
        assertTrue(NativeCapability.judge(listOf("android_dlopen_ext", "mprotect", "ptrace", "getaddrinfo")).c8)
        assertFalse(NativeCapability.judge(listOf("dlopen", "mprotect", "execve")).c8)
        assertFalse(NativeCapability.judge(listOf("dlopen", "connect", "system")).c8)
        assertEquals(listOf("ptrace"), NativeCapability.judge(listOf("ptrace", "malloc")).antiDebug)
    }

    @Test fun scoreRulesApplyC8OnceAndMergeAntiDebug() {
        val ex = ExtractArtifact(
            v1Signed = true, apkSigningBlock = true,
            nativeImports = listOf(
                NativeLibImports("lib/arm64-v8a/a.so", interesting = listOf("dlopen", "mprotect", "execve", "connect"), c8 = true, antiDebug = listOf("ptrace")),
                NativeLibImports("lib/arm64-v8a/b.so", interesting = listOf("dlopen"), c8 = true),
            ),
        )
        val r = ScoreRules.evaluate(ex, null)
        assertEquals(1, r.findings.count { it.ruleId == "C8" })
        assertEquals(-20, r.findings.single { it.ruleId == "C8" }.deduction)
        assertEquals(-10, r.findings.single { it.ruleId == "ANTIDEBUG" }.deduction)
        assertEquals(70, r.score)
    }
}
