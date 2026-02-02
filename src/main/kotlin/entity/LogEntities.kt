// Path: src/main/kotlin/com/tbread/entity/LogEntities.kt
package com.tbread.entity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BossMapRequest(
    val entityId: Int,
    val name: String
)

@Serializable
data class LogRoot(val meta: LogMeta, val records: List<LogRecord>)

@Serializable
data class LogMeta(
    val timestamp: Long,
    val durationMs: Long,
    val target: LogTarget,
    val mapId: Int? = 0,
    val recorderId: String? = null,
    val version: Int = 0
)

@Serializable
data class LogTarget(
    val id: Int,
    val code: Int?,
    val mapId: Int? = 0,
    val name: String,
    val totalDamage: Long
)

@Serializable
data class LogRecord(
    val name: String,
    val job: String,
    val totalDamage: Double,
    val dps: Double,
    val skills: Map<String, LogAnalyzedSkill>
)

@Serializable
data class LogAnalyzedSkill(
    val skillName: String,
    val damageAmount: Long,
    val times: Int,         // useCount
    val critTimes: Int,     // critCount
    val perfectTimes: Int = 0,
    val doubleTimes: Int = 0
)
