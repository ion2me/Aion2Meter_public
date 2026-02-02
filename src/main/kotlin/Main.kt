package com.tbread

import com.tbread.config.PcapCapturerConfig
import com.tbread.packet.PcapCapturer
import com.tbread.packet.StreamAssembler
import com.tbread.packet.StreamProcessor
import com.tbread.webview.WebServer
import com.tbread.db.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI
import kotlin.concurrent.timer

// 자동 저장은 전투 종료를 폴링으로 판단하므로, 주기 호출이 누락되지 않도록 별도 스케줄러로 분리한다.
fun startAutoSaveScheduler(dataStorage: DataStorage) {
    // 저장 조건은 보수적이므로, 너무 촘촘하지 않은 주기로 호출해도 충분하다.
    timer(period = 5000) {
        try {
            dataStorage.dpsCalculator.checkAndAutoSave()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun main() = runBlocking {
    // DB는 테이블/스키마 준비가 선행되어야 하므로, 패킷 처리 시작 전에 초기화한다.
    DatabaseManager.init()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        println("thread dead ${t.name}")
        e.printStackTrace()
    }

    // 캡처 스레드가 생산하는 청크를 흡수하기 위한 버퍼이며, 밀리면 최신 데이터를 유지하도록 drop 정책을 둔다.
    val channel = Channel<ByteArray>(50000, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val config = PcapCapturerConfig.loadFromProperties()

    // 집계 상태와 계산기는 동일 라이프사이클을 가져야 하므로 DataStorage를 단일 인스턴스로 유지한다.
    val dataStorage = DataStorage()

    val processor = StreamProcessor(dataStorage)
    val assembler = StreamAssembler(processor)
    val capturer = PcapCapturer(config, channel)

    // WebServer는 DataStorage 내부 계산기를 참조해야 UI와 저장 데이터가 동일 스트림을 보게 된다.
    val calculator = dataStorage.dpsCalculator

    // 패킷 조립/파싱은 CPU 작업이므로 Default 디스패처에서 처리한다.
    launch(Dispatchers.Default) {
        for (chunk in channel) {
            assembler.processChunk(chunk)
        }
    }

    // 캡처는 블로킹 I/O 성격이 강하므로 IO 디스패처에서 실행한다.
    launch(Dispatchers.IO) {
        capturer.start()
    }

    // 자동 저장은 별도 폴링이 필요하므로, 서버 시작과 함께 스케줄러를 올린다.
    startAutoSaveScheduler(dataStorage)

    println(">>> 서버 시작 중...")
    val server = WebServer(calculator, 8888)
    server.start()

    try {
        // 로컬 UI 편의 기능이며, 실패해도 서버 자체는 정상 동작해야 한다.
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI("http://localhost:8888"))
        }
    } catch (e: Exception) {
        println("브라우저 자동 실행 실패. 크롬을 열고 http://localhost:8888 로 접속하세요.")
    }

    // runBlocking 스코프를 유지해 코루틴/서버가 종료되지 않도록 한다.
    while (true) {
        kotlinx.coroutines.delay(1000)
    }
}
