package com.modradar.app.task.stages

import com.modradar.app.scan.apk.ApkReader
import com.modradar.app.scan.apk.ApkSigningBlock
import com.modradar.app.scan.apk.ManifestAnalyzer
import com.modradar.app.re.elf.ElfReader
import com.modradar.app.re.elf.NativeCapability
import com.modradar.app.task.ArtifactStore
import com.modradar.app.task.Stage
import com.modradar.app.task.StageContext
import com.modradar.app.task.StageRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** S2 结构提取：清单、权限、组件暴露、签名文件、ABI、DEX、加固特征。 */
class ExtractStage : StageRunner {

    override val stage: Stage = Stage.EXTRACT

    override suspend fun run(
        ctx: StageContext,
        onProgress: (Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val file = File(ctx.targetPath)

        onProgress(10, "解析 AndroidManifest")
        ctx.checkPause()

        var parseError: String? = null
        var summary: ManifestSummary? = null
        var isXposed = false
        var xposedMin: String? = null
        var xposedDesc: String? = null

        ApkReader(file).use { reader ->
            val bytes = reader.manifestBytes
            if (bytes == null) {
                parseError = "包内无 AndroidManifest.xml"
            } else {
                val info = ManifestAnalyzer.analyze(bytes)
                if (info == null) {
                    parseError = "清单解析失败（AXML 格式异常）"
                } else {
                    isXposed = info.isXposedModule
                    xposedMin = info.xposedMinVersion
                    xposedDesc = info.xposedDescription
                    summary = ManifestSummary(
                        packageName = info.packageName,
                        versionName = info.versionName,
                        versionCode = info.versionCode,
                        minSdk = info.minSdk,
                        targetSdk = info.targetSdk,
                        permissions = info.permissions,
                        debuggable = info.debuggable,
                        allowBackup = info.allowBackup,
                        usesCleartextTraffic = info.usesCleartextTraffic,
                        sharedUserId = info.sharedUserId,
                        applicationName = info.applicationName,
                        componentCount = info.components.size,
                        exportedWithoutPermission = info.exportedComponentsWithoutPermission
                            .map { "${it.type}:${it.name}" },
                        metaData = info.metaData,
                    )
                }
            }

            // 新式模块（libxposed API 100+）：meta-data 为空，声明在 META-INF/xposed/
            if (reader.has("META-INF/xposed/java_init.list") || reader.has("assets/xposed_init")) {
                isXposed = true
                val prop = reader.read("META-INF/xposed/module.prop")?.toString(Charsets.UTF_8).orEmpty()
                    .lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                    .toMap()
                if (xposedMin == null) xposedMin = prop["minApiVersion"]
                val scope = reader.read("META-INF/xposed/scope.list")?.toString(Charsets.UTF_8).orEmpty()
                    .lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (xposedDesc == null && scope.isNotEmpty()) {
                    xposedDesc = "libxposed API 模块 · 作用域 ${scope.take(8).joinToString("、")}" +
                        if (scope.size > 8) " 等 ${scope.size} 个" else ""
                }
            }

            ctx.checkPause()
            onProgress(55, "扫描包内文件与加固特征")

            val abis = reader.abis
            val dex = reader.dexEntries
            val libs = reader.nativeLibs
            val sigs = reader.signatureFiles
            val hints = reader.shellHints()

            ctx.checkPause()
            onProgress(80, "校验签名")

            val v1Signed = sigs.any {
                it.endsWith(".RSA", true) || it.endsWith(".DSA", true) || it.endsWith(".EC", true)
            }
            val signingBlock = runCatching { reader.hasApkSigningBlock() }.getOrDefault(false)
            val schemes = runCatching { ApkSigningBlock.read(file) }.getOrNull()
            val cert = runCatching { reader.firstSignerCertificate() }.getOrNull() ?: schemes?.certificate
            val subject = cert?.subjectX500Principal?.name
            val sha256 = cert?.let {
                ArtifactStore.sha256(it.encoded)
            }

            ctx.checkPause()
            val lspatch = detectLsPatch(reader, summary, ctx)

            onProgress(88, "解析模块元数据")

            // KSU / Magisk 模块：无 AndroidManifest，改读 module.prop 与脚本
            var ksuModule: com.modradar.app.scan.ksu.KsuModuleInfo? = null
            var ksuRisks: List<String> = emptyList()
            if (reader.hasKsuModuleProp) {
                val (info, scripts) = com.modradar.app.scan.ksu.RootShell.loadFromZip(file)
                ksuModule = info
                ksuRisks = com.modradar.app.scan.ksu.ScriptRisk.scanAll(scripts)
                    .flatMap { (name, hits) -> hits.map { "$name: ${it.label}" } }
                    .distinct()
                if (info == null) parseError = "module.prop 解析失败"
                else if (parseError == "包内无 AndroidManifest.xml") parseError = null
            }

            ctx.checkPause()
            onProgress(92, "解析 native 导入表")
            // split APK（已安装应用导入时一并复制）：native 库通常在 split_config.<abi>.apk
            val splits = File(ctx.targetPath + ".splits").listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
            val splitLibs = mutableListOf<String>()
            val splitAbis = mutableListOf<String>()
            val nativeImports = scanNative(reader, libs, abis) + splits.flatMap { sf ->
                runCatching {
                    ApkReader(sf).use { sr ->
                        splitLibs += sr.nativeLibs.map { "${sf.name}!$it" }
                        splitAbis += sr.abis
                        scanNative(sr, sr.nativeLibs, sr.abis).map { it.copy(path = "${sf.name}!${it.path}") }
                    }
                }.getOrDefault(emptyList())
            }

            ctx.checkPause()

            val artifact = ExtractArtifact(
                manifest = summary,
                abis = (abis + splitAbis).distinct().sorted(),
                dexEntries = dex,
                nativeLibs = libs + splitLibs,
                signatureFiles = sigs,
                shellHints = hints,
                assetCount = reader.assetEntries.size,
                isXposedModule = isXposed,
                xposedMinVersion = xposedMin,
                xposedDescription = xposedDesc,
                parseError = parseError,
                v1Signed = v1Signed,
                apkSigningBlock = signingBlock,
                signerSubject = subject,
                signerSha256 = sha256,
                signatureSchemes = listOfNotNull("v1".takeIf { v1Signed }) + schemes?.schemes.orEmpty(),
                ksuModule = ksuModule,
                ksuScriptRisks = ksuRisks,
                nativeImports = nativeImports,
                lspatch = lspatch,
            )
            ctx.write(ctx.artifactName(stage), Json.encodeToString(artifact))
        }

        onProgress(100, "结构提取完成")
    }

    /**
     * LSPatch 特征：assets/lspatch/（loader、config、内嵌模块、原包）或 meta-data lspatch。
     * 原包存在时读取其签名，用于判断「签名已被替换」。
     */
    private fun detectLsPatch(reader: ApkReader, manifest: ManifestSummary?, ctx: StageContext): LsPatchInfo? {
        val entries = reader.entryNames.filter { it.startsWith("assets/lspatch/") && !it.endsWith("/") }
        val meta = manifest?.metaData?.keys.orEmpty().filter { it.contains("lspatch", true) }
        val app = manifest?.applicationName?.takeIf { it.contains("lspatch", true) }
        if (entries.isEmpty() && meta.isEmpty() && app == null) return null

        val modules = entries.filter { it.startsWith("assets/lspatch/modules/") }.map { it.substringAfterLast('/') }
        val origin = entries.firstOrNull { it.substringAfterLast('/').startsWith("origin") }
        var originSha: String? = null
        var originSubject: String? = null
        if (origin != null && reader.size(origin) in 1..(150L shl 20)) {
            runCatching {
                val tmp = File(ctx.cacheDir, "lspatch_origin.apk")
                reader.read(origin)?.let { tmp.writeBytes(it) }
                val cert = ApkReader(tmp).use { it.firstSignerCertificate() } ?: ApkSigningBlock.read(tmp)?.certificate
                originSha = cert?.let { ArtifactStore.sha256(it.encoded) }
                originSubject = cert?.subjectX500Principal?.name
                tmp.delete()
            }
        }
        return LsPatchInfo(
            modules = modules,
            originSignerSha256 = originSha,
            originSignerSubject = originSubject,
            evidence = (entries.take(8) + meta.map { "meta-data: $it" } + listOfNotNull(app?.let { "application: $it" })),
        )
    }

    /** 只扫一个 ABI（优先 arm64-v8a）避免重复；单库上限 64MB。 */
    private fun scanNative(reader: ApkReader, libs: List<String>, abis: List<String>): List<NativeLibImports> {
        val abi = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").firstOrNull { it in abis } ?: abis.firstOrNull()
            ?: return emptyList()
        return libs.filter { it.startsWith("lib/$abi/") }.take(MAX_LIBS).mapNotNull { path ->
            if (reader.size(path) > MAX_LIB_BYTES) return@mapNotNull NativeLibImports(path, importCount = -1)
            val bytes = reader.read(path) ?: return@mapNotNull null
            val info = ElfReader.parse(bytes) ?: return@mapNotNull NativeLibImports(path, importCount = -1)
            val v = NativeCapability.judge(info.imports)
            NativeLibImports(
                path = path,
                is64 = info.is64,
                importCount = info.imports.size,
                interesting = NativeCapability.interesting(info.imports),
                needed = info.needed,
                c8 = v.c8,
                antiDebug = v.antiDebug,
            )
        }
    }

    private companion object {
        const val MAX_LIBS = 40
        const val MAX_LIB_BYTES = 64L * 1024 * 1024
    }
}
