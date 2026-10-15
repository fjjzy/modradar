package com.modradar.app.task.stages

import com.modradar.app.scan.ksu.KsuModuleInfo
import kotlinx.serialization.Serializable

@Serializable
data class LoadArtifact(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val kind: String,
    val isZip: Boolean,
    val entryCount: Int,
    val hasManifest: Boolean,
    val hasModuleProp: Boolean,
)

@Serializable
data class ManifestSummary(
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val permissions: List<String> = emptyList(),
    val debuggable: Boolean = false,
    val allowBackup: Boolean? = null,
    val usesCleartextTraffic: Boolean? = null,
    val sharedUserId: String? = null,
    val applicationName: String? = null,
    val componentCount: Int = 0,
    val exportedWithoutPermission: List<String> = emptyList(),
    val metaData: Map<String, String> = emptyMap(),
)

@Serializable
data class ExtractArtifact(
    val manifest: ManifestSummary? = null,
    val abis: List<String> = emptyList(),
    val dexEntries: List<String> = emptyList(),
    val nativeLibs: List<String> = emptyList(),
    val signatureFiles: List<String> = emptyList(),
    val shellHints: List<String> = emptyList(),
    val assetCount: Int = 0,
    val isXposedModule: Boolean = false,
    val xposedMinVersion: String? = null,
    val xposedDescription: String? = null,
    val parseError: String? = null,
    // 签名事实（S4 评分依据）
    val v1Signed: Boolean = false,
    val apkSigningBlock: Boolean = false,
    val signerSubject: String? = null,
    val signerSha256: String? = null,
    /** 实际存在的签名方案：v1 / v2 / v3 / v3.1。 */
    val signatureSchemes: List<String> = emptyList(),
    // KSU / Magisk 模块事实（S4 评分依据）
    val ksuModule: KsuModuleInfo? = null,
    /** 形如 "service.sh: 下载后直接执行（管道）" */
    val ksuScriptRisks: List<String> = emptyList(),
    // native 事实（S4 规则 C8 / 反调试依据）
    val nativeImports: List<NativeLibImports> = emptyList(),
    /** LSPatch 重打包特征（null 表示未发现）。 */
    val lspatch: LsPatchInfo? = null,
)

/** LSPatch 免 root 注入：原包被重打包，Xposed 模块内嵌进 assets/lspatch/。 */
@Serializable
data class LsPatchInfo(
    /** 内嵌模块（assets/lspatch/modules/ 下的文件名）。 */
    val modules: List<String> = emptyList(),
    /** 包内保留的原始 APK 的签名证书 SHA-256。 */
    val originSignerSha256: String? = null,
    val originSignerSubject: String? = null,
    val evidence: List<String> = emptyList(),
)

@Serializable
data class NativeLibImports(
    val path: String,
    val is64: Boolean = true,
    val importCount: Int = 0,
    /** 与安全判定相关的导入符号。 */
    val interesting: List<String> = emptyList(),
    val needed: List<String> = emptyList(),
    val c8: Boolean = false,
    val antiDebug: List<String> = emptyList(),
)
