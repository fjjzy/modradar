package com.modradar.app.task.stages

import kotlinx.serialization.Serializable

@Serializable
data class ChainEvidence(
    val kind: String,
    val detail: String,
    val location: String,
)

@Serializable
data class ChainHit(
    val ruleId: String,
    val title: String,
    val deduction: Int,
    val sources: List<String> = emptyList(),
    val transforms: List<String> = emptyList(),
    val sinks: List<String> = emptyList(),
    val sameMethod: Boolean = false,
    val reachable: Boolean = false,
    val evidence: List<ChainEvidence> = emptyList(),
)

@Serializable
data class ChainArtifact(
    val hits: List<ChainHit> = emptyList(),
    val dexCount: Int = 0,
    val methodCount: Int = 0,
    val sourceMethodCount: Int = 0,
    val sinkMethodCount: Int = 0,
    val httpUrls: List<String> = emptyList(),
    val truncated: Boolean = false,
    val note: String? = null,
    // 供 S4 评分使用的字符串事实
    val trackerHits: List<String> = emptyList(),
    val antiDebugHits: List<String> = emptyList(),
    val cleartextHttpCount: Int = 0,
    val trackerDomainHits: List<String> = emptyList(),
)
