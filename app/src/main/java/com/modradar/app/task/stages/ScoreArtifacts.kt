package com.modradar.app.task.stages

import kotlinx.serialization.Serializable

@Serializable
data class ScoreFinding(
    val ruleId: String,
    val title: String,
    val severity: String,
    val deduction: Int,
    val detail: String,
    val evidence: List<String> = emptyList(),
    /** 对当前目标类型属于预期行为（如 Xposed 模块调用 Hook 框架）：只提示、不扣分。 */
    val expected: Boolean = false,
    /** AI 复核判定为误报：deduction 已清零，原扣分保存在 [originalDeduction]。 */
    val aiDismissed: Boolean = false,
    val originalDeduction: Int = 0,
    /** AI 判「加重」追加的扣分（负值，独立于 deduction）。用户退回后清零。 */
    val aiAggravated: Boolean = false,
    val aiAdjustment: Int = 0,
)

@Serializable
data class ScoreArtifact(
    val score: Int = 100,
    val level: String = "LOW",
    val totalDeduction: Int = 0,
    val findings: List<ScoreFinding> = emptyList(),
    val notes: List<String> = emptyList(),
)
