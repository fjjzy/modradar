package com.modradar.app.scan.apk

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApkSigningBlockTest {

    @Test
    fun `未知 ID 与截断数据不抛异常`() {
        val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(12).putInt(0x42726577).putLong(0) // 未知 pair
        b.putInt(0x7fffffff) // 残缺尾巴
        b.flip()
        val r = ApkSigningBlock.parsePairs(b)
        assertTrue(r.schemes.isEmpty())
    }

    /** 本仓库构建产物只有 v2/v3 签名（minSdk 24 起 AGP 不写 v1）。 */
    @Test
    fun `读取本地构建 APK 的 v2+ 证书`() {
        val apk = File("../dist").listFiles { f -> f.name.matches(Regex("ModRadar-.*\\.apk")) }
            ?.maxByOrNull { it.lastModified() }
        assumeTrue(apk != null)
        val r = ApkSigningBlock.read(apk!!)
        assertNotNull(r)
        assertTrue(r!!.schemes.any { it == "v2" || it == "v3" })
        assertNotNull(r.certificate)
    }
}
