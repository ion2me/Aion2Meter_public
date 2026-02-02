package com.tbread.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object StatisticsRepository {

    fun getFilterOptions(): FilterOptionsResponse {
        return transaction {
            val timestamps = Battles.slice(Battles.timestamp).selectAll().map { it[Battles.timestamp] }
            val months = timestamps.map { ts ->
                Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM"))
            }.distinct().sortedDescending()

            // DB에는 동일 보스가 여러 전투로 누적되므로, 필터 UI용으로만 "대표 옵션"을 만든다.
            val bosses = Battles.slice(Battles.targetName, Battles.targetCode, Battles.targetMapId)
                .selectAll()
                .map { row ->
                    val name = row[Battles.targetName]
                    val code = row[Battles.targetCode] ?: 0
                    val mapId = row[Battles.targetMapId] ?: 0

                    // 맵이 섞이는 경우를 분리하기 위해 code+mapId를 결합해 식별자로 사용한다.
                    val uniqueId = if (mapId > 0) "${code}_${mapId}" else code.toString()

                    BossOption(name, uniqueId)
                }
                .distinctBy { it.id }
                .sortedBy { it.name }

            FilterOptionsResponse(months, bosses)
        }
    }

    fun getJobRanking(monthStr: String, bossId: String): List<JobRankingResponse> {
        val startTs = parseStartTs(monthStr)
        val endTs = parseEndTs(monthStr)

        return transaction {
            // 조회 조건은 DB에서 최소화하되, "유효 로그" 판별은 통계 기준이 바뀔 수 있어 애플리케이션에서 처리한다.
            val rawData = Players.innerJoin(Battles)
                .slice(Players.job, Players.dps, Battles.totalDamage)
                .select {
                    (Battles.timestamp greaterEq startTs) and
                            (Battles.timestamp less endTs) and
                            resolveBossCondition(bossId)
                }
                .map {
                    Triple(
                        it[Players.job],
                        it[Players.dps],
                        it[Battles.totalDamage]
                    )
                }

            if (rawData.isEmpty()) return@transaction emptyList()

            // 전투별 보스 총 피해량 분포의 중앙값을 기준으로 이상치 로그를 억제한다.
            val damages = rawData.map { it.third }.sorted()
            val midIndex = damages.size / 2
            val medianDamage = damages[midIndex]

            // 로그 품질을 단순화하기 위해 중앙값 ±10%만 집계 대상으로 삼는다.
            val lowerBound = (medianDamage * 0.9).toLong()
            val upperBound = (medianDamage * 1.1).toLong()

            rawData
                .filter { (_, _, totalDamage) ->
                    totalDamage in lowerBound..upperBound
                }
                .groupBy { it.first }
                .map { (job, records) ->
                    val dpsList = records.map { it.second }
                    JobRankingResponse(
                        job = job,
                        avgDps = dpsList.average().toLong(),
                        maxDps = dpsList.maxOrNull()?.toLong() ?: 0L,
                        count = dpsList.size.toLong()
                    )
                }
                .sortedByDescending { it.avgDps }
        }
    }

    fun getJobSkillAnalysis(monthStr: String, bossId: String, jobName: String): List<SkillAnalysisResponse> {
        val startTs = parseStartTs(monthStr)
        val endTs = parseEndTs(monthStr)

        return transaction {
            val avgDamage = Skills.damage.avg()
            val avgCount = Skills.useCount.avg()

            Skills.innerJoin(Players).innerJoin(Battles)
                .slice(Skills.skillName, avgDamage, avgCount)
                .select {
                    (Battles.timestamp greaterEq startTs) and
                            (Battles.timestamp less endTs) and
                            resolveBossCondition(bossId) and
                            (Players.job eq jobName)
                }
                .groupBy(Skills.skillName)
                .orderBy(avgDamage, SortOrder.DESC)
                .limit(15)
                .map {
                    SkillAnalysisResponse(
                        name = it[Skills.skillName],
                        avgDamage = it[avgDamage]?.toLong() ?: 0L,
                        avgCount = it[avgCount]?.toDouble() ?: 0.0
                    )
                }
        }
    }

    // =========================================================================
    // Private Helpers
    // =========================================================================

    // bossId는 UI/로그 호환을 위해 "code" 또는 "code_mapId" 형태를 우선하고, 구버전은 이름으로 폴백한다.
    private fun SqlExpressionBuilder.resolveBossCondition(bossId: String): Op<Boolean> {
        if (bossId.isBlank()) return Op.TRUE

        if (bossId.contains("_")) {
            val parts = bossId.split("_")
            val code = parts[0].toIntOrNull() ?: 0
            val mapId = parts[1].toIntOrNull() ?: 0
            return (Battles.targetCode eq code) and (Battles.targetMapId eq mapId)
        }

        if (bossId.all { it.isDigit() }) {
            return Battles.targetCode eq bossId.toInt()
        }

        return Battles.targetName eq bossId
    }

    // monthStr는 "yyyy-MM" 형식이며, 빈 값은 전체 기간 조회로 해석한다.
    private fun parseStartTs(monthStr: String): Long {
        if (monthStr.isBlank()) return 0L
        return try {
            Instant.from(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").parse("$monthStr-01 00:00")).epochSecond
        } catch (e: Exception) { 0L }
    }

    // 종료 시각은 다음 달 1일 00:00(미만)으로 계산해 월 단위 조회에서 경계 오류를 피한다.
    private fun parseEndTs(monthStr: String): Long {
        if (monthStr.isBlank()) return Long.MAX_VALUE
        return try {
            val parts = monthStr.split("-")
            val year = parts[0].toInt()
            val month = parts[1].toInt()
            val nextMonthStr = if (month == 12) "${year + 1}-01" else "$year-${(month + 1).toString().padStart(2, '0')}"
            Instant.from(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").parse("$nextMonthStr-01 00:00")).epochSecond
        } catch (e: Exception) { Long.MAX_VALUE }
    }
}
