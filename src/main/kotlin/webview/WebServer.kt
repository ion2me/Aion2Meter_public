package com.tbread.webview

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.tbread.DpsCalculator
import com.tbread.BossMappingManager
import com.tbread.PathManager
import com.tbread.config.AppConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.tbread.entity.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.tbread.util.DiscordSender // 아까 만든 유틸
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlinx.serialization.json.*
import java.io.FilenameFilter

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.tbread.db.LogImporter


class WebServer(private val dpsCalculator: DpsCalculator, private val port: Int = 8888) {

    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)

        // 1. 정적 파일 처리 (HTML, JS, CSS)
        server.createContext("/") { exchange ->
            handleStaticFile(exchange)
        }

        // 2. DPS 데이터 API
        server.createContext("/api/dps") { exchange ->
            if (exchange.requestMethod == "GET") {
                val data = dpsCalculator.getDps()
                val response = Json.encodeToString(data)
                sendResponse(exchange, response, "application/json")
            }
        }

        // 3. 상세 정보 API
        server.createContext("/api/detail") { exchange ->
            if (exchange.requestMethod == "GET") {
                val query = exchange.requestURI.query
                val params = parseQuery(query)
                val uid = params["uid"]?.toIntOrNull() ?: 0

                val detailData = dpsCalculator.getDps().map[uid]?.analyzedData
                val response = if (detailData != null) {
                    Json.encodeToString(detailData)
                } else {
                    "{}"
                }
                sendResponse(exchange, response, "application/json")
            }
        }

        // 4. 초기화 API
        server.createContext("/api/reset") { exchange ->
            dpsCalculator.resetDataStorage()
            sendResponse(exchange, "{\"status\":\"ok\"}", "application/json")
        }


        // 6. 보스 이름 매핑 API
        server.createContext("/api/boss/map") { exchange ->
            if (exchange.requestMethod == "POST") {
                try {
                    val requestBody = String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8)
                    val req = Json.decodeFromString<BossMapRequest>(requestBody)

                    // ✅ entityId로 엔티티 조회 후 Npc인지 확인
                    val entity = dpsCalculator.dataStorage.getEntity(req.entityId)
                    val npc = entity as? Npc

                    // npcCode / mapId는 Npc에서 직접 가져오기 (없으면 fallback)
                    val realNpcCode = npc?.npcCode ?: dpsCalculator.dataStorage.getNpcCode(req.entityId)
                    val realMapId = npc?.mapId ?: dpsCalculator.dataStorage.getCurrentMapId()

                    if (realNpcCode != null && realNpcCode != 0) {
                        // ✅ BossMappingManager.save(code, mapId, name)
                        BossMappingManager.save(realNpcCode, realMapId, req.name)

                        sendResponse(
                            exchange,
                            "{\"status\":\"mapped\", \"name\":\"${req.name}\"}",
                            "application/json"
                        )
                    } else {
                        println("매핑 실패: EntityId ${req.entityId}에 해당하는 NPC Code가 없습니다.")
                        sendResponse(
                            exchange,
                            "{\"status\":\"error\", \"message\":\"Invalid Target\"}",
                            "application/json"
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    sendResponse(exchange, "{\"status\":\"error\"}", "application/json")
                }
            }
        }


        // (이하 나머지 메서드들은 그대로 유지)
        // 7. 관리자 import
        server.createContext("/api/admin/import") { exchange ->
            if (!AppConfig.isAdmin) {
                sendResponse(exchange, "{\"status\":\"error\", \"message\":\"Not Admin\"}", "application/json")
                return@createContext
            }
            if (exchange.requestMethod == "GET" || exchange.requestMethod == "POST") {
                val resultMsg = LogImporter.importFromFolder("logs")
                sendResponse(exchange, "{\"status\":\"done\", \"message\":\"$resultMsg\"}", "application/json")
            }
        }

        // 8. 캐릭터 목록
        server.createContext("/api/analysis/characters") { exchange ->
            if (exchange.requestMethod == "GET") {
                val chars = com.tbread.analysis.LogAnalyzer.getAvailableCharacters()
                val response = Json.encodeToString(chars)
                sendResponse(exchange, response, "application/json")
            }
        }

        // 9. 개인 리포트
        server.createContext("/api/analysis/report") { exchange ->
            if (exchange.requestMethod == "GET") {
                val params = parseQuery(exchange.requestURI.query)
                val nickname = params["nickname"]
                if (nickname != null) {
                    val result = com.tbread.analysis.LogAnalyzer.analyze(nickname)
                    val response = Json.encodeToString(result)
                    sendResponse(exchange, response, "application/json")
                } else {
                    sendResponse(exchange, "{}", "application/json")
                }
            }
        }

        // [통계 1] 필터
        server.createContext("/api/stats/filters") { exchange ->
            if (exchange.requestMethod == "GET") {
                try {
                    val data = com.tbread.db.StatisticsRepository.getFilterOptions()
                    val response = Json.encodeToString(data)
                    sendResponse(exchange, response, "application/json")
                } catch (e: Exception) {
                    e.printStackTrace()
                    sendResponse(exchange, "{\"error\":\"${e.message}\"}", "application/json")
                }
            }
        }

        // [통계 2] 랭킹
        server.createContext("/api/stats/ranking") { exchange ->
            if (exchange.requestMethod == "GET") {
                val params = parseQuery(exchange.requestURI.query)
                val month = params["month"] ?: ""
                val boss = params["boss"] ?: ""

                val data = com.tbread.db.StatisticsRepository.getJobRanking(month, boss)
                val response = Json.encodeToString(data)
                sendResponse(exchange, response, "application/json")
            }
        }

        // [통계 3] 스킬
        server.createContext("/api/stats/skills") { exchange ->
            if (exchange.requestMethod == "GET") {
                val params = parseQuery(exchange.requestURI.query)
                val month = params["month"] ?: ""
                val boss = params["boss"] ?: ""
                val job = params["job"] ?: ""

                val data = com.tbread.db.StatisticsRepository.getJobSkillAnalysis(month, boss, job)
                val response = Json.encodeToString(data)
                sendResponse(exchange, response, "application/json")
            }
        }

        server.createContext("/boss_mapping.json") { exchange ->
            val file = PathManager.getBossMappingFile()
            if (file.exists()) {
                val content = file.readText(Charsets.UTF_8)
                val responseBytes = content.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                exchange.responseBody.use { it.write(responseBytes) }
            } else {
                val response = "{}"
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
        }
        // 지난주 로그 디스코드 공유 API (자동 백업용)
        server.createContext("/api/share/discord-last-week") { exchange ->
            if (exchange.requestMethod == "POST") {
                val requestTime = LocalDateTime.now()
                println("[$requestTime] /api/share/discord-last-week 요청 수신 (전체 파일 전송 & 마스킹)")

                try {
                    // 1. 요청자 확인 (로깅용)
                    val body = String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8)
                    val jsonElement = Json { ignoreUnknownKeys = true }.parseToJsonElement(body)
                    val reqObj = jsonElement.jsonObject
                    val requesterName = reqObj["nickname"]?.jsonPrimitive?.content
                        ?: reqObj["name"]?.jsonPrimitive?.content
                        ?: "Unknown"

                    println("ㄴ 요청자: [$requesterName]")

                    // 2. 지난주 월요일 날짜 계산
                    val today = LocalDate.now()
                    val lastWeekDate = today.minusWeeks(1)
                    val targetMonday = lastWeekDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val targetPattern = targetMonday.format(DateTimeFormatter.ISO_DATE) // "yyyy-MM-dd"

                    // 3. 파일 검색 (특정 UID가 아닌, 해당 날짜 패턴을 가진 '모든' 파일)
                    val logDir = PathManager.getLogDir() // 수정됨: .toFile() 제거
                    val filePrefix = "log_${targetPattern}_"

                    // 파일 필터링: "log_2026-01-26_"로 시작하고 ".json"으로 끝나는 파일들
                    val targetFiles = logDir.listFiles { _, name ->
                        name.startsWith(filePrefix) && name.endsWith(".json")
                    }

                    val fileCount = targetFiles?.size ?: 0
                    println("ㄴ 검색된 파일 개수: ${fileCount}개 (패턴: $filePrefix*)")

                    if (targetFiles != null && targetFiles.isNotEmpty()) {
                        var successCount = 0
                        val jsonParser = Json { ignoreUnknownKeys = true }

                        // 4. 검색된 모든 파일을 순회하며 처리
                        targetFiles.forEach { originalFile ->
                            try {
                                println("   >> 처리 중: ${originalFile.name}")

                                // [Step A] 파일 읽기 및 파싱
                                val rawContent = originalFile.readText(StandardCharsets.UTF_8)
                                val rootJson = jsonParser.parseToJsonElement(rawContent)

                                // [Step B] 이름 마스킹 (직업명으로 변경)
                                val maskedJson = maskNames(rootJson)

                                // [Step C] 임시 파일 생성
                                // 원본 파일명 식별을 위해 prefix 포함: masked_log_2026-01-26_UID_xxxx.json
                                val maskedContent = jsonParser.encodeToString(JsonElement.serializer(), maskedJson)
                                val tempFile = File.createTempFile("masked_${originalFile.nameWithoutExtension}_", ".json")
                                tempFile.writeText(maskedContent, StandardCharsets.UTF_8)

                                // [Step D] 전송
                                val success = DiscordSender.sendLogFile(tempFile)

                                // [Step E] 임시 파일 삭제
                                tempFile.delete()

                                if (success) {
                                    successCount++
                                    println("   >> 전송 성공")
                                } else {
                                    println("   >> 전송 실패")
                                }
                            } catch (e: Exception) {
                                println("   >> 파일 처리 중 에러 발생: ${originalFile.name}")
                                e.printStackTrace()
                            }
                        }

                        // 결과 응답
                        if (successCount > 0) {
                            sendResponse(exchange, "{\"status\":\"success\", \"msg\":\"Sent $successCount / $fileCount files\"}", "application/json")
                        } else {
                            sendResponse(exchange, "{\"status\":\"error\", \"msg\":\"Failed to send files\"}", "application/json")
                        }

                    } else {
                        println("ㄴ 해당 주차 로그 파일 없음 (정상)")
                        sendResponse(exchange, "{\"status\":\"no_file\", \"msg\":\"No logs found for last week\"}", "application/json")
                    }

                } catch (e: Exception) {
                    println("!!! 에러 발생 !!!")
                    e.printStackTrace()
                    sendResponse(exchange, "{\"status\":\"error\", \"msg\":\"${e.message}\"}", "application/json")
                }
            }
        }


        server.executor = null
        server.start()
        println("웹 서버가 시작되었습니다: http://localhost:$port")
    }

    private fun maskNames(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                val newContent = element.toMutableMap()

                // 1. 'name'이 있고, 동시에 'job'도 있는지 확인 (플레이어 구별)
                if (newContent.containsKey("name")) {
                    val hasJobInfo = newContent.containsKey("job") || newContent.containsKey("class")

                    if (hasJobInfo) {
                        // 직업 정보를 가져옴
                        val jobValue = newContent["job"]?.jsonPrimitive?.content
                            ?: newContent["class"]?.jsonPrimitive?.content
                            ?: "Unknown"

                        // name을 직업명으로 덮어쓰기 ("김수띠" -> "수호성")
                        newContent["name"] = JsonPrimitive(jobValue)
                    }
                    // job 정보가 없으면(보스 등) name을 그대로 둠
                }

                // 2. 하위 요소 재귀 처리
                newContent.forEach { (key, value) ->
                    newContent[key] = maskNames(value)
                }

                JsonObject(newContent)
            }
            is JsonArray -> {
                JsonArray(element.map { maskNames(it) })
            }
            else -> element
        }
    }

    // (유틸 메서드 그대로 유지)
    private fun handleStaticFile(exchange: HttpExchange) {
        var path = exchange.requestURI.path
        if (path == "/") path = "/index.html"
        if (path == "/analysis") path = "/analysis.html"

        val resourceStream = javaClass.getResourceAsStream(path)
        if (resourceStream == null) {
            val response = "404 Not Found"
            exchange.sendResponseHeaders(404, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.responseBody.close()
            return
        }

        val mimeType = when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".js") -> "application/javascript; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }

        exchange.responseHeaders.set("Content-Type", mimeType)
        exchange.sendResponseHeaders(200, 0)
        resourceStream.copyTo(exchange.responseBody)
        exchange.responseBody.close()
    }

    private fun sendResponse(exchange: HttpExchange, response: String, contentType: String) {
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "$contentType; charset=utf-8")
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        val os: OutputStream = exchange.responseBody
        os.write(bytes)
        os.close()
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split("&").associate {
            val parts = it.split("=")
            val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], StandardCharsets.UTF_8) else ""
            key to value
        }
    }
}