package com.modradar.app.task.stages

import com.modradar.app.scan.apk.ApkReader
import com.modradar.app.scan.apk.ManifestAnalyzer
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
            val cert = runCatching { reader.firstSignerCertificate() }.getOrNull()
            val subject = cert?.subjectX500Principal?.name
            val sha256 = cert?.let {
                ArtifactStore.sha256(it.encoded)
            }

            ctx.checkPause()
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

            val artifact = ExtractArtifact(
                manifest = summary,
                abis = abis,
                dexEntries = dex,
                nativeLibs = libs,
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
                ksuModule = ksuModule,
                ksuScriptRisks = ksuRisks,
            )
            ctx.write(ctx.artifactName(stage), Json.encodeToString(artifact))
        }

        onProgress(100, "结构提取完成")
    }
}
