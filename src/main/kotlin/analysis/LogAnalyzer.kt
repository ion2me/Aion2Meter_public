package com.tbread.analysis

import com.tbread.PathManager
import com.tbread.entity.LogRoot
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object LogAnalyzer {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * [시간 필터] 이번 주 월요일 0시 0분 0초의 타임스탬프(ms)를 구합니다.
     */
    private fun getStartOfThisWeek(): Long {
        return LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) // 이번 주 월요일(오늘 포함)
            .atStartOfDay(ZoneId.systemDefault()) // 00:00:00
            .toInstant()
            .toEpochMilli()
    }

    /**
     * logs 폴더를 스캔하여 [이번 주] 로그에 등장한 캐릭터 이름 목록을 반환합니다.
     * 월요일 이전의 옛날 파일은 읽지 않으며, 내용 중에서도 월요일 이후 전투만 집계합니다.
     */
    fun getAvailableCharacters(): List<String> {
        val logDir = PathManager.getLogDir()
        if (!logDir.exists()) return emptyList()

        // 1. 기준 시간 설정 (이번 주 월요일)
        val startOfWeek = getStartOfThisWeek()

        // 2. 파일 수정 시간으로 1차 필터링 (오래된 파일은 아예 열지 않음)
        val files = logDir.listFiles()?.filter {
            it.extension == "json" && it.lastModified() >= startOfWeek
        }
        if (files.isNullOrEmpty()) return emptyList()

        val nameCounts = mutableMapOf<String, Int>()

        files.forEach { file ->
            try {
                val content = file.readText()
                val logs = json.decodeFromString<List<LogRoot>>(content)

                // 3. 실제 로그 타임스탬프로 2차 필터링 (이번 주 데이터만 카운트)
                logs.filter { it.meta.timestamp >= startOfWeek }
                    .forEach { log ->
                        log.records.forEach { record ->
                            nameCounts[record.name] = (nameCounts[record.name] ?: 0) + 1
                        }
                    }
            } catch (e: Exception) {
                // 파싱 에러 무시
            }
        }

        // 등장 횟수 순으로 정렬하여 반환
        return nameCounts.filter { it.value >= 1 }
            .entries.sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * 특정 닉네임의 "이번 주(월요일~현재)" 전투 기록을 분석합니다.
     */
    fun analyze(targetNickname: String): AnalysisResult {
        val logDir = PathManager.getLogDir()
        if (!logDir.exists()) return AnalysisResult(targetNickname, 0, emptyList())

        val startOfWeek = getStartOfThisWeek()

        // 1. 파일 읽기 및 평탄화 (여기도 이번 주 필터 적용)
        val validLogs = logDir.listFiles()
            ?.filter { it.extension == "json" && it.lastModified() >= startOfWeek }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<List<LogRoot>>(file.readText())
                } catch (e: Exception) {
                    null
                }
            }
            ?.flatten()
            ?.filter { log -> log.meta.timestamp >= startOfWeek }
            ?: emptyList()

        // 2. 내가 참여한 전투만 필터링
        val myLogs = validLogs.filter { log ->
            log.records.any { it.name == targetNickname }
        }

        if (myLogs.isEmpty()) {
            return AnalysisResult(targetNickname, 0, emptyList())
        }

        // 3. 보스별 그룹화
        val logsByBoss = myLogs.groupBy { log ->
            val code = log.meta.target.code
            if (code != null && code != 0) {
                code.toString()
            } else {
                log.meta.target.name
            }
        }

        // 4. 통계 계산
        val bossStats = logsByBoss.mapNotNull { (_, logs) ->
            calculateBossStat(targetNickname, logs)
        }.sortedByDescending { it.killCount }

        return AnalysisResult(
            nickname = targetNickname,
            totalLogs = myLogs.size,
            bossStats = bossStats
        )
    }

    /**
     * 특정 보스에 대한 통계를 계산합니다.
     * (중앙값 필터링 로직 포함)
     */
    private fun calculateBossStat(nickname: String, rawLogs: List<LogRoot>): BossStat? {
        if (rawLogs.isEmpty()) return null

        // [Step 1] 이상치 제거 (Outlier Filtering)
        val damageList = rawLogs
            .map { it.meta.target.totalDamage }
            .filter { it > 0 }
            .sorted()

        if (damageList.isEmpty()) return null

        val midIndex = damageList.size / 2
        val medianDamage = damageList[midIndex]

        val lowerBound = (medianDamage * 0.9).toLong()
        val upperBound = (medianDamage * 1.1).toLong()

        val validLogs = rawLogs.filter { log ->
            val dmg = log.meta.target.totalDamage
            dmg >= lowerBound && dmg <= upperBound
        }

        if (validLogs.isEmpty()) return null


        // [Step 2] 통계 계산
        val myRecords = validLogs.mapNotNull { log ->
            val myRecord = log.records.find { it.name == nickname }
            if (myRecord != null) Pair(log, myRecord) else null
        }

        if (myRecords.isEmpty()) return null

        val dpsList = myRecords.map { it.second.dps.toLong() }
        val maxDps = dpsList.maxOrNull() ?: 0L
        val avgDps = if (dpsList.isNotEmpty()) dpsList.average().toLong() else 0L

        val bestRun = myRecords.maxByOrNull { it.second.dps }
        val bestParty = bestRun?.first?.records
            ?.filter { it.name != nickname }
            ?.map { it.job }
            ?: emptyList()

        val skillMap = mutableMapOf<String, Long>()
        myRecords.forEach { (_, record) ->
            record.skills.values.forEach { skill ->
                skillMap[skill.skillName] = (skillMap[skill.skillName] ?: 0L) + skill.damageAmount.toLong()
            }
        }

        val totalMyDamageSum = myRecords.sumOf { it.second.totalDamage }
        val averageMyTotalDamage = totalMyDamageSum / validLogs.size

        val skillStats = skillMap.map { (name, totalAmount) ->
            val avgAmount = totalAmount / validLogs.size
            val share = if (averageMyTotalDamage > 0) {
                (avgAmount.toDouble() / averageMyTotalDamage) * 100
            } else {
                0.0
            }
            SkillStat(name, avgAmount, share)
        }.sortedByDescending { it.avgDamage }
            .take(12)

        val firstMeta = validLogs.first().meta.target
        val code = firstMeta.code ?: 0
        val mapId = firstMeta.mapId ?: 0
        val uniqueBossId = if (mapId > 0) "${code}_${mapId}" else code.toString()

        return BossStat(
            bossName = firstMeta.name,
            bossId = uniqueBossId,
            killCount = validLogs.size,
            bestDps = maxDps,
            avgDps = avgDps,
            bestParty = bestParty,
            skillStats = skillStats
        )
    }
}