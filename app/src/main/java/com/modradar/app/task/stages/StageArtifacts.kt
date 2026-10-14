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
    // KSU / Magisk 模块事实（S4 评分依据）
    val ksuModule: KsuModuleInfo? = null,
    /** 形如 "service.sh: 下载后直接执行（管道）" */
    val ksuScriptRisks: List<String> = emptyList(),
)
