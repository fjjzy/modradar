package com.modradar.app.task.stages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SOURCE / TRANSFORM / SINK 分类：能力链判定的基础，必须准确。 */
class ApiSignaturesTest {

    private fun classify(cls: String, name: String) = ApiSignatures.classifyMethod(cls, name)

    @Test
    fun `OkHttp 归为 SOURCE`() {
        val (s, _, k) = classify("Lokhttp3/OkHttpClient;", "newCall")
        assertTrue(s.contains("OkHttp"))
        assertTrue(k.isEmpty())
    }

    @Test
    fun `DexClassLoader 归为 SINK`() {
        val (_, _, k) = classify("Ldalvik/system/DexClassLoader;", "<init>")
        assertTrue(k.contains("DexClassLoader"))
    }

    @Test
    fun `Runtime exec 归为 SINK`() {
        val (_, _, k) = classify("Ljava/lang/Runtime;", "exec")
        assertTrue(k.contains("Runtime.exec"))
    }

    @Test
    fun `Base64 归为 TRANSFORM`() {
        val (_, t, _) = classify("Landroid/util/Base64;", "decode")
        assertTrue(t.contains("Base64"))
    }

    @Test
    fun `Cipher 归为 TRANSFORM 且不是 SINK`() {
        val (_, t, k) = classify("Ljavax/crypto/Cipher;", "doFinal")
        assertTrue(t.contains("Cipher"))
        assertFalse(k.contains("Cipher"))
    }

    @Test
    fun `System loadLibrary 归为 SINK`() {
        val (_, _, k) = classify("Ljava/lang/System;", "loadLibrary")
        assertTrue(k.contains("System.loadLibrary"))
    }

    @Test
    fun `普通方法不命中任何分类`() {
        val (s, t, k) = classify("Ljava/lang/String;", "length")
        assertTrue(s.isEmpty())
        assertTrue(t.isEmpty())
        assertTrue(k.isEmpty())
    }

    @Test
    fun `同一方法可同时命中多类`() {
        val (s, _, k) = classify("Lokhttp3/OkHttpClient;", "newCall")
        val sinkHit = classify("Ljava/lang/Runtime;", "exec").third
        assertTrue(s.isNotEmpty())
        assertTrue(sinkHit.isNotEmpty())
        assertEquals(1, sinkHit.size)
    }
}
