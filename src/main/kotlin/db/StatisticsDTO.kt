package com.tbread.db

import kotlinx.serialization.Serializable

// 필터 옵션 응답용 가방
@Serializable
data class FilterOptionsResponse(
    val months: List<String>,
    val bosses: List<BossOption>
)

@Serializable
data class BossOption(
    val name: String,
    val id: String
)

// 랭킹 응답용 가방
@Serializable
data class JobRankingResponse(
    val job: String,
    val avgDps: Long,
    val maxDps: Long,
    val count: Long
)

// 스킬 분석 응답용 가방
@Serializable
data class SkillAnalysisResponse(
    val name: String,
    val avgDamage: Long,
    val avgCount: Double
)