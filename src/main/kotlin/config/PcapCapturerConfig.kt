package com.tbread.config

import com.tbread.packet.PropertyHandler
import org.slf4j.LoggerFactory

data class PcapCapturerConfig(
    val serverIp: String,
    val serverPort: String,
    val timeout: Int = 10,
    val snapshotSize: Int = 65536,
    val targetDeviceKeyword: String? = null // [추가] 장치 이름 수동 지정용
) {
    companion object {
        private val logger = LoggerFactory.getLogger(javaClass.enclosingClass)
        fun loadFromProperties(): PcapCapturerConfig {
            val ip = PropertyHandler.getProperty("server.ip") ?: "206.127.156.0/24"
            val port = PropertyHandler.getProperty("server.port") ?: "13328"
            val timeout = PropertyHandler.getProperty("server.timeout")?.toInt() ?: 10
            val snapSize = PropertyHandler.getProperty("server.maxSnapshotSize")?.toInt() ?: 65536

            // 프로퍼티에서 읽어오기 (비어있으면 null)
            val deviceKeyword = PropertyHandler.getProperty("pcap.device")?.ifBlank { null }

            logger.debug("{},{},{},{}, Device:{}", ip, port, timeout, snapSize, deviceKeyword)
            logger.info("프로퍼티스 초기화 완료")
            return PcapCapturerConfig(ip, port, timeout, snapSize, deviceKeyword)
        }
    }
}