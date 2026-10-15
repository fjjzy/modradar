package com.modradar.app.provenance

import com.modradar.app.scan.apk.ApkSigningBlock
import com.modradar.app.task.ArtifactStore
import com.modradar.app.task.stages.ProvenanceArtifact
import com.modradar.app.task.stages.ProvenanceArtifact.Companion as P
import com.modradar.app.task.stages.ReleaseRef
import com.modradar.app.task.stages.RepoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 联网来源核验：在 GitHub 上找目标的开源仓库与 release，判断是否官方原版。
 *
 * 找仓库（并发）：KSU updateJson → LSPosed 模块仓库（Xposed-Modules-Repo/包名）→ 包内 github 链接 → 名称搜索。
 * 比对：release 资产 sha256（GitHub digest）→ APK 用 HTTP Range 只取签名块比对证书 → 模块 zip 下载后逐文件比对。
 */
class ProvenanceChecker(
    http: OkHttpClient,
    private val api: String = "https://api.github.com",
    private val maxDownload: Long = 30L shl 20,
) {
    data class Input(
        val kind: String,
        val targetName: String,
        val packageName: String? = null,
        val versionName: String? = null,
        val ksuId: String? = null,
        val ksuName: String? = null,
        val ksuVersion: String? = null,
        val updateJson: String? = null,
        val file: File? = null,
        val fileSha256: String? = null,
        val signerSha256: String? = null,
        /** 包内字符串里出现的链接。 */
        val urls: List<String> = emptyList(),
        /** 本地文件是 root 从模块目录打包的（安装后脚本可能改过文件，内容差异只提示）。 */
        val packedFromDevice: Boolean = false,
    ) {
        val isKsu: Boolean get() = kind == "KSU_MODULE"
        val isXposed: Boolean get() = kind == "XPOSED_MODULE"
    }

    private val http = http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private data class Candidate(val fullName: String, val strength: String, val by: String, val info: RepoRef? = null)

    suspend fun check(input: Input, now: Long = System.currentTimeMillis()): ProvenanceArtifact = withContext(Dispatchers.IO) {
        val names = nameKeys(input)
        // 各来源并发查询；子任务内部吞掉异常，避免一个失败取消全部
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun <T> safe(block: () -> T?): T? = runCatching { block() }.onFailure { errors += it.message ?: it.javaClass.simpleName }.getOrNull()
        val cands = coroutineScope {
            val fromUpdate = async { safe { fromUpdateJson(input.updateJson) } }
            val fromLsposed = async {
                val pkg = input.packageName
                if (input.isXposed && pkg != null) safe {
                    repoInfo("Xposed-Modules-Repo/$pkg")?.let { Candidate(it.fullName, P.STRONG, "LSPosed 模块仓库", it) }
                } else null
            }
            val fromLinks = async { safe { fromLinks(input.urls, names) } }
            val byName = async { searchKeyword(input)?.let { kw -> safe { search("$kw in:name", names, "名称搜索") } } }
            val byPkg = async {
                input.packageName?.takeIf { !input.isKsu }?.let { pkg ->
                    safe { search("\"$pkg\" in:readme,description", emptyList(), "包名出现在仓库说明中") }
                }
            }
            listOfNotNull(fromUpdate.await(), fromLsposed.await()) + fromLinks.await().orEmpty() +
                byName.await().orEmpty() + byPkg.await().orEmpty()
        }
        val ranked = cands.distinctBy { it.fullName.lowercase() }
            .sortedWith(compareBy<Candidate> { strengthRank(it.strength) }.thenByDescending { it.info?.stars ?: 0 })
        val primary = ranked.firstOrNull()
            ?: return@withContext ProvenanceArtifact(
                status = if (errors.isNotEmpty() && cands.isEmpty()) P.FAILED else P.OK,
                error = errors.firstOrNull(),
                checkedAt = now,
                verdict = P.NOT_FOUND,
                detail = "未在 GitHub 找到对应的开源项目（闭源应用、改名发布或仓库已删除时常见）",
            )

        val (repo, releases) = coroutineScope {
            val r = async { primary.info ?: runCatching { repoInfo(primary.fullName) }.getOrNull() }
            val rel = async { runCatching { releases(primary.fullName) }.getOrDefault(emptyList()) }
            r.await() to rel.await()
        }
        val base = ProvenanceArtifact(
            checkedAt = now,
            repo = repo ?: RepoRef(primary.fullName, "https://github.com/${primary.fullName}"),
            matchedBy = primary.by,
            strength = primary.strength,
            others = ranked.drop(1).take(3).mapNotNull { it.info ?: RepoRef(it.fullName, "https://github.com/${it.fullName}") },
            latestTag = releases.firstOrNull()?.tag,
        )
        compare(input, base, releases)
    }

    private fun strengthRank(s: String) = when (s) { P.STRONG -> 0; P.MEDIUM -> 1; else -> 2 }

    // ---- 找仓库 ----

    /** 用于判断仓库名是否「像」目标的名字集合（已归一化）。 */
    internal fun nameKeys(input: Input): List<String> = listOfNotNull(
        input.ksuId, input.ksuName, cleanName(input.targetName),
        input.packageName?.substringAfterLast('.'), input.packageName,
    ).map(::norm).filter { it.length >= 3 }.distinct()

    private fun searchKeyword(input: Input): String? =
        (input.ksuName ?: input.ksuId ?: cleanName(input.targetName))
            ?.replace(Regex("[^\\p{L}\\p{N}_.-]+"), " ")?.trim()?.takeIf { it.length >= 2 }

    /** updateJson 本身或其中的 zipUrl 通常就是作者仓库地址。 */
    private fun fromUpdateJson(url: String?): Candidate? {
        if (url == null) return null
        repoOf(url)?.let { return Candidate(it, P.STRONG, "module.prop updateJson") }
        val o = getJson(url) as? JsonObject ?: return null
        val zip = o.str("zipUrl") ?: return null
        return repoOf(zip)?.let { Candidate(it, P.STRONG, "updateJson 中的 zipUrl") }
    }

    /** 包内字符串里的 github 链接：仓库名与目标相像才采用。 */
    private fun fromLinks(urls: List<String>, names: List<String>): List<Candidate> =
        urls.mapNotNull(::repoOf).distinct()
            .filter { similar(it.substringAfter('/'), names) }
            .take(2)
            .mapNotNull { r -> repoInfo(r)?.let { Candidate(it.fullName, P.MEDIUM, "包内链接", it) } }

    private fun search(q: String, names: List<String>, by: String): List<Candidate> {
        val url = "$api/search/repositories".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("sort", "stars")
            .addQueryParameter("order", "desc").addQueryParameter("per_page", "5").build().toString()
        val items = (getJson(url) as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { (it as? JsonObject)?.let(::toRepo) }
            .filter { names.isEmpty() || similar(it.fullName.substringAfter('/'), names) }
            .filter { !it.fork }
            .take(3)
            .map { Candidate(it.fullName, P.WEAK, by, it) }
    }

    private fun repoInfo(fullName: String): RepoRef? =
        (getJson("$api/repos/$fullName", allow404 = true) as? JsonObject)?.let(::toRepo)

    private fun toRepo(o: JsonObject): RepoRef? {
        val full = o.str("full_name") ?: return null
        return RepoRef(
            fullName = full,
            url = o.str("html_url") ?: "https://github.com/$full",
            stars = (o["stargazers_count"] as? JsonPrimitive)?.intOrNull ?: 0,
            description = o.str("description"),
            archived = (o["archived"] as? JsonPrimitive)?.booleanOrNull == true,
            fork = (o["fork"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    internal data class Asset(val name: String, val size: Long, val url: String, val digest: String?)
    internal data class Rel(val tag: String, val url: String, val publishedAt: String?, val assets: List<Asset>)

    private fun releases(fullName: String): List<Rel> {
        val arr = getJson("$api/repos/$fullName/releases?per_page=15", allow404 = true) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if ((o["draft"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            Rel(
                tag = o.str("tag_name") ?: return@mapNotNull null,
                url = o.str("html_url") ?: "https://github.com/$fullName/releases",
                publishedAt = o.str("published_at"),
                assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
                    val ao = a as? JsonObject ?: return@mapNotNull null
                    Asset(
                        name = ao.str("name") ?: return@mapNotNull null,
                        size = (ao["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
                        url = ao.str("browser_download_url") ?: return@mapNotNull null,
                        digest = ao.str("digest")?.removePrefix("sha256:")?.lowercase(),
                    )
                },
            )
        }
    }

    // ---- 比对 ----

    private fun compare(input: Input, base: ProvenanceArtifact, releases: List<Rel>): ProvenanceArtifact {
        val ext = if (input.isKsu) ".zip" else ".apk"
        val assets = releases.flatMap { r -> r.assets.filter { it.name.endsWith(ext, true) }.map { r to it } }
        if (assets.isEmpty()) {
            return base.copy(
                verdict = P.SOURCE_FOUND,
                detail = if (releases.isEmpty()) "找到仓库，但没有发布 release，无法比对" else "release 中没有 $ext 文件，无法比对",
            )
        }
        fun ref(r: Rel, a: Asset) = ReleaseRef(r.tag, r.url, a.name, r.publishedAt)

        // 1. 整包哈希（GitHub 为 release 资产提供 sha256 digest）
        input.fileSha256?.lowercase()?.let { sha ->
            assets.firstOrNull { it.second.digest == sha }?.let { (r, a) ->
                return base.copy(verdict = P.OFFICIAL, release = ref(r, a), detail = "文件 SHA-256 与官方 release ${r.tag} 的 ${a.name} 完全一致")
            }
        }
        val version = if (input.isKsu) input.ksuVersion else input.versionName
        val vMatch = version?.let { v -> assets.firstOrNull { (r, a) -> versionIn(v, r.tag) || versionIn(v, a.name) } }
        val versionNote = if (vMatch == null && version != null) "；本地版本 $version 未在近期 release 中找到（最新 ${releases.first().tag}）" else ""

        // 2. APK：签名证书跨版本稳定，取同版本（否则最新）资产的签名块比对
        if (!input.isKsu) {
            val (r, a) = vMatch ?: assets.first()
            val official = runCatching { remoteSigner(a) }.getOrNull()
                ?: return base.copy(verdict = P.SOURCE_FOUND, release = ref(r, a), detail = "未能读取官方 ${a.name} 的签名$versionNote")
            val local = input.signerSha256?.lowercase()
            return if (local == official) {
                base.copy(verdict = P.SAME_SIGNER, release = ref(r, a), officialSigner = official, detail = "签名证书与官方 release ${r.tag} 一致$versionNote")
            } else {
                base.copy(
                    verdict = P.SIGNER_MISMATCH, release = ref(r, a), officialSigner = official,
                    detail = "签名证书与官方 release ${r.tag} 不同：可能是修改版 / 重打包，也可能作者更换过证书$versionNote",
                )
            }
        }

        // 3. 模块 zip：同版本官方包下载后逐文件比对（已安装模块是从目录重新打包的，整包哈希必然不同）
        val (r, a) = vMatch ?: return base.copy(
            verdict = P.SOURCE_FOUND, release = assets.first().let { ref(it.first, it.second) },
            detail = "无法逐文件比对$versionNote",
        )
        if (a.size > maxDownload) return base.copy(verdict = P.SOURCE_FOUND, release = ref(r, a), detail = "官方包超过 ${maxDownload shr 20} MB，跳过下载比对")
        val local = input.file?.let { f -> f.inputStream().use { zipHashes(it) } }
            ?: return base.copy(verdict = P.SOURCE_FOUND, release = ref(r, a), detail = "本地文件不可读，无法比对")
        val remote = download(a.url) { zipHashes(it) }
        val common = local.keys.intersect(remote.keys).filter { !it.startsWith("META-INF/") }
        val diff = common.filter { local[it] != remote[it] }.sorted()
        return if (common.isNotEmpty() && diff.isEmpty()) {
            base.copy(verdict = P.OFFICIAL, release = ref(r, a), sameFiles = common.size, detail = "逐文件比对：${common.size} 个文件与官方 release ${r.tag} 完全一致")
        } else {
            base.copy(
                verdict = P.CONTENT_MISMATCH, release = ref(r, a), sameFiles = common.size - diff.size, diffFiles = diff.take(20),
                deviceCopy = input.packedFromDevice,
                detail = if (common.isEmpty()) "与官方 release ${r.tag} 没有同名文件，可能不是同一个模块"
                else "${diff.size} 个文件与官方 release ${r.tag} 不同" +
                    if (input.packedFromDevice) "（已安装模块的安装脚本可能改写过文件，需结合差异文件判断）" else "",
            )
        }
    }

    /** 只用 HTTP Range 取远端 APK 的尾部与签名块（通常 < 100 KB）。 */
    private fun remoteSigner(a: Asset): String? {
        if (a.size <= 0) return null
        val cert = ApkSigningBlock.read(a.size) { pos, n -> range(a.url, pos, n) }?.certificate ?: return null
        return ArtifactStore.sha256(cert.encoded).lowercase()
    }

    private fun range(url: String, pos: Long, n: Int): ByteArray {
        val req = Request.Builder().url(url).header("Range", "bytes=$pos-${pos + n - 1}").header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            check(resp.code == 206) { "服务器不支持分段下载（HTTP ${resp.code}）" }
            val bytes = resp.body?.bytes() ?: error("空响应")
            check(bytes.size == n) { "分段长度不符" }
            return bytes
        }
    }

    private fun <T> download(url: String, block: (java.io.InputStream) -> T): T {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "下载失败（HTTP ${resp.code}）" }
            val body = resp.body ?: error("空响应")
            check(body.contentLength() <= maxDownload) { "文件过大" }
            return block(body.byteStream())
        }
    }

    private fun getJson(url: String, allow404: Boolean = false): JsonElement? {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UA)
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404 && allow404) return null
            if (resp.code == 403 || resp.code == 429) error("GitHub API 限流（未登录每小时 60 次），请稍后重试")
            check(resp.isSuccessful) { "GitHub 请求失败（HTTP ${resp.code}）" }
            return json.parseToJsonElement(resp.body?.string().orEmpty())
        }
    }

    companion object {
        private const val UA = "ModRadar"
        private const val MAX_ZIP_BYTES = 200L shl 20

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

        internal fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9\\p{L}]"), "")

        /** 去掉扩展名与尾部版本号：WeType_UI_Enhanced-1.28.0.apk → WeType_UI_Enhanced。 */
        internal fun cleanName(s: String): String? =
            s.replace(Regex("\\.(apk|apks|zip)$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[-_ ]v?\\d+(\\.\\d+)+.*$", RegexOption.IGNORE_CASE), "")
                .trim().takeIf { it.isNotEmpty() }

        internal fun similar(repoName: String, names: List<String>): Boolean {
            val r = norm(repoName)
            if (r.length < 3) return false
            return names.any { n -> n == r || (minOf(n.length, r.length) >= 5 && (n.contains(r) || r.contains(n))) }
        }

        /** 版本号作为独立片段出现（1.2 不匹配 1.20）。 */
        internal fun versionIn(version: String, s: String): Boolean {
            val v = version.trim().lowercase().removePrefix("v")
            if (v.isEmpty()) return false
            return Regex("(?<![0-9a-z.])v?${Regex.escape(v)}(?![0-9])").containsMatchIn(s.lowercase())
        }

        /** github.com / raw.githubusercontent.com 链接 → owner/repo。 */
        internal fun repoOf(url: String): String? {
            val m = Regex("^https?://(?:www\\.)?(?:github\\.com|raw\\.githubusercontent\\.com)/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)")
                .find(url.trim()) ?: return null
            val owner = m.groupValues[1]
            val repo = m.groupValues[2].removeSuffix(".git")
            if (owner.lowercase() in RESERVED || repo.isEmpty()) return null
            return "$owner/$repo"
        }

        private val RESERVED = setOf("orgs", "settings", "features", "topics", "sponsors", "marketplace", "apps", "login", "about")

        /** zip 内每个文件的 sha256（跳过目录；总量上限防 zip 炸弹）。 */
        internal fun zipHashes(input: java.io.InputStream): Map<String, String> {
            val out = mutableMapOf<String, String>()
            var total = 0L
            ZipInputStream(input.buffered()).use { z ->
                val buf = ByteArray(16 * 1024)
                while (out.size < 5000) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val md = MessageDigest.getInstance("SHA-256")
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        check(total <= MAX_ZIP_BYTES) { "解压内容过大" }
                        md.update(buf, 0, n)
                    }
                    out[e.name.removePrefix("./").removePrefix("/")] = md.digest().joinToString("") { "%02x".format(it) }
                }
            }
            return out
        }
    }
}
