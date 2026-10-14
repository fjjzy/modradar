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
)

@Serializable
data class ScoreArtifact(
    val score: Int = 100,
    val level: String = "LOW",
    val totalDeduction: Int = 0,
    val findings: List<ScoreFinding> = emptyList(),
    val notes: List<String> = emptyList(),
)
