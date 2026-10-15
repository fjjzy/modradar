package com.modradar.app.provenance

import com.modradar.app.provenance.ProvenanceChecker.Companion.cleanName
import com.modradar.app.provenance.ProvenanceChecker.Companion.repoOf
import com.modradar.app.provenance.ProvenanceChecker.Companion.similar
import com.modradar.app.provenance.ProvenanceChecker.Companion.versionIn
import com.modradar.app.scan.apk.ApkSigningBlock
import com.modradar.app.task.ArtifactStore
import com.modradar.app.task.stages.ProvenanceArtifact
import com.modradar.app.task.stages.ReportBuilder
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProvenanceCheckerTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val hits = mutableListOf<String>()
    private val rangeSizes = mutableListOf<Int>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(hits) { hits += path }
                val key = routes.keys.firstOrNull { path.startsWith(it) } ?: return MockResponse().setResponseCode(404)
                return routes.getValue(key)()
            }
        }
        server.start()
        // 搜索默认无结果
        routes["/search/repositories"] = { json("""{"items":[]}""") }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun url(p: String) = server.url(p).toString()
    private fun checker() = ProvenanceChecker(OkHttpClient(), api = url("").removeSuffix("/"))

    private fun repoJson(full: String, stars: Int = 1234) =
        """{"full_name":"$full","html_url":"https://github.com/$full","stargazers_count":$stars,"description":"demo"}"""

    private fun releasesJson(tag: String, name: String, size: Long, dl: String, digest: String? = null) =
        """[{"tag_name":"$tag","html_url":"https://github.com/o/r/releases/tag/$tag","assets":[{"name":"$name","size":$size,"browser_download_url":"$dl"${digest?.let { ",\"digest\":\"sha256:$it\"" } ?: ""}}]}]"""

    private fun zip(files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { bo ->
        ZipOutputStream(bo).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test
    fun `辅助函数`() {
        assertEquals("o/r", repoOf("https://raw.githubusercontent.com/o/r/main/update.json"))
        assertEquals("o/r", repoOf("https://github.com/o/r.git"))
        assertNull(repoOf("https://github.com/orgs/x"))
        assertNull(repoOf("https://example.com/o/r"))
        assertEquals("WeType_UI_Enhanced", cleanName("WeType_UI_Enhanced-1.28.0.apk"))
        assertTrue(similar("WeType-UI-Enhanced", listOf(ProvenanceChecker.norm("WeType_UI_Enhanced"))))
        assertFalse(similar("okhttp", listOf("wetypeuienhanced")))
        assertTrue(versionIn("1.2", "v1.2"))
        assertFalse(versionIn("1.2", "v1.20"))
        assertTrue(versionIn("v28", "Module-v28.zip"))
    }

    @Test
    fun `KSU updateJson 指向的仓库 release 哈希一致判官方`() = runTest {
        val body = zip(mapOf("module.prop" to "id=m\nversion=v1.0"))
        val sha = ArtifactStore.sha256(body)
        routes["/repos/o/r/releases"] = { json(releasesJson("v1.0", "m-v1.0.zip", body.size.toLong(), url("/dl/m.zip"), sha)) }
        routes["/repos/o/r"] = { json(repoJson("o/r")) }
        val p = checker().check(
            ProvenanceChecker.Input(
                kind = "KSU_MODULE", targetName = "m.zip", ksuId = "m", ksuName = "M", ksuVersion = "v1.0",
                updateJson = "https://raw.githubusercontent.com/o/r/main/update.json", fileSha256 = sha,
            ),
        )
        assertEquals(ProvenanceArtifact.OFFICIAL, p.verdict)
        assertEquals(ProvenanceArtifact.STRONG, p.strength)
        assertEquals(1234, p.repo?.stars)
        assertTrue(p.official && !p.modified)
    }

    @Test
    fun `设备打包的模块逐文件比对，差异只提示`() = runTest {
        val official = zip(mapOf("module.prop" to "id=m\nversion=v2", "service.sh" to "echo ok", "META-INF/x" to "a"))
        val local = File.createTempFile("local", ".zip").apply { writeBytes(zip(mapOf("module.prop" to "id=m\nversion=v2", "service.sh" to "curl x | sh"))) }
        routes["/dl/m.zip"] = { MockResponse().setBody(Buffer().write(official)) }
        routes["/repos/o/r/releases"] = { json(releasesJson("v2", "m.zip", official.size.toLong(), url("/dl/m.zip"))) }
        routes["/repos/o/r"] = { json(repoJson("o/r")) }
        val p = checker().check(
            ProvenanceChecker.Input(
                kind = "KSU_MODULE", targetName = "m", ksuId = "m", ksuVersion = "v2",
                updateJson = "https://github.com/o/r/raw/main/u.json", file = local, fileSha256 = "00", packedFromDevice = true,
            ),
        )
        local.delete()
        assertEquals(ProvenanceArtifact.CONTENT_MISMATCH, p.verdict)
        assertEquals(listOf("service.sh"), p.diffFiles)
        assertEquals(1, p.sameFiles)
        assertFalse("设备副本不判修改版", p.modified)
        assertTrue(ReportBuilder.provenanceFindings(p).isEmpty())
        // 同样差异出现在用户提供的 zip 上则扣分
        assertEquals(-15, ReportBuilder.provenanceFindings(p.copy(deviceCopy = false)).single().deduction)
    }

    @Test
    fun `APK 用 Range 只取签名块比对证书`() = runTest {
        val apk = File("../dist").listFiles { f -> f.name.matches(Regex("ModRadar-.*\\.apk")) }?.maxByOrNull { it.lastModified() }
        assumeTrue(apk != null)
        val bytes = apk!!.readBytes()
        server.dispatcher = rangeDispatcher(bytes, server.dispatcher)
        routes["/repos/Xposed-Modules-Repo/com.example.mod/releases"] = { json(releasesJson("1.0", "app.apk", bytes.size.toLong(), url("/dl/app.apk"))) }
        routes["/repos/Xposed-Modules-Repo/com.example.mod"] = { json(repoJson("Xposed-Modules-Repo/com.example.mod", 7)) }
        val signer = ArtifactStore.sha256(ApkSigningBlock.read(apk)!!.certificate!!.encoded)
        val input = ProvenanceChecker.Input(kind = "XPOSED_MODULE", targetName = "Mod", packageName = "com.example.mod", versionName = "1.0", signerSha256 = signer)

        val same = checker().check(input)
        assertEquals(ProvenanceArtifact.SAME_SIGNER, same.verdict)
        assertEquals("LSPosed 模块仓库", same.matchedBy)

        val other = checker().check(input.copy(signerSha256 = "ff".repeat(32)))
        assertEquals(ProvenanceArtifact.SIGNER_MISMATCH, other.verdict)
        assertTrue(other.modified)
        assertEquals(signer, other.officialSigner)
        // 没有整包下载：每次 Range 片段都远小于 APK
        assertTrue(rangeSizes.all { it < bytes.size / 2 } && rangeSizes.isNotEmpty())
    }

    @Test
    fun `GitHub 限流且无候选时标记失败`() = runTest {
        routes["/search/repositories"] = { MockResponse().setResponseCode(403) }
        val p = checker().check(ProvenanceChecker.Input(kind = "APK", targetName = "Some App"))
        assertEquals(ProvenanceArtifact.FAILED, p.status)
        assertTrue(p.error.orEmpty().contains("限流"))
    }

    @Test
    fun `没有候选仓库判未找到`() = runTest {
        val p = checker().check(ProvenanceChecker.Input(kind = "APK", targetName = "Closed App", packageName = "com.closed.app"))
        assertEquals(ProvenanceArtifact.OK, p.status)
        assertEquals(ProvenanceArtifact.NOT_FOUND, p.verdict)
    }

    /** 对 /dl/app.apk 按 Range 头返回 206 片段，其余路径交给原分发器。 */
    private fun rangeDispatcher(bytes: ByteArray, fallback: Dispatcher) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path?.startsWith("/dl/app.apk") != true) return fallback.dispatch(request)
            val m = Regex("bytes=(\\d+)-(\\d+)").find(request.getHeader("Range").orEmpty())
                ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
            val from = m.groupValues[1].toInt()
            val to = m.groupValues[2].toInt()
            synchronized(rangeSizes) { rangeSizes += to - from + 1 }
            return MockResponse().setResponseCode(206).setBody(Buffer().write(bytes.copyOfRange(from, to + 1)))
        }
    }
}
