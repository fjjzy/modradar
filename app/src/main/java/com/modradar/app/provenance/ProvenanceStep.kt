package com.modradar.app.provenance

import android.content.Context
import com.modradar.app.scan.ksu.RootShell
import com.modradar.app.task.Stage
import com.modradar.app.task.stages.ChainArtifact
import com.modradar.app.task.stages.ExtractArtifact
import com.modradar.app.task.stages.LoadArtifact
import com.modradar.app.task.stages.ProvenanceArtifact
import kotlinx.serialization.json.Json
import java.io.File

/** 联网来源核验开关（默认开启；只发送包名 / 模块名 / updateJson 地址）。 */
object ProvenanceSettings {
    private const val PREF = "provenance"
    private const val KEY = "enabled"

    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/**
 * 从 S1–S3 产物组装核验输入并写出 provenance.json。
 * 流水线（S5 内与 AI 并发）与报告页「重新核验」共用。
 */
class ProvenanceStep(
    private val checker: ProvenanceChecker,
    private val enabled: () -> Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(
        targetPath: String,
        targetName: String,
        kind: String,
        read: (String) -> String?,
        write: (String, String) -> Unit,
    ): ProvenanceArtifact {
        val result = if (!enabled()) {
            ProvenanceArtifact(status = ProvenanceArtifact.DISABLED, checkedAt = System.currentTimeMillis(), detail = "已在设置中关闭联网来源核验")
        } else {
            val load = read("${Stage.LOAD.id}.json")?.let { runCatching { json.decodeFromString<LoadArtifact>(it) }.getOrNull() }
            val ex = read("${Stage.EXTRACT.id}.json")?.let { runCatching { json.decodeFromString<ExtractArtifact>(it) }.getOrNull() }
            val chain = read("${Stage.CHAIN.id}.json")?.let { runCatching { json.decodeFromString<ChainArtifact>(it) }.getOrNull() }
            val file = File(targetPath).takeIf { it.isFile }
            val k = when {
                ex?.ksuModule != null -> "KSU_MODULE"
                ex?.isXposedModule == true -> "XPOSED_MODULE"
                else -> kind
            }
            val input = ProvenanceChecker.Input(
                kind = k,
                targetName = targetName,
                packageName = ex?.manifest?.packageName,
                versionName = ex?.manifest?.versionName,
                ksuId = ex?.ksuModule?.id,
                ksuName = ex?.ksuModule?.name,
                ksuVersion = ex?.ksuModule?.version,
                updateJson = ex?.ksuModule?.updateJson,
                file = file,
                fileSha256 = load?.sha256,
                signerSha256 = ex?.signerSha256,
                urls = chain?.httpUrls.orEmpty().filter { "github" in it },
                packedFromDevice = k == "KSU_MODULE" && file != null && RootShell.isPackedFromDevice(file),
            )
            runCatching { checker.check(input) }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                ProvenanceArtifact(status = ProvenanceArtifact.FAILED, error = it.message ?: "网络错误", checkedAt = System.currentTimeMillis())
            }
        }
        write(ProvenanceArtifact.FILE, json.encodeToString(result))
        return result
    }
}
