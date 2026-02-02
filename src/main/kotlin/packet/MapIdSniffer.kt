//package com.tbread.packet
//
//import org.slf4j.LoggerFactory
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//
//class MapIdSniffer {
//    private val logger = LoggerFactory.getLogger(MapIdSniffer::class.java)
//
//    // 찾으려는 맵 ID (불의 신전: 600022)
//    private val targetMapId = 600022
//
//    // 검색 패턴 캐싱
//    private val patternInt32: ByteArray
//    private val patternVarInt: ByteArray
//
//    init {
//        // 1. Int32 Little Endian 패턴 생성 (D6 27 09 00)
//        patternInt32 = ByteBuffer.allocate(4)
//            .order(ByteOrder.LITTLE_ENDIAN)
//            .putInt(targetMapId)
//            .array()
//
//        // 2. VarInt 패턴 생성 (D6 CF 24)
//        patternVarInt = toVarIntBytes(targetMapId)
//
//        logger.info("MapSniffer initialized. Target: $targetMapId")
//        logger.info("Search Pattern [Int32]: ${toHex(patternInt32)}")
//        logger.info("Search Pattern [VarInt]: ${toHex(patternVarInt)}")
//    }
//
//    fun scan(packet: ByteArray) {
//        // 패킷이 너무 짧으면 패스
//        if (packet.size < 3) return
//
//        // 1. Int32 패턴 검색
//        val idx32 = findArrayIndex(packet, patternInt32)
//        if (idx32 != -1) {
//            printFoundLog("Int32 (4Byte)", idx32, packet)
//        }
//
//        // 2. VarInt 패턴 검색
//        val idxVar = findArrayIndex(packet, patternVarInt)
//        if (idxVar != -1) {
//            printFoundLog("VarInt (Variable)", idxVar, packet)
//        }
//    }
//
//    private fun printFoundLog(type: String, index: Int, packet: ByteArray) {
//        logger.info("================================================================")
//        logger.info("[MAP ID FOUND] Type: $type, Index: $index")
//        logger.info("Target ID: $targetMapId")
//        // 전체 패킷 덤프
//        logger.info("Full Packet Hex: ${toHex(packet)}")
//
//        // 헤더 추정 (보통 VarInt 길이 정보 뒤에 오는 2바이트가 Opcode일 확률이 높음)
//        // 인덱스 앞쪽의 바이트들을 유심히 보세요.
//        val startRange = if(index - 5 > 0) index - 5 else 0
//        logger.info("Nearby Bytes (Pre-data): ${toHex(packet.copyOfRange(startRange, index))}")
//        logger.info("================================================================")
//    }
//
//    private fun findArrayIndex(data: ByteArray, pattern: ByteArray): Int {
//        // KMP 알고리즘까지는 필요 없고 단순 검색
//        for (i in 0..data.size - pattern.size) {
//            var found = true
//            for (j in pattern.indices) {
//                if (data[i + j] != pattern[j]) {
//                    found = false
//                    break
//                }
//            }
//            if (found) return i
//        }
//        return -1
//    }
//
//    private fun toVarIntBytes(value: Int): ByteArray {
//        val bytes = mutableListOf<Byte>()
//        var v = value
//        while ((v and 0xFFFFFF80.toInt()) != 0) {
//            bytes.add(((v and 0x7F) or 0x80).toByte())
//            v = v ushr 7
//        }
//        bytes.add((v and 0x7F).toByte())
//        return bytes.toByteArray()
//    }
//
//    private fun toHex(bytes: ByteArray): String {
//        return bytes.joinToString(" ") { "%02X".format(it) }
//    }
//}