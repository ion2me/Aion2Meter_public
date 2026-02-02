package com.tbread.entity

import com.tbread.BossMappingManager

/**
 * 런타임에 등장하는 게임 엔티티의 공통 타입과 구현을 정의한다.
 * 이름/식별자 규칙은 패킷 수신으로 갱신될 수 있으므로, 외부에 노출되는 값의 의미를 주석으로 고정한다.
 */

// 1. 최상위 부모
abstract class GameEntity(
    val id: Int // 세션 동안만 유효한 동적 엔티티 ID
) {
    // 엔티티 이름은 타입에 따라 갱신 방식이 달라 동일한 인터페이스로만 노출한다.
    abstract var name: String

    override fun toString(): String {
        return "[$id] $name"
    }
}

// 2. 플레이어
class Player(
    id: Int
) : GameEntity(id) {
    // 닉네임 패킷 수신 전까지 임시 표기이며, 이후 실제 닉네임으로 치환된다.
    override var name: String = "User_$id"

    // 미확정/미수신 상태가 있을 수 있으므로 nullable로 유지한다.
    var jobClass: String? = null
}

// 3. 몬스터 & 보스
class Npc(
    id: Int,
    var npcCode: Int,
    var mapId: Int,
    val isBoss: Boolean = false
) : GameEntity(id) {

    // 매핑 결과를 즉시 반영하기 위해, 저장된 스냅샷 대신 조회 시점에 매니저에서 이름을 가져온다.
    override var name: String
        get() {
            val savedName = BossMappingManager.getName(npcCode, mapId)
            return savedName ?: "Mob_$npcCode"
        }
        set(value) {
            // Npc 이름의 단일 소스는 BossMappingManager이므로, 여기서는 의도적으로 무시한다.
        }

    // 통계/저장에서는 런타임 id 대신 (npcCode,mapId) 조합을 안정 키로 사용한다.
    fun getUniqueKey(): String = "${npcCode}_${mapId}"
}

// 4. 소환수
class Summon(
    id: Int,
    val ownerId: Int, // 소환수의 주인(플레이어) 엔티티 ID
    val npcCode: Int
) : GameEntity(id) {
    // 별도 이름 패킷이 없을 수 있어 코드 기반 기본 표기를 사용한다.
    override var name: String = "Summon_$npcCode"
}
