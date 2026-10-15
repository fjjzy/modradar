package com.modradar.app.scan.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestAnalyzerTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("axml/$name")!!.readBytes()

    @Test
    fun `解析 AGP 产出的 UTF-16 清单`() {
        val m = ManifestAnalyzer.analyze(fixture("modradar_manifest.bin"))
        assertNotNull(m)
        assertEquals("com.modradar.app", m!!.packageName)
        assertEquals("0.3.0", m.versionName)
        assertEquals(3L, m.versionCode)
        assertEquals(24, m.minSdk)
        assertEquals(34, m.targetSdk)
        assertTrue(m.permissions.isNotEmpty())
        assertTrue(m.components.any { it.name.endsWith("MainActivity") })
    }

    @Test
    fun `解析新式 libxposed 模块清单`() {
        val m = ManifestAnalyzer.analyze(fixture("wetype_manifest.bin"))
        assertNotNull(m)
        assertEquals("com.xposed.wetypehook", m!!.packageName)
        assertEquals("com.xposed.wetypehook.ModuleApplication", m.applicationName)
        assertTrue(m.components.any { it.name == "io.github.libxposed.service.XposedProvider" })
        // 新式模块不写 xposedmodule meta-data，需结合 META-INF/xposed 判断
        assertFalse(m.isXposedModule)
    }

    @Test
    fun `截断或垃圾数据不抛异常`() {
        val b = fixture("modradar_manifest.bin")
        ManifestAnalyzer.analyze(b.copyOf(b.size / 3))
        ManifestAnalyzer.analyze(ByteArray(64) { it.toByte() })
    }
}
