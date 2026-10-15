package com.modradar.app.scan.apk

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * APK Signing Block（v2 / v3 / v3.1）只读解析：取出存在的方案和第一位签名者的证书。
 * 只读证书，不校验摘要（校验需要重算整包哈希，交给系统安装器）。
 */
object ApkSigningBlock {

    data class Result(val schemes: List<String>, val certificate: X509Certificate?)

    private const val ID_V2 = 0x7109871a
    private const val ID_V3 = 0xf05368c0.toInt()
    private const val ID_V31 = 0x1b93ad61
    private const val MAX_BLOCK = 16L * 1024 * 1024

    fun read(file: File): Result? = RandomAccessFile(file, "r").use { raf ->
        read(raf.length()) { pos, n -> ByteArray(n).also { raf.seek(pos); raf.readFully(it) } }
    }

    /**
     * 通用读取：只需按偏移取字节，便于对远端 release 用 HTTP Range 只拉签名块（几十 KB），不下载整包。
     */
    fun read(len: Long, readAt: (pos: Long, n: Int) -> ByteArray): Result? {
        if (len < 32) return null
        val tailSize = minOf(len, 65_536L + 22L).toInt()
        val tail = readAt(len - tailSize, tailSize)
        val eocd = (tail.size - 22 downTo 0).firstOrNull {
            tail[it] == 0x50.toByte() && tail[it + 1] == 0x4b.toByte() && tail[it + 2] == 0x05.toByte() && tail[it + 3] == 0x06.toByte()
        } ?: return null
        val cdOffset = ByteBuffer.wrap(tail, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        if (cdOffset < 32 || cdOffset > len) return null

        // 尾部：u64 块大小 + 16 字节 magic
        val footer = readAt(cdOffset - 24, 24)
        if (String(footer, 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return null
        val blockSize = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (blockSize < 24 || blockSize > MAX_BLOCK || blockSize + 8 > cdOffset) return null

        val block = readAt(cdOffset - blockSize - 8 + 8, (blockSize - 24).toInt())
        return parsePairs(ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN))
    }

    /** 解析 ID-value 对序列；可单测。 */
    internal fun parsePairs(buf: ByteBuffer): Result {
        val schemes = mutableListOf<String>()
        var cert: X509Certificate? = null
        while (buf.remaining() >= 12) {
            val pairLen = buf.long
            if (pairLen < 4 || pairLen > buf.remaining()) break
            val start = buf.position()
            val id = buf.int
            val name = when (id) { ID_V2 -> "v2"; ID_V3 -> "v3"; ID_V31 -> "v3.1"; else -> null }
            if (name != null) {
                schemes += name
                if (cert == null) {
                    val value = slice(buf, start + 4, (pairLen - 4).toInt())
                    cert = runCatching { firstCert(value) }.getOrNull()
                }
            }
            buf.position(start + pairLen.toInt())
        }
        return Result(schemes.distinct(), cert)
    }

    // v2/v3 共同结构：signers[ signer{ signedData{ digests, certificates[...], ... }, ... } ]
    private fun firstCert(value: ByteBuffer): X509Certificate? {
        val signers = prefixed(value)
        val signer = prefixed(signers)
        val signedData = prefixed(signer)
        prefixed(signedData) // digests
        val certs = prefixed(signedData)
        val der = prefixed(certs)
        val bytes = ByteArray(der.remaining()).also { der.get(it) }
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(bytes.inputStream()) as? X509Certificate
    }

    private fun prefixed(buf: ByteBuffer): ByteBuffer {
        val n = buf.int
        require(n in 0..buf.remaining()) { "bad length" }
        val out = slice(buf, buf.position(), n)
        buf.position(buf.position() + n)
        return out
    }

    private fun slice(buf: ByteBuffer, pos: Int, n: Int): ByteBuffer {
        val d = buf.duplicate()
        d.position(pos)
        d.limit(pos + n)
        return d.slice().order(ByteOrder.LITTLE_ENDIAN)
    }
}
