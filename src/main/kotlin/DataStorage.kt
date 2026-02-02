// Path: com/tbread/DataStorage.kt
package com.tbread

import com.tbread.entity.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

class DataStorage {
    private val logger = LoggerFactory.getLogger(DataStorage::class.java)

    // DataStorage는 실시간 집계 상태의 단일 소스로 동작하므로, 계산기도 함께 소유해 라이프사이클을 일치시킨다.
    val dpsCalculator = DpsCalculator(this)

    // 모든 엔티티(플레이어/NPC/소환수)를 동적 ID 기준으로 보관한다.
    private val entityMap = ConcurrentHashMap<Int, GameEntity>()
    private val byTargetStorage = ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>>()
    private val byActorStorage = ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>>()
    private val skillCodeData = HashMap<Int, String>()
    private var currentTarget: Int = 0
    private var currentMapId: Int = 0
    private val lastDamagedAtMs = ConcurrentHashMap<Int, Long>()
    private val COMBAT_WINDOW_MS = 12_000L

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun markDamaged(entityId: Int) {
        lastDamagedAtMs[entityId] = nowMs()
    }

    // 전투 판정은 최근 피격 시각 기반의 휴리스틱이며, 엔티티 메타 확정(mapId 등)에만 사용한다.
    fun isInCombat(entityId: Int, now: Long = nowMs()): Boolean {
        val t = lastDamagedAtMs[entityId] ?: return false
        return (now - t) <= COMBAT_WINDOW_MS
    }

    fun registerEntity(entity: GameEntity) {
        entityMap[entity.id] = entity
        logger.debug("엔티티 등록: {}", entity)
    }

    fun getEntity(id: Int): GameEntity? = entityMap[id]

    fun getAllEntities(): Map<Int, GameEntity> = entityMap

    // =================================================================================
    // 3. 닉네임 처리
    // =================================================================================

    /**
     * 닉네임 패킷은 순서/재전송으로 뒤늦게 들어올 수 있으므로, 엔티티 타입을 보존하면서 Player 이름만 갱신한다.
     */
    fun updatePlayerNickname(id: Int, newName: String) {
        entityMap.compute(id) { _, existingEntity ->
            // NPC/Summon은 동일 ID로 충돌할 수 있어도 이름 패킷으로는 절대 덮지 않는다.
            if (existingEntity is Npc) {
                // logger.trace("NPC[{}]에 대한 닉네임 변경 요청 무시됨: {}", id, newName)
                return@compute existingEntity
            }

            if (existingEntity is Summon) {
                return@compute existingEntity
            }

            if (existingEntity is Player) {
                if (shouldUpdateNickname(existingEntity.name, newName)) {
                    val old = existingEntity.name
                    existingEntity.name = newName
                    logger.debug("닉네임 업데이트 [{}]: {} -> {}", id, old, newName)
                }
                existingEntity
            }
            else {
                // 식별자만 먼저 관측된 상태에서 닉네임이 도착할 수 있어, 없으면 Player로 생성한다.
                Player(id).apply { name = newName }
            }
        }
    }

    private fun shouldUpdateNickname(oldName: String, newName: String): Boolean {
        if (oldName == newName) return false
        // 깨진/단축된 이름(예: 1~2글자)으로 정상 이름을 덮어쓰지 않기 위한 방어 규칙.
        if (oldName.length >= 3 && newName.length <= 2) return false
        return true
    }

    // =================================================================================
    // 4. 맵 & 타겟 관리
    // =================================================================================

    @Synchronized
    fun setMapId(mapId: Int) {
        if (currentMapId == mapId) return
        logger.info("🗺️ DataStorage 맵 정보 갱신: {} -> {}", currentMapId, mapId)
        currentMapId = mapId
    }

    fun getCurrentMapId(): Int = currentMapId

    fun setCurrentTarget(targetId: Int) {
        currentTarget = targetId
    }

    fun getCurrentTarget(): Int = currentTarget

    // 표시 이름은 런타임 id보다 (npcCode,mapId) 기반의 안정 키를 우선해 통계/저장과 일관되게 만든다.
    fun getTargetName(targetId: Int): String {
        if (targetId == 0) return ""

        val e = entityMap[targetId]

        // 엔티티가 아직 등록되지 않았으면, 현재 맵 기준으로 임시 식별자를 만든다.
        if (e == null) {
            return "I${targetId}_${currentMapId}"
        }

        return when (e) {
            is Npc -> {
                // 매핑 이름이 없으면 고정 코드 기반 키를 그대로 노출해 디버깅/식별이 가능하게 한다.
                val mapped = BossMappingManager.getName(e.npcCode, e.mapId)
                mapped ?: "${e.npcCode}_${e.mapId}"
            }
            else -> e.name
        }
    }

    fun onNpcObserved(entityId: Int, npcCode: Int) {
        val now = nowMs()
        val inCombat = isInCombat(entityId, now)
        entityMap.compute(entityId) { _, existing ->
            when (existing) {
                null -> {
                    // 전투 중 관측된 경우에만 mapId를 확정해, 로딩/대기 중 오탐을 최소화한다.
                    val mapToSet = if (inCombat) currentMapId else 0
                    logger.debug(
                        "NPC 등록 [{}] npcCode={} mapId={} (inCombat={})",
                        entityId, npcCode, mapToSet, inCombat
                    )
                    Npc(id = entityId, npcCode = npcCode, mapId = mapToSet)
                }
                is Npc -> {
                    // npcCode는 식별자이므로 전투 여부와 무관하게 최신 값으로 동기화한다.
                    if (existing.npcCode != npcCode) {
                        logger.info("npcCode 갱신 [{}]: {} -> {}", entityId, existing.npcCode, npcCode)
                        existing.npcCode = npcCode
                    }

                    // mapId는 전투 중에만 확정/갱신해, 잘못된 맵 매핑으로 통계 키가 오염되는 것을 막는다.
                    if (inCombat && existing.mapId != currentMapId) {
                        logger.info("mapId 확정/갱신(전투중) [{}]: {} -> {}", entityId, existing.mapId, currentMapId)
                        existing.mapId = currentMapId
                    }

                    existing
                }
                else -> {
                    // 동일 entityId가 다른 타입으로 먼저 등록된 경우, 관측 정보 기준으로 NPC로 교체한다.
                    val mapToSet = if (inCombat) currentMapId else 0
                    logger.info(
                        "엔티티 교체 [{}]: {} -> Npc(npcCode={}, mapId={}) (inCombat={})",
                        entityId, existing::class.simpleName, npcCode, mapToSet, inCombat
                    )
                    Npc(id = entityId, npcCode = npcCode, mapId = mapToSet)
                }
            }
        }
    }

    // =================================================================================
    // 6. 데미지 처리
    // =================================================================================

    @Synchronized
    fun appendDamage(pdp: ParsedDamagePacket) {
        // 전투 판정 및 NPC 메타 확정을 위해 피격 시각을 먼저 기록한다.
        markDamaged(pdp.getTargetId())
        finalizeNpcMapIdOnDamage(pdp.getTargetId())

        byActorStorage.getOrPut(pdp.getActorId()) {
            ConcurrentSkipListSet(compareBy<ParsedDamagePacket> { it.getTimeStamp() }.thenBy { it.getUuid() })
        }.add(pdp)

        byTargetStorage.getOrPut(pdp.getTargetId()) {
            ConcurrentSkipListSet(compareBy<ParsedDamagePacket> { it.getTimeStamp() }.thenBy { it.getUuid() })
        }.add(pdp)

        // 실시간 UI/통계를 위해 저장과 동시에 계산기로 스트림을 전달한다.
        dpsCalculator.onPacketReceived(pdp)
    }

    @Synchronized
    fun flushDamageStorage() {
        byActorStorage.clear()
        byTargetStorage.clear()
        lastDamagedAtMs.clear()

        // 계산기 상태도 함께 초기화해 이전 전투가 새 집계에 섞이지 않게 한다.
        dpsCalculator.resetDataStorage()

        logger.info("데미지 패킷 초기화됨")
    }

    private fun finalizeNpcMapIdOnDamage(targetId: Int) {
        val mapNow = currentMapId
        if (mapNow == 0) return
        entityMap.compute(targetId) { _, e ->
            // mapId가 아직 미확정(0)인 NPC는 실제 데미지 발생 시점의 맵으로 확정한다.
            if (e is Npc && e.mapId == 0) e.mapId = mapNow
            e
        }
    }

    // =================================================================================
    // 7. 기타 유틸
    // =================================================================================

    fun getSkillName(skillCode: Int): String {
        return skillCodeData[skillCode] ?: skillCode.toString()
    }

    fun getBossModeData(): ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>> {
        return byTargetStorage
    }

    fun getNpcCode(id: Int): Int? {
        return (entityMap[id] as? Npc)?.npcCode
    }


}
