package com.tbread.entity

import java.util.UUID

data class TargetInfo(
    private val targetId: Int,
    private var damagedAmount: Int = 0,
    private var targetDamageStarted: Long,
    private var targetDamageEnded: Long,
    private val processedUuid: MutableSet<UUID> = mutableSetOf(),
) {
    // 최근 N초 동안의 누적 피해량을 계산하기 위한 슬라이딩 윈도우(시간순 큐)와 합계 캐시.
    private val damageWindow = ArrayDeque<Pair<Long, Int>>()
    private var recentDamageSum: Long = 0L

    // UI/표시용 '최근 딜량'의 기준 구간(밀리초).
    private val WINDOW_SIZE_MS = 20_000L

    fun processedUuid(): MutableSet<UUID> {
        return processedUuid
    }

    fun damagedAmount(): Int {
        return damagedAmount
    }

    fun targetId(): Int {
        return targetId
    }

    // 최근 구간 합계는 누적합 캐시로 즉시 반환한다.
    fun getRecentDamage(): Long = recentDamageSum
    fun getLastHitTime(): Long = targetDamageEnded

    fun processPdp(pdp: ParsedDamagePacket) {
        // 패킷 중복 처리 방지: 동일 UUID는 집계/윈도우 모두 반영하지 않는다.
        if (processedUuid.contains(pdp.getUuid())) return

        val dmg = pdp.getDamage()
        val ts = pdp.getTimeStamp()

        // 전체 누적은 전투 로그 검증/통계 재계산에 사용될 수 있으므로 항상 증가시킨다.
        damagedAmount += dmg
        processedUuid.add(pdp.getUuid())

        // 전투 시간 범위는 실제 타격 시각의 최솟값/최댓값으로 유지한다.
        if (ts < targetDamageStarted) targetDamageStarted = ts
        if (ts > targetDamageEnded) targetDamageEnded = ts

        // 최근 구간 집계는 "추가 후 오래된 항목 제거" 순서로 처리해 합계 캐시를 유지한다.
        damageWindow.addLast(ts to dmg)
        recentDamageSum += dmg

        // 윈도우 기준보다 오래된 항목은 시간순으로 제거하며 합계 캐시에서도 차감한다.
        while (damageWindow.isNotEmpty() && damageWindow.first().first < (ts - WINDOW_SIZE_MS)) {
            val removed = damageWindow.removeFirst()
            recentDamageSum -= removed.second
        }
    }

    fun parseBattleTime():Long{
        return targetDamageEnded - targetDamageStarted
    }

    fun getEffectiveRecentDamage(now: Long): Long {
        // 최근 딜량은 "현재 진행 중인 전투"에만 의미가 있으므로, 마지막 타격 이후 일정 시간이 지나면 0으로 본다.
        if (now - targetDamageEnded > 10_000) {
            return 0L
        }
        return recentDamageSum
    }

}
