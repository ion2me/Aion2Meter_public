// Path: src/main/kotlin/com/tbread/config/AppConfig.kt
package com.tbread.config

import java.io.File
import java.util.Properties
import java.util.UUID

object AppConfig {
    private const val CONFIG_FILE = "config.properties"

    // 1. 고유 식별자 (Recorder ID)
    // 한 번 생성되면 파일에 저장되어 바뀌지 않음
    val recorderId: String by lazy {
        loadOrCreateRecorderId()
    }

    val isAdmin: Boolean by lazy {
        File("admin.mode").exists()
    }

    private fun loadOrCreateRecorderId(): String {
        val file = File(CONFIG_FILE)
        val props = Properties()

        // 기존 파일이 있으면 읽어옴
        if (file.exists()) {
            file.reader().use { props.load(it) }
            val existingId = props.getProperty("recorder_id")
            if (!existingId.isNullOrBlank()) {
                println("🔑 기존 ID를 불러왔습니다: $existingId")
                return existingId
            }
        }

        // ID가 없으면 새로 생성 후 저장
        val newId = UUID.randomUUID().toString().take(8) // 너무 길면 보기 싫으니 앞 8자리만 사용
        props.setProperty("recorder_id", newId)

        // 파일에 저장
        file.writer().use { props.store(it, "Aion Meter Configuration") }

        println("✨ 새로운 고유 ID가 생성되었습니다: $newId")
        return newId
    }
}