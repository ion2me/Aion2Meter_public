package com.tbread.packet

import org.slf4j.LoggerFactory

class StreamAssembler(private val processor: StreamProcessor) {
    private val logger = LoggerFactory.getLogger(StreamAssembler::class.java)

    // 새로 만든 Ring Buffer 기반 Accumulator 사용
    private val buffer = PacketAccumulator()

    // 기준이 되는 매직 패킷
    private val MAGIC_PACKET = byteArrayOf(0x06.toByte(), 0x00.toByte(), 0x36.toByte())

    suspend fun processChunk(chunk: ByteArray) {
        // 1. 들어온 청크를 무조건 버퍼 뒤에 붙임 (O(1) ~ O(N) copy, but fast)
        buffer.append(chunk)

        // 2. 버퍼 내에서 완성된 패킷이 있는지 반복해서 확인
        while (true) {
            // 매직 패킷 위치 검색 (상대 인덱스)
            val foundIndex = buffer.indexOf(MAGIC_PACKET)

            if (foundIndex == -1) {
                // 매직 패킷이 없으면 데이터가 더 들어올 때까지 대기
                break
            }

            // 자를 위치 계산 (매직 패킷 포함)
            // 예: [Data] [06 00 36] ...
            // foundIndex가 [06]의 시작점이므로, 길이는 foundIndex + 3
            val packetLength = foundIndex + MAGIC_PACKET.size

            // 3. 패킷 추출 및 버퍼에서 제거 (Atomic Operation)
            // 기존: getRange() -> process -> discardBytes() (3단계, 2번 복사)
            // 변경: readAndDiscard() (1단계, 1번 추출 복사, 내부 이동 없음)
            val fullPacket = buffer.readAndDiscard(packetLength)

            // 4. 프로세서로 전달
            if (fullPacket.isNotEmpty()) {
                // 비동기 처리 중 에러가 나도 다음 패킷 파싱에 영향 없도록 안전장치
                try {
                    processor.onPacketReceived(fullPacket)
                } catch (e: Exception) {
                    logger.error("패킷 처리 중 오류 발생", e)
                }
            }
        }
    }
}