package com.tbread

import com.tbread.config.AppConfig
import com.tbread.entity.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.time.LocalDate

class DpsCalculator(val dataStorage: DataStorage) {
    private val logger = LoggerFactory.getLogger(DpsCalculator::class.java)

    // 타겟별 누적 상태는 패킷 스트림과 UI 조회가 동시에 접근하므로, 동시 접근 가능한 맵을 사용한다.
    private val targetInfoMap = ConcurrentHashMap<Int, TargetInfo>()

    // 타겟 -> (공격자 -> 개인 통계) 구조이며, 내부 맵도 동시에 갱신될 수 있어 동시성 맵으로 유지한다.
    private val combatStatsMap = ConcurrentHashMap<Int, ConcurrentHashMap<Int, PersonalData>>()

    private var currentTargetId: Int = 0

    // 자동 저장은 "전투가 끝났다"는 휴리스틱에 의존하므로, 조건은 보수적으로 두고 메모리 정리는 분리한다.
    private val IDLE_TIMEOUT_MS = 60_000L
    private val MIN_BATTLE_DURATION_MS = 30_000L

    /**
     * 패킷 스트림을 실시간으로 집계 상태에 반영한다.
     */
    fun onPacketReceived(pdp: ParsedDamagePacket) {
        val targetId = pdp.getTargetId()

        // 타겟별 상태는 최초 관측 시점의 타임스탬프를 기준으로 생성하고, 이후는 누적으로 갱신한다.
        val targetInfo = targetInfoMap.getOrPut(targetId) {
            TargetInfo(targetId, 0, pdp.getTimeStamp(), pdp.getTimeStamp())
        }
        targetInfo.processPdp(pdp)

        // 소환수는 통계를 소유자에게 귀속시키기 위해 ownerId로 정규화한다.
        val actorId = pdp.getActorId()
        val actorEntity = dataStorage.getEntity(actorId)
        val realUid = if (actorEntity is Summon) actorEntity.ownerId else actorId

        val realEntity = dataStorage.getEntity(realUid)
        val nickname = realEntity?.name ?: "User_$realUid"

        // 타겟별 공격자 맵을 생성/조회하고, 공격자별 누적 통계를 갱신한다.
        val attackersMap = combatStatsMap.getOrPut(targetId) { ConcurrentHashMap() }

        val personalData = attackersMap.getOrPut(realUid) {
            PersonalData(nickname = nickname)
        }

        personalData.processPdp(pdp)

        // 직업 정보는 스킬 기반 추론이므로, 최초 1회만 채워 불필요한 연산을 줄인다.
        if (personalData.job == "") {
            val origin = SkillMetadata.inferOriginalSkillCode(pdp.getSkillCode1()) ?: pdp.getSkillCode1()
            val job = JobClass.convertFromSkill(origin)
            if (job != null) {
                personalData.job = job.className
            }
        }

        // 현재 표시 타겟은 최근 딜량 기반으로 결정하며, 패킷 시각을 기준으로 윈도우를 평가한다.
        updateCurrentTarget(targetId, pdp.getTimeStamp())
    }

    /**
     * UI 표시 및 저장을 위한 스냅샷을 생성한다.
     * targetId가 0이면 현재 타겟 기준으로 반환한다.
     */
    fun getDps(targetId: Int = currentTargetId): DpsData {
        val dpsData = DpsData()

        if (targetId == 0 || !targetInfoMap.containsKey(targetId)) {
            return dpsData
        }

        val targetInfo = targetInfoMap[targetId]!!
        val attackersMap = combatStatsMap[targetId] ?: return dpsData

        dpsData.targetId = targetId
        dpsData.battleTime = targetInfo.parseBattleTime()

        dpsData.targetName = dataStorage.getTargetName(targetId)

        // 집계가 아직 시작 단계인 경우에도 0으로 나누지 않도록 최소 시간을 보정한다.
        val safeBattleTime = if (dpsData.battleTime <= 0L) 1L else dpsData.battleTime

        val totalDamage = targetInfo.damagedAmount().toDouble()

        // 저장/필터링에서 사용될 수 있도록 보스 총 피해량을 함께 노출한다.
        dpsData.totalDamage = totalDamage.toLong()

        // 동시 수정 중인 맵을 순회하므로, 각 값은 순간 스냅샷으로 취급한다.
        attackersMap.forEach { (uid, pData) ->
            if (pData.job == "") return@forEach

            pData.dps = pData.amount / safeBattleTime * 1000

            if (totalDamage > 0) {
                pData.damageContribution = (pData.amount / totalDamage) * 100
            } else {
                pData.damageContribution = 0.0
            }

            dpsData.map[uid] = pData
        }

        return dpsData
    }

    /**
     * 종료된 전투를 찾아 파일로 저장하고, 저장한 타겟의 메모리를 해제한다.
     * 호출 주기는 UI/타이머에서 짧게 유지하되, 저장 조건은 보수적으로 평가한다.
     */
    fun checkAndAutoSave() {
        val now = System.currentTimeMillis()
        val savedTargets = mutableListOf<Int>()

        targetInfoMap.forEach { (tId, info) ->
            val lastHit = info.getLastHitTime()
            val timeDiff = now - lastHit

            // 전투 종료 판단은 "마지막 타격 이후 일정 시간 경과"로 단순화한다.
            if (timeDiff > IDLE_TIMEOUT_MS) {
                val battleTime = info.parseBattleTime()

                // 저장 대상이 아니어도 메모리 누수를 막기 위해 종료된 타겟은 정리 후보로 넣는다.
                if (battleTime < MIN_BATTLE_DURATION_MS || !isValidBossTarget(tId)) {
                    savedTargets.add(tId)
                }

                if (battleTime >= MIN_BATTLE_DURATION_MS) {
                    if (isValidBossTarget(tId)) {
                        logger.info("💾 자동 저장 조건 만족! TargetID: $tId (전투시간: ${battleTime/1000}초)")

                        saveLogToFile(tId)

                        savedTargets.add(tId)
                    } else {
                        // 보스가 아닌 로그는 저장하지 않되, 누적이 과하면 별도 정책으로 정리할 수 있다.
                    }
                }
            }
        }

        // 저장/정리 대상은 집계 맵에서 제거해 다음 전투에 섞이지 않게 한다.
        savedTargets.forEach { removeId ->
            targetInfoMap.remove(removeId)
            combatStatsMap.remove(removeId)

            if (currentTargetId == removeId) {
                currentTargetId = 0
                dataStorage.setCurrentTarget(0)
            }
            logger.info("🧹 저장된 타겟 데이터 메모리 해제 완료: $removeId")
        }
    }

    private fun isValidBossTarget(targetId: Int): Boolean {
        // 통계 키의 안정성을 위해 npcCode/mapId가 확정된 NPC만 저장 대상으로 인정한다.
        val npcEntity = dataStorage.getEntity(targetId) as? Npc ?: return false
        return npcEntity.mapId != 0 && npcEntity.npcCode != 0
    }

    /**
     * 특정 타겟의 전투 결과를 주 단위 파일에 append하여 저장한다.
     */
    private fun saveLogToFile(targetId: Int) {
        try {
            val dpsData = getDps(targetId)
            val now = LocalDateTime.now()

            val targetEntity = dataStorage.getEntity(targetId)
            val targetNpc = targetEntity as? Npc

            val npcCode = targetNpc?.npcCode ?: dataStorage.getNpcCode(targetId)
            val mapId = targetNpc?.mapId ?: dataStorage.getCurrentMapId()

            // 저장 시점에는 사람에게 보이는 이름을 우선하되, 매핑이 있으면 매핑 이름을 사용한다.
            val targetName = if (npcCode != null) {
                BossMappingManager.getName(npcCode, mapId) ?: dpsData.targetName
            } else {
                dpsData.targetName
            }

            val targetTotalDamage = targetInfoMap[targetId]?.damagedAmount()?.toLong() ?: 0L

            // 파일 단위를 주(월요일 시작)로 고정해, 같은 주의 로그가 한 파일에 모이도록 한다.
            val today = LocalDate.now()
            val mondayDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekPattern = mondayDate.format(DateTimeFormatter.ISO_DATE)

            val logDir = PathManager.getLogDir()
            logger.info("LOG DIR = ${logDir.absolutePath}")

            val recorderId = AppConfig.recorderId

            val filename = "log_${weekPattern}_$recorderId.json"
            val file = File(logDir, filename)

            val meta = LogMeta(
                timestamp = now.toEpochSecond(ZoneOffset.of("+09:00")),
                durationMs = dpsData.battleTime,
                target = LogTarget(
                    id = targetId,
                    code = npcCode,
                    mapId = mapId,
                    name = targetName,
                    totalDamage = targetTotalDamage
                ),
                recorderId = recorderId,
                version = 1
            )

            // 저장 포맷은 스키마 변경 가능성이 있으므로, meta.version으로 역직렬화 분기 여지를 남긴다.
            val records = dpsData.map.values.map { p ->
                val logSkills = p.analyzedData.entries.associate { (skillId, s) ->
                    skillId.toString() to LogAnalyzedSkill(
                        skillName = s.skillName,
                        damageAmount = s.damageAmount.toLong(),
                        times = s.times,
                        critTimes = s.critTimes,
                        perfectTimes = s.perfectTimes,
                        doubleTimes = s.doubleTimes
                    )
                }

                LogRecord(
                    name = p.nickname,
                    job = p.job,
                    totalDamage = p.amount.toDouble(),
                    dps = p.dps,
                    skills = logSkills
                )
            }

            // 주 단위 파일은 배열(JSON list)로 누적 저장하며, 손상 시에는 빈 리스트로 복구한다.
            val newLog = LogRoot(meta, records)
            val logList = if (file.exists()) {
                try {
                    Json.decodeFromString<MutableList<LogRoot>>(file.readText())
                } catch (e: Exception) {
                    mutableListOf()
                }
            } else {
                mutableListOf()
            }

            logList.add(newLog)

            val prettyJson = Json { prettyPrint = true }
            file.writeText(prettyJson.encodeToString(logList))

            logger.info("✅ 전투 로그 파일 저장 완료: $filename (Target: $targetName)")

        } catch (e: Exception) {
            logger.error("❌ 자동 저장 실패 [TargetID: $targetId]", e)
        }
    }

    private fun updateCurrentTarget(activeTargetId: Int, currentPacketTime: Long) {
        val entity = dataStorage.getEntity(activeTargetId)

        // 표시 타겟은 몬스터 계열만 대상으로 하며, 플레이어/소환수는 제외한다.
        if (entity is Player || entity is Summon) return

        if (currentTargetId == 0) {
            changeTarget(activeTargetId)
            return
        }

        if (currentTargetId == activeTargetId) return

        val currentTargetInfo = targetInfoMap[currentTargetId]
        val activeTargetInfo = targetInfoMap[activeTargetId]

        if (currentTargetInfo == null || activeTargetInfo == null) {
            changeTarget(activeTargetId)
            return
        }

        // 최근 딜량은 '살아있는 타겟'만 비교하도록 dead-time 보정이 포함된다.
        val currentDmg = currentTargetInfo.getEffectiveRecentDamage(currentPacketTime)
        val activeDmg = activeTargetInfo.getEffectiveRecentDamage(currentPacketTime)

        if (activeDmg > currentDmg) {
            changeTarget(activeTargetId)
            return
        }

        // 최근 딜이 비슷할 때는 보스 표시를 우선해, UI가 쫄몹으로 흔들리는 것을 줄인다.
        val currentEntity = dataStorage.getEntity(currentTargetId)
        val isCurrentBoss = (currentEntity as? Npc)?.isBoss == true
        val isActiveBoss = (entity as? Npc)?.isBoss == true

        if (isActiveBoss && !isCurrentBoss) {
            changeTarget(activeTargetId)
            return
        }
    }

    private fun changeTarget(newTargetId: Int) {
        if (currentTargetId != newTargetId) {
            currentTargetId = newTargetId
            dataStorage.setCurrentTarget(newTargetId)
            logger.debug("🎯 타겟 변경: $newTargetId")
        }
    }

    fun resetDataStorage() {
        targetInfoMap.clear()
        combatStatsMap.clear()
        currentTargetId = 0
        logger.info("DpsCalculator 데이터 전체 리셋 완료")
    }

    fun analyzingData(uid: Int) {
        val dpsData = getDps()
        dpsData.map.forEach { (_, pData) ->
            logger.debug("-----------------------------------------")
            logger.debug(
                "닉네임: {} 직업: {} 총 딜량: {} 기여도: {}",
                pData.nickname, pData.job, pData.amount, pData.damageContribution
            )
            pData.analyzedData.forEach { (key, data) ->
                logger.debug("스킬: {} / 피해량: {}", data.skillName, data.damageAmount)
                val share = if (pData.amount > 0) (data.damageAmount / pData.amount * 100).roundToInt() else 0
                logger.debug("딜 지분: {}%", share)
            }
            logger.debug("-----------------------------------------")
        }
    }
}
