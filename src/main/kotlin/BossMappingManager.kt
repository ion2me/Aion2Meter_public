package com.tbread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object BossMappingManager {

    // 키는 npcCode와 mapId 조합으로 안정 식별자를 만들기 위해 "code_mapId" 문자열을 사용한다.
    private val mapping: MutableMap<String, String> = ConcurrentHashMap()

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init {
        load()
    }

    private fun load() {
        // 파일 위치/생성 책임은 PathManager로 위임해 OS별 경로 및 최초 생성 로직을 단일화한다.
        val file = PathManager.getBossMappingFile()

        // 파일이 없거나 포맷이 깨져도 앱이 중단되지 않도록 예외를 흡수하고 빈 매핑으로 시작한다.
        if (file.exists()) {
            try {
                val content = file.readText()
                val loaded = json.decodeFromString<Map<String, String>>(content)
                mapping.putAll(loaded)
            } catch (e: Exception) {
                System.err.println("맵핑 파일 로드 실패 (포맷 변경 또는 손상됨): ${e.message}")
                // e.printStackTrace() // 필요 시 원인 추적용으로만 활성화한다.
            }
        }
    }

    // 저장 시에는 특정 맵에 종속된 이름을 우선 기록해, 동일 npcCode라도 맵별로 다른 보스를 분리할 수 있게 한다.
    fun save(npcCode: Int, mapId: Int, name: String) {
        val key = makeKey(npcCode, mapId)
        mapping[key] = name
        saveToFile()
    }

    fun getName(npcCode: Int, mapId: Int): String? {
        // 맵 전용 키를 우선하고, 없으면 공통 키(code_0)로 폴백해 구버전 데이터도 활용한다.
        val specificKey = makeKey(npcCode, mapId)
        if (mapping.containsKey(specificKey)) {
            return mapping[specificKey]
        }

        val commonKey = makeKey(npcCode, 0)
        return mapping[commonKey]
    }

    private fun saveToFile() {
        try {
            // 저장 경로는 PathManager에서 일관되게 제공해, 실행 위치/권한에 따른 편차를 없앤다.
            val file = PathManager.getBossMappingFile()

            // 파일 쓰기는 원자성이 없으므로, 동시 호출에서 내용이 섞이지 않도록 단일 락으로 직렬화한다.
            synchronized(this) {
                file.writeText(json.encodeToString(mapping))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun makeKey(code: Int, mapId: Int): String {
        return "${code}_${mapId}"
    }
}
