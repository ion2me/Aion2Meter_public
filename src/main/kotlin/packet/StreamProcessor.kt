package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.entity.*
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.io.File


class StreamProcessor(private val dataStorage: DataStorage) {
    private val logger = LoggerFactory.getLogger(StreamProcessor::class.java)

    data class VarIntOutput(val value: Int, val length: Int)

    private val mask = 0x0f

    fun onPacketReceived(packet: ByteArray) {
        val packetLengthInfo = readVarInt(packet)
        if (packet.size == packetLengthInfo.value) {
            // 전체 길이가 맞는 경우에만 트레일러(3바이트)를 제외하고 단일 패킷으로 처리한다.
            // logger.trace("현재 바이트길이와 예상 길이가 같음 : {}", toHex(packet.copyOfRange(0, packet.size - 3)))
            parsePerfectPacket(packet.copyOfRange(0, packet.size - 3))
            return
        }
        if (packet.size <= 3) return

        if (packetLengthInfo.value > packet.size) {
            // 길이 정보가 실제보다 큰 경우는 조각난 데이터로 보고 복구 루틴으로 넘긴다.
            // logger.trace("현재 바이트길이가 예상 길이보다 짧음 : {}", toHex(packet))
            parseBrokenLengthPacket(packet)
            return
        }
        if (packetLengthInfo.value <= 3) {
            // 길이 필드가 깨진 경우(의미 없는 값)에는 1바이트씩 밀어 재동기화한다.
            onPacketReceived(packet.copyOfRange(1, packet.size))
            return
        }

        try {
            if (packet.copyOfRange(0, packetLengthInfo.value - 3).size != 3) {
                if (packet.copyOfRange(0, packetLengthInfo.value - 3).isNotEmpty()) {
                    // 프레이밍이 맞는 구간만 먼저 처리하고, 나머지는 재귀로 소비한다.
                    // logger.trace("패킷을 성공적으로 분리함 : {}", toHex(packet.copyOfRange(0, packetLengthInfo.value - 3)))
                    parsePerfectPacket(packet.copyOfRange(0, packetLengthInfo.value - 3))
                }
            }
            onPacketReceived(packet.copyOfRange(packetLengthInfo.value - 3, packet.size))
        } catch (e: Exception) {
            logger.error("패킷 소비중 예외발생 {}", toHex(packet), e)
            return
        }
    }

    // ========================================================================
    // 패킷 라우팅 (순서 중요)
    // ========================================================================
    private fun parsePerfectPacket(packet: ByteArray) {
        if (packet.size < 3) return

        // 맵 변경은 이후 파싱(보스 식별/통계 키)에 영향을 주므로 가장 먼저 처리한다.
        if (parsingMapId(packet)) return

        var flag = parsingDamage(packet)
        if (flag) return

        flag = parsingNickname(packet)
        if (flag) return

        // 엔티티 등록(몬스터/보스/소환수)은 대상 식별에 필요하므로 닉네임 이후에 처리한다.
        flag = parseSummonPacket(packet)
        if (flag) return

        parseDoTPacket(packet)
    }

    // ========================================================================
    // [신규] 맵 ID 파싱
    // ========================================================================
    private fun parsingMapId(packet: ByteArray): Boolean {
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        offset += packetLengthInfo.length

        if (packet.size < offset + 2 + 4) return false

        // Opcode 체크 (00 61)
        if (packet[offset] != 0x00.toByte()) return false
        if (packet[offset + 1] != 0x61.toByte()) return false
        offset += 2

        // LE UInt32 읽기
        val raw = parseUInt32le(packet, offset)          // Int
        val u32 = raw.toLong() and 0xFFFF_FFFFL          // 0..4294967295
        val cand = u32.toInt()

        // 분석 단계에서 오탐을 줄이기 위해 6자리 값만 후보로 인정한다.
        val accepted6 = u32 in 100_000L..999_999L

        // 주변 바이트(패턴용)
        val aroundFrom = maxOf(0, offset - 12)
        val aroundTo = minOf(packet.size, offset + 4 + 12)
        val mapBytesHex = toHex(packet.copyOfRange(offset, offset + 4))
        val aroundHex = toHex(packet.copyOfRange(aroundFrom, aroundTo))

        // 패킷 전체(짧은 편이면 통째로, 길면 앞/뒤만)
        val fullHex = if (packet.size <= 128) {
            toHex(packet)
        } else {
            val head = toHex(packet.copyOfRange(0, 96))
            val tail = toHex(packet.copyOfRange(packet.size - 32, packet.size))
            "$head...$tail"
        }

        // 맵 ID는 다른 키들과 결합되어 통계/매핑에 사용되므로, 기준을 통과한 값만 저장한다.
        if (accepted6) {
            dataStorage.setMapId(cand)
        }

        return true
    }



    // ========================================================================
    // [리팩토링] 소환/등장 패킷 (Entity 객체 생성 및 등록)
    // ========================================================================
    private fun parseSummonPacket(packet: ByteArray): Boolean {
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        offset += packetLengthInfo.length

        // 0x4036 헤더 체크
        if (packet[offset] != 0x40.toByte()) return false
        if (packet[offset + 1] != 0x36.toByte()) return false
        offset += 2

        // 1. Entity ID (Dynamic ID)
        val summonInfo = readVarInt(packet, offset)
        if (summonInfo.length < 0) return false
        val entityId = summonInfo.value

        offset += summonInfo.length + 28

        var foundNpcCode = 0

        // 2. NPC 코드가 유효할 때만 등록해 0/미확정 값으로 엔티티가 오염되는 것을 막는다.
        if (packet.size > offset) {
            val mobInfo = readVarInt(packet, offset)
            if (mobInfo.length >= 0) {
                offset += mobInfo.length

                if (packet.size > offset) {
                    val mobInfo2 = readVarInt(packet, offset)
                    if (mobInfo2.length >= 0) {

                        // 두 위치의 값이 일치할 때만 NPC 코드로 확정한다(오탐 방지).
                        if (mobInfo.value == mobInfo2.value) {
                            foundNpcCode = mobInfo.value

                            if (foundNpcCode != 0) {
                                dataStorage.onNpcObserved(
                                    entityId = entityId,
                                    npcCode = foundNpcCode
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. 소환수는 ownerId를 기준으로 별도 엔티티로 덮어써야 하므로 패턴 매칭으로 식별한다.
        val keyIdx = findArrayIndex(packet, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
        if (keyIdx != -1) {
            val afterPacket = packet.copyOfRange(keyIdx + 8, packet.size)
            val opcodeIdx = findArrayIndex(afterPacket, 0x07, 0x02, 0x06)

            if (opcodeIdx != -1) {
                val ownerOffset = keyIdx + opcodeIdx + 11
                if (ownerOffset + 2 <= packet.size) {
                    val ownerId = parseUInt16le(packet, ownerOffset)

                    // ownerId가 확인된 경우에만 소환수로 등록해, 일반 NPC와 구분한다.
                    val summon = Summon(
                        id = entityId,
                        ownerId = ownerId,
                        npcCode = foundNpcCode
                    )
                    dataStorage.registerEntity(summon)
                    // logger.debug("소환수 등록: 주인[{}] -> 소환수[{}]", ownerId, entityId)
                }
            }
        }

        return true
    }

    // ========================================================================
    // [리팩토링] 닉네임 파싱
    // ========================================================================
    private fun parsingNickname(packet: ByteArray): Boolean {
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        offset += packetLengthInfo.length

        if (packet[offset] != 0x04.toByte()) return false
        if (packet[offset + 1] != 0x8d.toByte()) return false
        offset = 10

        if (offset >= packet.size) return false

        val playerInfo = readVarInt(packet, offset)
        if (playerInfo.length <= 0) return false
        offset += playerInfo.length

        if (offset >= packet.size) return false

        val nicknameLength = packet[offset].toInt()
        if (nicknameLength < 0 || nicknameLength > 72) return false
        if (nicknameLength + offset + 1 > packet.size) return false

        val np = packet.copyOfRange(offset + 1, offset + nicknameLength + 1)
        val nickname = String(np, Charsets.UTF_8)

        // logger.debug("닉네임 발견: {} -> {}", playerInfo.value, nickname)

        // 플레이어 이름은 패킷 수신 순서에 따라 늦게 들어올 수 있으므로 덮어쓰기를 허용한다.
        dataStorage.updatePlayerNickname(playerInfo.value, nickname)

        return true
    }

    // ========================================================================
    // 손상된 패킷 처리 (Broken Length Packet)
    // ========================================================================
    private fun parseBrokenLengthPacket(packet: ByteArray, flag: Boolean = true) {
        if (packet[2] != 0xff.toByte() || packet[3] != 0xff.toByte()) {
            val target = dataStorage.getCurrentTarget()
            var processed = false
            if (target != 0) {
                val targetBytes = convertVarInt(target)
                val damageOpcodes = byteArrayOf(0x04, 0x38)
                val dotOpcodes = byteArrayOf(0x05, 0x38)
                val damageKeyword = damageOpcodes + targetBytes
                val dotKeyword = dotOpcodes + targetBytes
                val damageIdx = findArrayIndex(packet, damageKeyword)
                val dotIdx = findArrayIndex(packet, dotKeyword)

                val (idx, handler) = when {
                    damageIdx > 0 && dotIdx > 0 -> {
                        if (damageIdx < dotIdx) damageIdx to ::parsingDamage
                        else dotIdx to ::parseDoTPacket
                    }
                    damageIdx > 0 -> damageIdx to ::parsingDamage
                    dotIdx > 0 -> dotIdx to ::parseDoTPacket
                    else -> -1 to null
                }

                if (idx > 0 && handler != null) {
                    val packetLengthInfo = readVarInt(packet, idx - 1)
                    if (packetLengthInfo.length == 1) {
                        val startIdx = idx - 1
                        val endIdx = idx - 1 + packetLengthInfo.value - 3
                        if (startIdx in 0..<endIdx && endIdx <= packet.size) {
                            val extractedPacket = packet.copyOfRange(startIdx, endIdx)
                            handler(extractedPacket)
                            processed = true
                            if (endIdx < packet.size) {
                                val remainingPacket = packet.copyOfRange(endIdx, packet.size)
                                parseBrokenLengthPacket(remainingPacket, false)
                            }
                        }
                    }
                }
            }
            if (flag && !processed) {
                // 데미지/도트 복구에 실패한 경우에만 닉네임 추정을 시도해 오탐을 줄인다.
                parseNicknameFromBrokenLengthPacket(packet)
            }
            return
        }
        val newPacket = packet.copyOfRange(10, packet.size)
        onPacketReceived(newPacket)
    }

    private fun parseNicknameFromBrokenLengthPacket(packet: ByteArray) {
        var originOffset = 0
        while (originOffset < packet.size) {
            val info = readVarInt(packet, originOffset)
            if (info.length == -1) {
                return
            }
            val innerOffset = originOffset + info.length

            if (innerOffset + 6 >= packet.size) {
                originOffset++
                continue
            }

            // 패턴 1
            if (packet[innerOffset + 3] == 0x01.toByte() && packet[innerOffset + 4] == 0x07.toByte()) {
                val possibleNameLength = packet[innerOffset + 5].toInt() and 0xff
                if (innerOffset + 6 + possibleNameLength <= packet.size) {
                    val possibleNameBytes = packet.copyOfRange(innerOffset + 6, innerOffset + 6 + possibleNameLength)
                    val name = String(possibleNameBytes, Charsets.UTF_8)
                    if (hasPossibilityNickname(name)) {
                        // logger.debug("1번패턴 예상 닉네임 : {}", name)
                        dataStorage.updatePlayerNickname(info.value, name)
                        originOffset++
                    }
                }
            }
            // 패턴 2
            if (packet.size > innerOffset + 3 && packet[innerOffset + 1] == 0x00.toByte()) {
                val possibleNameLength = packet[innerOffset + 2].toInt() and 0xff
                if (packet.size >= innerOffset + possibleNameLength + 3 && possibleNameLength.toInt() != 0) {
                    val possibleNameBytes = packet.copyOfRange(innerOffset + 3, innerOffset + possibleNameLength + 3)
                    val name = String(possibleNameBytes, Charsets.UTF_8)
                    if (hasPossibilityNickname(name)) {
                        // logger.debug("2번패턴 예상 닉네임 : {}", name)
                        dataStorage.updatePlayerNickname(info.value, name)
                        originOffset++
                    }
                }
            }
            // 패턴 3
            if (packet.size > innerOffset + 5) {
                if (packet[innerOffset + 3] == 0x00.toByte() && packet[innerOffset + 4] == 0x07.toByte()) {
                    val possibleNameLength = packet[innerOffset + 5].toInt() and 0xff
                    if (packet.size > innerOffset + possibleNameLength + 6) {
                        val possibleNameBytes = packet.copyOfRange(innerOffset + 6, innerOffset + possibleNameLength + 6)
                        val name = String(possibleNameBytes, Charsets.UTF_8)
                        if (hasPossibilityNickname(name)) {
                            // logger.debug("신규 패턴 예상 닉네임 : {}", name)
                            dataStorage.updatePlayerNickname(info.value, name)
                            originOffset++
                        }
                    }
                }
            }
            originOffset++
        }
    }

    private fun hasPossibilityNickname(nickname: String): Boolean {
        if (nickname.isEmpty()) return false
        val regex = Regex("^[가-힣a-zA-Z0-9]+$")
        if (!regex.matches(nickname)) return false
        val onlyNumbers = Regex("^[0-9]+$")
        if (onlyNumbers.matches(nickname)) return false
        val oneAlphabet = Regex("^[A-Za-z]$")
        return !oneAlphabet.matches(nickname)
    }

    // ========================================================================
    // 데미지 파싱 (로그 추가됨)
    // ========================================================================
    private fun parsingDamage(packet: ByteArray): Boolean {
        // 특정 헤더(0x20)로 시작하는 패킷은 데미지 포맷이 아니므로 빠르게 배제한다.
        if (packet[0] == 0x20.toByte()) return false
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        val pdp = ParsedDamagePacket()

        offset += packetLengthInfo.length

        if (offset >= packet.size) return false
        if (packet[offset] != 0x04.toByte()) return false
        if (packet[offset + 1] != 0x38.toByte()) return false
        offset += 2
        if (offset >= packet.size) return false
        val targetInfo = readVarInt(packet, offset)
        if (targetInfo.length < 0) return false
        pdp.setTargetId(targetInfo)
        offset += targetInfo.length
        if (offset >= packet.size) return false

        val switchInfo = readVarInt(packet, offset)
        if (switchInfo.length < 0) return false
        pdp.setSwitchVariable(switchInfo)
        offset += switchInfo.length
        if (offset >= packet.size) return false

        val flagInfo = readVarInt(packet, offset)
        if (flagInfo.length < 0) return false
        pdp.setFlag(flagInfo)
        offset += flagInfo.length
        if (offset >= packet.size) return false

        val actorInfo = readVarInt(packet, offset)
        if (actorInfo.length < 0) return false
        pdp.setActorId(actorInfo)
        offset += actorInfo.length
        if (offset >= packet.size) return false

        if (offset + 5 >= packet.size) return false

        val temp = offset
        val skillCode = parseUInt32le(packet, offset)
        pdp.setSkillCode(skillCode)

        offset = temp + 5

        val typeInfo = readVarInt(packet, offset)
        if (typeInfo.length < 0) return false
        pdp.setType(typeInfo)
        offset += typeInfo.length
        if (offset >= packet.size) return false

        // val damageType = packet[offset]

        // switch 하위 비트에 따라 특수 플래그 블록 길이가 달라지므로, 먼저 길이를 산출한 뒤 슬라이스한다.
        val andResult = switchInfo.value and mask
        val start = offset
        var tempV = 0
        tempV += when (andResult) {
            4 -> 8
            5 -> 12
            6 -> 10
            7 -> 14
            else -> return false
        }
        if (start + tempV > packet.size) return false
        pdp.setSpecials(parseSpecialDamageFlags(packet.copyOfRange(start, start + tempV)))
        offset += tempV

        if (offset >= packet.size) return false

        val unknownInfo = readVarInt(packet, offset)
        if (unknownInfo.length < 0) return false
        pdp.setUnknown(unknownInfo)
        offset += unknownInfo.length
        if (offset >= packet.size) return false

        val damageInfo = readVarInt(packet, offset)
        if (damageInfo.length < 0) return false
        pdp.setDamage(damageInfo)
        offset += damageInfo.length
        if (offset >= packet.size) return false

        val loopInfo = readVarInt(packet, offset)
        if (loopInfo.length < 0) return false
        pdp.setLoop(loopInfo)
        offset += loopInfo.length

        // 운영 환경에서는 과도한 로그가 성능/디스크를 압박할 수 있으므로 필요 시 레벨 조정이 필요하다.
        logger.debug("⚔️ 일반: 피격자: {}, 공격자: {}, 타입: {}, 데미지: {}, 플래그: {}",
            pdp.getTargetId(),
            pdp.getActorId(),
            pdp.getType(),
            pdp.getDamage(),
            pdp.getSpecials()
        )

        if (pdp.getActorId() != pdp.getTargetId()) {
            dataStorage.appendDamage(pdp)
        }
        return true
    }

    // ========================================================================
    // 도트 데미지 파싱 (로그 추가됨)
    // ========================================================================
    private fun parseDoTPacket(packet: ByteArray) {
        var offset = 0
        val pdp = ParsedDamagePacket()
        pdp.setDot(true)
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return
        offset += packetLengthInfo.length

        if (packet[offset] != 0x05.toByte()) return
        if (packet[offset + 1] != 0x38.toByte()) return
        offset += 2
        if (packet.size < offset) return

        val targetInfo = readVarInt(packet, offset)
        if (targetInfo.length < 0) return
        offset += targetInfo.length
        if (packet.size < offset) return
        pdp.setTargetId(targetInfo)

        // 도트 포맷은 고정 1바이트가 끼어 있어 오프셋을 수동으로 보정한다.
        offset += 1
        if (packet.size < offset) return

        val actorInfo = readVarInt(packet, offset)
        if (actorInfo.length < 0) return
        if (actorInfo.value == targetInfo.value) return
        offset += actorInfo.length
        if (packet.size < offset) return
        pdp.setActorId(actorInfo)

        val unknownInfo = readVarInt(packet, offset)
        if (unknownInfo.length < 0) return
        offset += unknownInfo.length

        val skillCode: Int = parseUInt32le(packet, offset) / 100
        offset += 4
        if (packet.size <= offset) return
        pdp.setSkillCode(skillCode)

        val damageInfo = readVarInt(packet, offset)
        if (damageInfo.length < 0) return
        pdp.setDamage(damageInfo)

        // 운영 환경에서는 과도한 로그가 성능/디스크를 압박할 수 있으므로 필요 시 레벨 조정이 필요하다.
        logger.debug("🩸 도트: 공격자 {}, 피격자 {}, 데미지 {}",
            pdp.getActorId(),
            pdp.getTargetId(),
            pdp.getDamage()
        )

        if (pdp.getActorId() != pdp.getTargetId()) {
            dataStorage.appendDamage(pdp)
        }
    }

    // ========================================================================
    // 유틸리티 메서드
    // ========================================================================

    private fun findArrayIndex(data: ByteArray, vararg pattern: Int): Int {
        if (pattern.isEmpty()) return 0
        val p = ByteArray(pattern.size) { pattern[it].toByte() }
        val lps = IntArray(p.size)
        var len = 0
        for (i in 1 until p.size) {
            while (len > 0 && p[i] != p[len]) len = lps[len - 1]
            if (p[i] == p[len]) len++
            lps[i] = len
        }
        var i = 0
        var j = 0
        while (i < data.size) {
            if (data[i] == p[j]) {
                i++; j++
                if (j == p.size) return i - j
            } else if (j > 0) {
                j = lps[j - 1]
            } else {
                i++
            }
        }
        return -1
    }

    private fun findArrayIndex(data: ByteArray, p: ByteArray): Int {
        val lps = IntArray(p.size)
        var len = 0
        for (i in 1 until p.size) {
            while (len > 0 && p[i] != p[len]) len = lps[len - 1]
            if (p[i] == p[len]) len++
            lps[i] = len
        }
        var i = 0
        var j = 0
        while (i < data.size) {
            if (data[i] == p[j]) {
                i++; j++
                if (j == p.size) return i - j
            } else if (j > 0) {
                j = lps[j - 1]
            } else {
                i++
            }
        }
        return -1
    }

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntOutput {
        var value = 0
        var shift = 0
        var count = 0
        while (true) {
            if (offset + count >= bytes.size) {
                // logger.error("배열범위초과, 패킷 {} 오프셋 {} count {}", toHex(bytes), offset, count)
                return VarIntOutput(-1, -1)
            }
            val byteVal = bytes[offset + count].toInt() and 0xff
            count++
            value = value or (byteVal and 0x7F shl shift)
            if ((byteVal and 0x80) == 0) return VarIntOutput(value, count)
            shift += 7
            if (shift >= 32) return VarIntOutput(-1, -1)
        }
    }

    fun convertVarInt(value: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var num = value
        while (num > 0x7F) {
            bytes.add(((num and 0x7F) or 0x80).toByte())
            num = num ushr 7
        }
        bytes.add(num.toByte())
        return bytes.toByteArray()
    }

    private fun parseUInt16le(packet: ByteArray, offset: Int = 0): Int {
        return (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun parseUInt32le(packet: ByteArray, offset: Int = 0): Int {
        require(offset + 4 <= packet.size) { "패킷 길이가 필요길이보다 짧음" }
        return ((packet[offset].toInt() and 0xFF)) or
                ((packet[offset + 1].toInt() and 0xFF) shl 8) or
                ((packet[offset + 2].toInt() and 0xFF) shl 16) or
                ((packet[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun parseSpecialDamageFlags(packet: ByteArray): List<SpecialDamage> {
        val flags = mutableListOf<SpecialDamage>()
        if (packet.size == 8) return emptyList()
        if (packet.size >= 10) {
            val flagByte = packet[0].toInt() and 0xFF
            if ((flagByte and 0x01) != 0) flags.add(SpecialDamage.BACK)
            if ((flagByte and 0x02) != 0) flags.add(SpecialDamage.UNKNOWN)
            if ((flagByte and 0x04) != 0) flags.add(SpecialDamage.PARRY)
            if ((flagByte and 0x08) != 0) flags.add(SpecialDamage.PERFECT)
            if ((flagByte and 0x10) != 0) flags.add(SpecialDamage.DOUBLE)
            if ((flagByte and 0x20) != 0) flags.add(SpecialDamage.ENDURE)
            if ((flagByte and 0x40) != 0) flags.add(SpecialDamage.UNKNOWN4)
            if ((flagByte and 0x80) != 0) flags.add(SpecialDamage.POWER_SHARD)
        }
        return flags
    }
}
