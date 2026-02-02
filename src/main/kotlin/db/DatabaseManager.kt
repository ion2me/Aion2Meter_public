// Path: src/main/kotlin/com/tbread/db/DatabaseManager.kt
package com.tbread.db

import com.tbread.config.AppConfig
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseManager {
    private const val DB_FILE = "stat.db"

    fun init() {
        if (!AppConfig.isAdmin) return

        Database.connect("jdbc:sqlite:$DB_FILE", driver = "org.sqlite.JDBC")

        transaction {
            // 마이그레이션 도구 없이 스키마를 진화시키는 개발 단계 전용 초기화.
            // 운영/배포 환경에서는 자동 컬럼 추가가 의도치 않은 스키마 변경을 만들 수 있으므로 별도 마이그레이션을 권장.
            SchemaUtils.createMissingTablesAndColumns(Battles, Players, Skills)
        }
        println("💾 데이터베이스($DB_FILE)가 준비되었습니다.")
    }
}

// --- 테이블 정의 ---

object Battles : LongIdTable("battles") {
    val timestamp = long("timestamp")
    val duration = long("duration_ms")
    val targetName = varchar("target_name", 100)
    val targetId = integer("target_id")
    val targetCode = integer("target_code").nullable()
    val totalDamage = long("total_damage").default(0L)

    // 기존 DB/로그에는 값이 없을 수 있어 nullable로 유지한다.
    val targetMapId = integer("target_map_id").nullable()

    val recorderId = varchar("recorder_id", 50).nullable()

    // 동일 파일명이 여러 번 저장될 수 있으므로 유니크 제약 대신 조회 성능을 위한 인덱스만 둔다.
    val fileName = varchar("file_name", 255).index()
}

object Players : LongIdTable("players") {
    val battleId = reference("battle_id", Battles)
    val name = varchar("name", 50)
    val job = varchar("job", 50)
    val totalDamage = double("total_damage")
    val dps = double("dps")
}

object Skills : LongIdTable("skills") {
    val playerId = reference("player_id", Players)
    val skillId = integer("skill_id")
    val skillName = varchar("skill_name", 100)
    val damage = long("damage_amount")
    val useCount = integer("use_count")
    val critCount = integer("crit_count")
    val maxDamage = long("max_damage").default(0)
}
