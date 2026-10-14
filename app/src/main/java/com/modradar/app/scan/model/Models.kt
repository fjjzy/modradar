package com.modradar.app.scan.model

/** 分析目标类型。 */
enum class TargetKind {
    APK,
    XPOSED_MODULE,
    KSU_MODULE,
    UNKNOWN,
}

/** 风险等级（findings 与总分共用）。 */
enum class Severity { HIGH, MEDIUM, LOW, INFO }

/** 总分等级。 */
enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

/** AI 研判状态。 */
enum class AiStatus { NOT_CONFIGURED, PENDING, RUNNING, DONE, FAILED }

/**
 * 证据：每一条扣分/结论都必须能指回原始位置，报告里可点击查看。
 * location 形如 "classes.dex!0x1a2b" / "com.a.b#c" / "module.prop" / "lib/x.so"。
 */
data class Evidence(
    val id: String,
    val kind: String,
    val detail: String,
    val location: String? = null,
)

/** 单条分析结论。aiGenerated=true 的结论不计入总分。 */
data class Finding(
    val ruleId: String,
    val title: String,
    val severity: Severity,
    val detail: String,
    val deduction: Int = 0,
    val evidence: List<Evidence> = emptyList(),
    val aiGenerated: Boolean = false,
)

/** 能力链命中：SOURCE → TRANSFORM → SINK。 */
data class CapabilityChain(
    val chainId: String,
    val sources: List<String>,
    val transforms: List<String>,
    val sinks: List<String>,
    val reachable: Boolean,
    val sameMethod: Boolean,
    val deduction: Int,
    val evidence: List<Evidence> = emptyList(),
)

/** 一次分析的完整结果。 */
data class ScanReport(
    val targetName: String,
    val targetPath: String,
    val kind: TargetKind,
    val packageName: String? = null,
    val versionName: String? = null,
    val score: Int = 100,
    val level: RiskLevel = RiskLevel.LOW,
    val findings: List<Finding> = emptyList(),
    val chains: List<CapabilityChain> = emptyList(),
    val aiStatus: AiStatus = AiStatus.NOT_CONFIGURED,
    val aiSummary: String? = null,
    val aiFindings: List<Finding> = emptyList(),
    val analyzedAt: Long = System.currentTimeMillis(),
)

/** 评分等级换算。 */
fun scoreToLevel(score: Int): RiskLevel = when {
    score >= 80 -> RiskLevel.LOW
    score >= 60 -> RiskLevel.MEDIUM
    score >= 40 -> RiskLevel.HIGH
    else -> RiskLevel.CRITICAL
}
