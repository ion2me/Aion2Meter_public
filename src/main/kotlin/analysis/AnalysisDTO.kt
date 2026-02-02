package com.tbread.analysis

import kotlinx.serialization.Serializable

@Serializable
data class AnalysisResult(
    val nickname: String,     // 분석 대상 캐릭터 이름
    val totalLogs: Int,       // 총 분석된 로그 수
    val bossStats: List<BossStat> // 보스별 통계 리스트
)

@Serializable
data class BossStat(
    val bossName: String,
    val bossId: String,         //
    val killCount: Int,       // 처치 횟수
    val bestDps: Long,        // 최고 DPS
    val avgDps: Long,         // 평균 DPS
    val bestParty: List<String>, // 최고 DPS 찍었을 때의 파티 조합 (직업 리스트)
    val skillStats: List<SkillStat> // 스킬별 평균 통계
)

@Serializable
data class SkillStat(
    val name: String,
    val avgDamage: Long,      // 평균 데미지 (누적 딜 / 횟수 아님, 한 판당 평균 누적 딜)
    val share: Double         // 점유율 (%)
)