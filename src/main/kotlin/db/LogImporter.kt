// Path: src/main/kotlin/com/tbread/db/LogImporter.kt
package com.tbread.db

import com.tbread.entity.LogRoot
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object LogImporter {

    fun importFromFolder(folderPath: String = "logs"): String {
        val dir = File(folderPath)
        if (!dir.exists()) return "폴더 없음"

        val jsonFiles = dir.listFiles { _, name -> name.endsWith(".json") } ?: return "파일 없음"
        var success = 0
        var fail = 0
        var skip = 0

        jsonFiles.forEach { file ->
            try {
                if (importSingleFile(file)) success++ else skip++
            } catch (e: Exception) {
                e.printStackTrace()
                fail++
            }
        }
        return "성공: $success, 중복: $skip, 실패: $fail"
    }

    private fun importSingleFile(file: File): Boolean {
        // 1. 중복 체크
        var exists = false
        transaction {
            exists = Battles.select { Battles.fileName eq file.name }.count() > 0
        }
        if (exists) return false

        // 2. 파싱
        val content = file.readText()

        val logDataList = try {
            Json { ignoreUnknownKeys = true }.decodeFromString<List<LogRoot>>(content)
        } catch (e: Exception) {
            try {
                listOf(Json { ignoreUnknownKeys = true }.decodeFromString<LogRoot>(content))
            } catch (e2: Exception) {
                println("❌ 파싱 실패: ${file.name}")
                return false
            }
        }

        // 3. DB 저장
        transaction {
            logDataList.forEach { logData ->
                val newBattleId = Battles.insertAndGetId {
                    it[Battles.timestamp] = logData.meta.timestamp
                    it[Battles.duration] = logData.meta.durationMs
                    it[Battles.totalDamage] = logData.meta.target.totalDamage
                    it[Battles.targetId] = logData.meta.target.id
                    it[Battles.targetName] = logData.meta.target.name
                    it[Battles.targetCode] = logData.meta.target.code
                    it[Battles.targetMapId] = logData.meta.target.mapId

                    it[Battles.recorderId] = logData.meta.recorderId
                    it[Battles.fileName] = file.name
                }

                logData.records.forEach { record ->
                    val newPlayerId = Players.insertAndGetId {
                        it[Players.battleId] = newBattleId
                        it[Players.name] = record.name
                        it[Players.job] = record.job
                        it[Players.totalDamage] = record.totalDamage
                        it[Players.dps] = record.dps
                    }

                    record.skills.forEach { (skillIdKey, skillData) ->
                        Skills.insert {
                            it[Skills.playerId] = newPlayerId
                            it[Skills.skillId] = skillIdKey.toInt()
                            it[Skills.skillName] = skillData.skillName
                            it[Skills.damage] = skillData.damageAmount
                            it[Skills.useCount] = skillData.times
                            it[Skills.critCount] = skillData.critTimes
                        }
                    }
                }
            }
        }
        println("✅ DB 저장 완료: ${file.name} (로그 ${logDataList.size}개)")
        return true
    }
}