package com.tbread.packet

import com.tbread.config.PcapCapturerConfig
import kotlinx.coroutines.channels.Channel
import org.pcap4j.core.Pcaps
import org.pcap4j.core.PcapNetworkInterface
import org.pcap4j.packet.TcpPacket
import org.slf4j.LoggerFactory
import java.net.Inet4Address

class PcapCapturer(private val config: PcapCapturerConfig, private val channel: Channel<ByteArray>) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun start() {
        // 1. 장치 목록 가져오기
        val nifs = Pcaps.findAllDevs() ?: emptyList()
        if (nifs.isEmpty()) {
            logger.error("❌ 네트워크 장치를 찾을 수 없습니다. (Npcap/Wireshark 설치 확인 및 관리자 권한 실행 필요)")
            return
        }

        var targetDevice: PcapNetworkInterface? = null
        val osName = System.getProperty("os.name").lowercase()

        // -------------------------------------------------------------------------
        // [전략 0] 수동 지정 (Config에 'pcap.device' 값이 있을 경우 최우선)
        // -------------------------------------------------------------------------
        if (config.targetDeviceKeyword != null) {
            targetDevice = nifs.find {
                it.name.contains(config.targetDeviceKeyword, true) ||
                        (it.description != null && it.description.contains(config.targetDeviceKeyword, true))
            }
            if (targetDevice != null) {
                logger.info("🔧 설정된 키워드('${config.targetDeviceKeyword}')로 장치를 선택했습니다.")
            }
        }

        // 수동 설정이 없거나 못 찾았을 경우 자동 감지 시작
        if (targetDevice == null) {
            if (osName.contains("mac")) {
                // ---------------------------------------------------------------------
                // [전략 1] MacOS 자동 감지
                // ---------------------------------------------------------------------
                logger.info("🍎 MacOS 환경 감지됨")
                targetDevice = nifs.find { it.name.equals("bridge100", ignoreCase = true) }
                if (targetDevice == null) {
                    targetDevice = nifs.find { it.name.startsWith("en") && it.addresses.isNotEmpty() }
                }

            } else {
                // ---------------------------------------------------------------------
                // [전략 2] Windows 자동 감지 (수정됨)
                // ---------------------------------------------------------------------
                logger.info("🪟 Windows 환경 감지됨 - 스마트 장치 필터링 시작")

                // [필터 함수] 제외할 장치 키워드 (블루투스, 가상머신 등)
                fun isSuspiciousDevice(device: PcapNetworkInterface): Boolean {
                    val desc = (device.description ?: "").lowercase()
                    val name = device.name.lowercase()
                    val ignoreKeywords = listOf("bluetooth", "virtual", "vmware", "vpn", "loopback", "hyper-v", "npcap loopback")

                    return ignoreKeywords.any { desc.contains(it) || name.contains(it) }
                }

                // [2-1] 원컴 사용자용: 실제 외부 IP(IPv4)를 가진 활성 장치 우선
                // 수정사항: LinkLocalAddress(169.254.x.x) 제외 및 Bluetooth 이름 제외
                targetDevice = nifs.find { device ->
                    // 1차 필터: 의심스러운 장치 이름 제외
                    if (isSuspiciousDevice(device)) return@find false

                    // 2차 필터: 유효한 IP 확인
                    device.addresses.any { addr ->
                        val ip = addr.address
                        ip is Inet4Address &&
                                !ip.isLoopbackAddress &&
                                !ip.isLinkLocalAddress && // <--- 핵심 수정: APIPA(169.254.xx.xx) 제외
                                ip.hostAddress != "0.0.0.0"
                    }
                }

                // [2-2] 투컴(미러링) 사용자용: IP는 없지만 케이블이 연결된 이더넷 장치
                if (targetDevice == null) {
                    logger.info("⚠️ 유효한 IP(인터넷 연결)를 가진 장치가 없습니다. 미러링(No IP) 장치를 탐색합니다.")
                    targetDevice = nifs.find { device ->
                        !device.isLoopBack &&
                                !isSuspiciousDevice(device) && // 블루투스 등 제외
                                (device.description?.contains("Ethernet", true) == true ||
                                        device.description?.contains("Adapter", true) == true ||
                                        device.description?.contains("Wi-Fi", true) == true ||
                                        device.description?.contains("Wireless", true) == true ||
                                        device.description?.contains("Realtek", true) == true ||
                                        device.description?.contains("Intel", true) == true)
                    }
                }
            }
        }

        // -------------------------------------------------------------------------
        // [전략 3] 최후의 수단: 그래도 없으면 목록의 첫 번째 장치 선택
        // -------------------------------------------------------------------------
        if (targetDevice == null && nifs.isNotEmpty()) {
            logger.warn("⚠️ 적절한 장치를 자동으로 찾지 못해 목록의 첫 번째 장치를 선택합니다. (권장하지 않음)")
            nifs.forEachIndexed { index, device ->
                logger.info("   [$index] ${device.description} (${device.name}) - IPs: ${device.addresses.size}")
            }
            targetDevice = nifs[0]
        }

        if (targetDevice == null) {
            logger.error("❌ 사용할 네트워크 장치를 결정하지 못했습니다.")
            return
        }

        // -------------------------------------------------------------------------
        // 캡처 시작
        // -------------------------------------------------------------------------
        logger.info("✅ 선택된 장치: ${targetDevice.description} [${targetDevice.name}]")
        logger.info("   IP 정보: ${targetDevice.addresses.joinToString { it.address.hostAddress }}")
        logger.info("   스니핑 시작... (Filter: tcp port ${config.serverPort}, Target Net: ${config.serverIp})")

        val handle = targetDevice.openLive(config.snapshotSize, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, config.timeout)
        val filter = "tcp and net ${config.serverIp} and (port ${config.serverPort})"

        try {
            handle.setFilter(filter, org.pcap4j.core.BpfProgram.BpfCompileMode.OPTIMIZE)
        } catch (e: Exception) {
            logger.error("❌ 필터 설정 실패 (BPF 구문 오류 가능성): $filter", e)
            handle.close()
            return
        }

        val listener = { packet: org.pcap4j.packet.Packet ->
            try {
                if (packet.contains(TcpPacket::class.java)) {
                    val tcpPacket = packet.get(TcpPacket::class.java)
                    val payload = tcpPacket.payload
                    if (payload != null) {
                        val data = payload.rawData
                        if (data.isNotEmpty()) {
                            channel.trySend(data)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.trace("패킷 처리 중 오류", e)
            }
        }

        try {
            handle.loop(-1, listener)
        } catch (e: InterruptedException) {
            logger.info("🛑 스니핑이 중단되었습니다.")
        } catch (e: Exception) {
            logger.error("❌ 캡처 루프 중 치명적 오류 발생", e)
        } finally {
            if (handle.isOpen) {
                handle.close()
            }
        }
    }
}