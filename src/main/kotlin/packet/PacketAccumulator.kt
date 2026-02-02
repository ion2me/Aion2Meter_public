package com.tbread.packet

import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

class PacketAccumulator {
    private val logger = LoggerFactory.getLogger(PacketAccumulator::class.java)

    // 10MB 버퍼 (충분히 크게 잡아 링버퍼 오버헤드 방지)
    private val CAPACITY = 10 * 1024 * 1024
    private val buffer = ByteArray(CAPACITY)

    private var readPos = 0  // 데이터 시작점
    private var writePos = 0 // 데이터 끝점 (다음 쓸 위치)
    private var count = 0    // 현재 유효 데이터 크기

    private val lock = ReentrantLock()

    /**
     * 데이터를 링버퍼에 추가 (Append)
     * 배열 복사 없이 포인터 이동만으로 처리
     */
    fun append(data: ByteArray) {
        lock.withLock {
            if (count + data.size > CAPACITY) {
                logger.error("버퍼 오버플로우 발생! 데이터 유실 가능성 있음. (현재: $count, 추가: ${data.size})")
                reset() // 안전을 위해 초기화 (기존 로직 유지)
                return
            }

            // 1. 버퍼 끝까지 남은 공간 계산
            val spaceToEnd = CAPACITY - writePos
            val copyLen = min(data.size, spaceToEnd)

            // 2. 끝부분 채우기
            System.arraycopy(data, 0, buffer, writePos, copyLen)

            // 3. 앞부분으로 넘어가서 남은거 채우기 (Wrap around)
            if (data.size > copyLen) {
                System.arraycopy(data, copyLen, buffer, 0, data.size - copyLen)
            }

            // 4. 포인터 업데이트
            writePos = (writePos + data.size) % CAPACITY
            count += data.size
        }
    }

    /**
     * 매직 패킷(06 00 36) 검색
     * 링버퍼 경계를 넘어서 이어져 있어도 찾을 수 있음
     * @return 찾은 패턴의 시작 인덱스 (상대 좌표 0 ~ count-1), 없으면 -1
     */
    fun indexOf(target: ByteArray): Int {
        lock.withLock {
            if (count < target.size) return -1

            // 검색 범위: 0 부터 (데이터길이 - 타겟길이)
            for (i in 0..count - target.size) {
                var match = true
                for (j in target.indices) {
                    // (readPos + i + j) % CAPACITY : 원형 인덱스 계산
                    val bufferIdx = (readPos + i + j) % CAPACITY
                    if (buffer[bufferIdx] != target[j]) {
                        match = false
                        break
                    }
                }
                if (match) return i // 상대 인덱스 반환
            }
            return -1
        }
    }

    /**
     * 데이터 읽기 및 삭제 (Consume)
     * 버퍼 내부 데이터를 복사하지 않고, 읽은 만큼 포인터만 이동 (O(1) discard)
     * @param length 읽어낼 바이트 수
     */
    fun readAndDiscard(length: Int): ByteArray {
        lock.withLock {
            if (length <= 0) return ByteArray(0)
            if (length > count) {
                logger.warn("요청 길이가 버퍼 크기보다 큽니다. 전체 반환")
                return readAndDiscard(count)
            }

            val result = ByteArray(length)

            // 1. 버퍼 끝까지 읽을 수 있는 양
            val spaceToEnd = CAPACITY - readPos
            val copyLen = min(length, spaceToEnd)

            // 2. 데이터 복사 (추출)
            System.arraycopy(buffer, readPos, result, 0, copyLen)

            // 3. 경계 넘어간 부분 복사 (Wrap around)
            if (length > copyLen) {
                System.arraycopy(buffer, 0, result, copyLen, length - copyLen)
            }

            // 4. 읽은 만큼 포인터 이동 (실제 데이터 삭제 효과) -> 핵심 성능 개선 포인트
            readPos = (readPos + length) % CAPACITY
            count -= length

            return result
        }
    }

    fun reset() {
        lock.withLock {
            readPos = 0
            writePos = 0
            count = 0
            logger.info("버퍼가 초기화되었습니다.")
        }
    }
}