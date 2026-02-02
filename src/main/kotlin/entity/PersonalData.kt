// Path: com/tbread/entity/PersonalData.kt
package com.tbread.entity

import com.tbread.DpsCalculator
import com.tbread.SkillMetadata
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PersonalData(
    @Required var job: String = "",
    var dps: Double = 0.0,
    @Transient var amount: Double = 0.0,
    @Required var damageContribution: Double = 0.0,
    @Transient val analyzedData: MutableMap<Int, AnalyzedSkill> = mutableMapOf(),
    val nickname: String
) {
    private fun addDamage(damage: Double) {
        amount += damage
    }

    fun processPdp(pdp: ParsedDamagePacket) {
        addDamage(pdp.getDamage().toDouble())

        // 1. Raw ID (특화 정보가 포함된 원본 패킷의 코드)를 Key로 사용하여 데이터 분리
        val rawSkillCode = pdp.getSkillCode1()

        if (!analyzedData.containsKey(rawSkillCode)) {
            // 2. 이름 생성 및 특화 포맷팅 로직
            val originSkillCode = SkillMetadata.inferOriginalSkillCode(rawSkillCode) ?: rawSkillCode
            val baseName = SkillMetadata.SKILL_MAP[originSkillCode] ?: rawSkillCode.toString()

            // 3. 오프셋 계산 (예: 120 -> 특화 정보 추출)
            val offset = rawSkillCode - originSkillCode

            // 4. 특화 표시 포맷 적용: 숫자를 하나씩 분리하여 [n] 형태로 변환 (예: 120 -> [1][2][0])
            val specDisplay = if (offset > 0) {
                val bracketed = offset.toString().map { "[$it]" }.joinToString("")
                " (특화: $bracketed)"
            } else ""

            // 5. 최종 표시 이름 설정 및 객체 생성 (수정된 AnalyzedSkill 생성자 사용)
            val analyzedSkill = AnalyzedSkill(rawSkillCode, "$baseName$specDisplay")
            analyzedData[rawSkillCode] = analyzedSkill
        }

        val analyzedSkill = analyzedData[rawSkillCode]!!
        if (pdp.isDoT()) {
            analyzedSkill.dotTimes ++
            analyzedSkill.dotDamageAmount += pdp.getDamage()
        } else {
            analyzedSkill.times++
            analyzedSkill.damageAmount += pdp.getDamage()
            if (pdp.isCrit()) analyzedSkill.critTimes++
            if (pdp.getSpecials().contains(SpecialDamage.BACK)) analyzedSkill.backTimes++
            if (pdp.getSpecials().contains(SpecialDamage.PARRY)) analyzedSkill.parryTimes++
            if (pdp.getSpecials().contains(SpecialDamage.DOUBLE)) analyzedSkill.doubleTimes++
            if (pdp.getSpecials().contains(SpecialDamage.PERFECT)) analyzedSkill.perfectTimes++
        }
    }
}