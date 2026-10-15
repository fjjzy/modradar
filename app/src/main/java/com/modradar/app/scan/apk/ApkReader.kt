package com.modradar.app.scan.apk

import java.io.File
import java.util.zip.ZipFile

/** APK / 模块 zip 的只读访问层。 */
class ApkReader(private val file: File) : AutoCloseable {

    private val zip = ZipFile(file)

    val entryNames: List<String> = zip.entries().toList().map { it.name }

    fun has(name: String): Boolean = zip.getEntry(name) != null

    /** 条目解压后大小；未知返回 -1。 */
    fun size(name: String): Long = zip.getEntry(name)?.size ?: -1L

    fun read(name: String): ByteArray? = try {
        val e = zip.getEntry(name)
        if (e == null) null else zip.getInputStream(e).use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }

    val manifestBytes: ByteArray? get() = read("AndroidManifest.xml")

    val dexEntries: List<String> get() = entryNames
        .filter { DEX_NAME.matches(it) }
        .sorted()

    val nativeLibs: List<String> get() = entryNames.filter { it.startsWith("lib/") && it.endsWith(".so") }

    val abis: List<String> get() = nativeLibs.mapNotNull { it.split('/').getOrNull(1) }.distinct().sorted()

    val assetEntries: List<String> get() = entryNames.filter { it.startsWith("assets/") && !it.endsWith("/") }

    val hasKsuModuleProp: Boolean get() = has("module.prop")

    /** 签名相关文件（v1 签名）。 */
    val signatureFiles: List<String> get() = entryNames.filter { SIG_NAME.matches(it) }

    /** 是否含加固/壳特征文件。 */
    fun shellHints(): List<String> {
        val hints = mutableListOf<String>()
        val patterns = listOf(
            "lib/.*libDexHelper.*", "lib/.*libDexHelper-x86.*", "lib/.*libjiagu.*", "lib/.*libshell.*",
            "lib/.*libexec.*", "lib/.*libprotectClass.*", "lib/.*libtup.*", "lib/.*libSecShell.*",
            "assets/.*libjiagu.*", "assets/.*ijiami.*", "assets/.*dexhelper.*", "assets/.*stub.*",
            "assets/.*classes\\.dex.*", "assets/.*\\.[0-9]{3}\\.dat", "assets/.*\\.bin",
        )
        for (name in entryNames) {
            for (p in patterns) if (Regex(p, RegexOption.IGNORE_CASE).matches(name)) {
                hints.add(name); break
            }
        }
        return hints.distinct()
    }

    /** APK Signing Block（v2/v3 签名）是否存在：位于中央目录之前，末尾 16 字节为固定 magic。 */
    fun hasApkSigningBlock(): Boolean = runCatching {
        val len = file.length()
        if (len < 32) return false
        java.io.RandomAccessFile(file, "r").use { raf ->
            val tailSize = minOf(len, 65_536L + 22L).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(len - tailSize)
            raf.readFully(tail)

            var eocd = -1
            for (i in tail.size - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                ) {
                    eocd = i; break
                }
            }
            if (eocd < 0) return false

            val cdOffset = (tail[eocd + 16].toLong() and 0xFF) or
                ((tail[eocd + 17].toLong() and 0xFF) shl 8) or
                ((tail[eocd + 18].toLong() and 0xFF) shl 16) or
                ((tail[eocd + 19].toLong() and 0xFF) shl 24)
            if (cdOffset < 32) return false

            val magic = ByteArray(16)
            raf.seek(cdOffset - 16)
            raf.readFully(magic)
            String(magic, Charsets.US_ASCII) == "APK Sig Block 42"
        }
    }.getOrDefault(false)

    /** 取 v1 签名文件里的第一张证书（PKCS7 → X509）。 */
    fun firstSignerCertificate(): java.security.cert.X509Certificate? = runCatching {
        val name = signatureFiles.firstOrNull {
            it.endsWith(".RSA", true) || it.endsWith(".DSA", true) || it.endsWith(".EC", true)
        } ?: return null
        val bytes = read(name) ?: return null
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        cf.generateCertificates(java.io.ByteArrayInputStream(bytes))
            .firstOrNull() as? java.security.cert.X509Certificate
    }.getOrNull()

    override fun close() {
        runCatching { zip.close() }
    }

    companion object {
        private val DEX_NAME = Regex("classes\\d*\\.dex")
        private val SIG_NAME = Regex("META-INF/.*\\.(RSA|DSA|EC|SF)", RegexOption.IGNORE_CASE)
    }
}
