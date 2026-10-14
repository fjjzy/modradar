package com.modradar.app.task.stages

import com.modradar.app.scan.apk.ApkReader
import com.modradar.app.scan.apk.ManifestAnalyzer
import com.modradar.app.scan.model.TargetKind
import com.modradar.app.task.Stage
import com.modradar.app.task.StageContext
import com.modradar.app.task.StageRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** S1 装载：识别类型、计算哈希、建立包结构视图。 */
class LoadStage : StageRunner {

    override val stage: Stage = Stage.LOAD

    override suspend fun run(
        ctx: StageContext,
        onProgress: (Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val file = File(ctx.targetPath)
        if (!file.isFile) throw IOException("目标文件不存在：${ctx.targetPath}")

        onProgress(5, "计算文件哈希")
        ctx.checkPause()
        val sha = sha256File(file)

        onProgress(45, "读取包结构")
        ctx.checkPause()

        var entryCount = 0
        var hasManifest = false
        var hasModuleProp = false
        var isZip = true
        var kind = TargetKind.UNKNOWN
        var isXposed = false

        try {
            ApkReader(file).use { reader ->
                entryCount = reader.entryNames.size
                hasManifest = reader.has("AndroidManifest.xml")
                hasModuleProp = reader.hasKsuModuleProp

                isXposed = reader.manifestBytes
                    ?.let { ManifestAnalyzer.analyze(it)?.isXposedModule }
                    ?: false

                kind = when {
                    hasModuleProp -> TargetKind.KSU_MODULE
                    isXposed -> TargetKind.XPOSED_MODULE
                    hasManifest -> TargetKind.APK
                    else -> TargetKind.UNKNOWN
                }
            }
        } catch (t: Throwable) {
            isZip = false
        }

        ctx.checkPause()

        val artifact = LoadArtifact(
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = sha,
            kind = kind.name,
            isZip = isZip,
            entryCount = entryCount,
            hasManifest = hasManifest,
            hasModuleProp = hasModuleProp,
        )
        ctx.write(ctx.artifactName(stage), Json.encodeToString(artifact))

        onProgress(100, "装载完成 · ${kind.name}")
    }

    private fun sha256File(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
