package com.modradar.app.task.stages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AI 研判的结构化结论（P7 输出协议）。字段固定，报告卡片与 HTML 模板都按它渲染，
 * 保证多次研判的版式一致。
 */
@Serializable
data class AiVerdict(
    /** 低风险 / 需关注 / 可疑 / 高危 / 恶意（纯定性标签，不影响分数与等级） */
    val verdict: String = "",
    val headline: String = "",
    val overview: String = "",
    /** 指标宫格：每项一句话的关键判断，如「远程代码：未发现」。 */
    val highlights: List<AiHighlight> = emptyList(),
    val findings: List<AiFinding> = emptyList(),
    @SerialName("rule_review") val ruleReview: List<AiRuleReview> = emptyList(),
    val privacy: String = "",
    val unknowns: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
)

@Serializable
data class AiHighlight(
    val label: String = "",
    val value: String = "",
    /** ok / low / mid / hi */
    val tone: String = "low",
)

@Serializable
data class AiFinding(
    val title: String = "",
    /** HIGH / MEDIUM / LOW / INFO */
    val severity: String = "MEDIUM",
    /** 已确认 / 有嫌疑 / 未验证 */
    val status: String = "",
    val evidence: String = "",
    /** 通俗说明：对用户意味着什么 */
    val impact: String = "",
)

@Serializable
data class AiRuleReview(
    @SerialName("rule_id") val ruleId: String = "",
    /** 成立 / 误报 / 加重 */
    val judgement: String = "",
    /** 仅「加重」时可填 HIGH / MEDIUM / LOW：指定追加档位；空则按原扣分 50%（封顶 −15） */
    @SerialName("severity") val severity: String? = null,
    val reason: String = "",
)

/** 从模型输出中取出 [AiVerdict]：容忍 ```json 围栏、前后废话与尾逗号。 */
object AiVerdictParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parse(raw: String?): AiVerdict? {
        val s = raw ?: return null
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = s.substring(start, end + 1).replace(Regex(",\\s*([}\\]])"), "$1")
        val v = runCatching { json.decodeFromString(AiVerdict.serializer(), body) }.getOrNull() ?: return null
        if (v.headline.isBlank() && v.overview.isBlank() && v.findings.isEmpty()) return null
        return v
    }
}

/** S5 产物：AI 研判结果。 */
@Serializable
data class AiReviewArtifact(
    val status: String = "NOT_CONFIGURED",
    val summary: String? = null,
    val toolCalls: List<String> = emptyList(),
    val model: String? = null,
    val error: String? = null,
    val verdict: AiVerdict? = null,
)

/** S6 产物：汇总报告。 */
@Serializable
data class ReportArtifact(
    val targetName: String = "",
    val targetKind: String = "",
    /** 统一分数（100 − Σ扣分，AI 复核修正后）：全 App 唯一口径。 */
    val score: Int = 100,
    val level: String = "LOW",
    val totalDeduction: Int = 0,
    /** AI 净调整量：正 = 误报退回，负 = 加重 + 补充扣分；0 = 未调整或无 AI。 */
    val aiAdjustment: Int = 0,
    /** 用户已退回的 AI 调整量（绝对值）。 */
    val aiDismissedAdjustment: Int = 0,
    val highCount: Int = 0,
    val mediumCount: Int = 0,
    val lowCount: Int = 0,
    val findings: List<ScoreFinding> = emptyList(),
    val chains: List<ChainHit> = emptyList(),
    val aiStatus: String = "NOT_CONFIGURED",
    val aiSummary: String? = null,
    val notes: List<String> = emptyList(),
    val generatedAt: Long = 0L,
    val aiVerdict: AiVerdict? = null,
    val aiModel: String? = null,
    val aiError: String? = null,
    /** 联网来源核验（GitHub）；null 表示未执行。 */
    val provenance: ProvenanceArtifact? = null,
)
