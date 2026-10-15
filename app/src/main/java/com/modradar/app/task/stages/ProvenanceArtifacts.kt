package com.modradar.app.task.stages

import kotlinx.serialization.Serializable

/**
 * 联网来源核验结果（与 S5 并发执行，独立文件 provenance.json，不是阶段主产物）。
 * 只向 GitHub API 发送包名 / 模块名 / updateJson 地址，不上传目标文件。
 */
@Serializable
data class ProvenanceArtifact(
    /** OK / DISABLED / FAILED */
    val status: String = OK,
    val error: String? = null,
    val checkedAt: Long = 0L,
    val repo: RepoRef? = null,
    /** 仓库是怎么找到的（展示文案）。 */
    val matchedBy: String? = null,
    /** STRONG：updateJson / LSPosed 模块仓库；MEDIUM：包内链接；WEAK：名称搜索。 */
    val strength: String = NONE,
    val others: List<RepoRef> = emptyList(),
    val release: ReleaseRef? = null,
    val latestTag: String? = null,
    val verdict: String = NOT_FOUND,
    val detail: String = "",
    val officialSigner: String? = null,
    /** 内容比对：不一致的文件。 */
    val diffFiles: List<String> = emptyList(),
    val sameFiles: Int = 0,
    /** 本地是从设备模块目录重新打包的：安装脚本可能改过文件，内容差异不直接判为修改版。 */
    val deviceCopy: Boolean = false,
) {
    /** 与官方一致（哈希 / 签名 / 文件内容）。 */
    val official: Boolean get() = verdict == OFFICIAL || verdict == SAME_SIGNER

    /** 与可靠来源比对后不一致：疑似修改版。 */
    val modified: Boolean get() = (verdict == SIGNER_MISMATCH || verdict == CONTENT_MISMATCH) && strength != WEAK && !deviceCopy

    companion object {
        const val OK = "OK"
        const val DISABLED = "DISABLED"
        const val FAILED = "FAILED"

        const val STRONG = "STRONG"
        const val MEDIUM = "MEDIUM"
        const val WEAK = "WEAK"
        const val NONE = "NONE"

        const val OFFICIAL = "OFFICIAL"
        const val SAME_SIGNER = "SAME_SIGNER"
        const val SIGNER_MISMATCH = "SIGNER_MISMATCH"
        const val CONTENT_MISMATCH = "CONTENT_MISMATCH"
        const val SOURCE_FOUND = "SOURCE_FOUND"
        const val NOT_FOUND = "NOT_FOUND"

        const val FILE = "provenance.json"
    }
}

@Serializable
data class RepoRef(
    val fullName: String,
    val url: String,
    val stars: Int = 0,
    val description: String? = null,
    val archived: Boolean = false,
    val fork: Boolean = false,
)

@Serializable
data class ReleaseRef(
    val tag: String,
    val url: String,
    val asset: String? = null,
    val publishedAt: String? = null,
)
