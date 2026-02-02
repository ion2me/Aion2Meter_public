package com.tbread.analysis

import com.tbread.PathManager
import com.tbread.entity.LogRoot
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

object LogAnalyzer {
    // JSON 파서 설정
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }


    private fun getThisWeekPattern(): String {
        val today = LocalDate.now()
        val mondayDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return mondayDate.format(DateTimeFormatter.ISO_DATE)
    }


    private fun getWeeklyFiles(): List<File> {
        val logDir = PathManager.getLogDir()
        if (!logDir.exists()) return emptyList()

        val weekPattern = getThisWeekPattern()

        // 파일명이 "log_2026-01-26_..." 형태이므로 weekPattern이 포함된 것만 필터링
        return logDir.listFiles()?.filter { file ->
            file.extension == "json" && file.name.contains(weekPattern)
        } ?: emptyList()
    }

    fun getAvailableCharacters(): List<String> {
        val files = getWeeklyFiles()
        if (files.isEmpty()) return emptyList()

        val nameCounts = mutableMapOf<String, Int>()

        files.forEach { file ->
            try {
                val content = file.readText()
                val logs = json.decodeFromString<List<LogRoot>>(content)

                logs.forEach { log ->
                    log.records.forEach { record ->
                        nameCounts[record.name] = (nameCounts[record.name] ?: 0) + 1
                    }
                }
            } catch (e: Exception) {
                println("⚠️ 파일 파싱 실패 (${file.name}): ${e.message}")
            }
        }

        return nameCounts.filter { it.value >= 1 }
            .entries.sortedByDescending { it.value }
            .map { it.key }
    }

    fun analyze(targetNickname: String): AnalysisResult {
        val files = getWeeklyFiles()

        if (files.isEmpty()) return AnalysisResult(targetNickname, 0, emptyList())

        // 1. 이번 주 파일들만 읽어서 리스트로 평탄화
        val validLogs = files.mapNotNull { file ->
            try {
                json.decodeFromString<List<LogRoot>>(file.readText())
            } catch (e: Exception) {
                null
            }
        }
            .flatten()

        // 2. 내가 참여한 전투만 필터링
        val myLogs = validLogs.filter { log ->
            log.records.any { it.name == targetNickname }
        }

        if (myLogs.isEmpty()) {
            return AnalysisResult(targetNickname, 0, emptyList())
        }

        // 3. 보스별로 그룹화
        val logsByBoss = myLogs.groupBy { log ->
            val code = log.meta.target.code
            if (code != null && code != 0) {
                code.toString()
            } else {
                log.meta.target.name
            }
        }

        // 4. 각 보스별 통계 계산
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
     * 이상치(Median ±10%) 제거 로직 포함.
     */
    private fun calculateBossStat(nickname: String, rawLogs: List<LogRoot>): BossStat? {
        if (rawLogs.isEmpty()) return null

        // [Step 1] 이상치 제거
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
            dmg in lowerBound..upperBound
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