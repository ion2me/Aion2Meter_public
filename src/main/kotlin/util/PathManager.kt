package com.tbread

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object PathManager {
    // 배포본에서 눈에 띄는 앱명 노출을 줄이기 위한 데이터 폴더 식별자.
    private const val APP_NAME = "on2me"

    // 기존 설치본 데이터 보존을 위해, 최초 1회 구형 폴더를 신규 폴더로 마이그레이션한다.
    private const val OLD_APP_NAME = "Aion2Meter"

    // OS별 표준 사용자 데이터 경로를 사용해 실행 위치/권한에 따른 경로 변동을 피한다.
    private fun getBaseDir(folderName: String): File {
        val userHome = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()

        return if (os.contains("mac")) {
            File(userHome, "Library/Application Support/$folderName")
        } else if (os.contains("win")) {
            File(System.getenv("APPDATA"), folderName)
        } else {
            File(userHome, ".$folderName")
        }
    }

    // 실제 사용 경로는 지연 초기화 시점에만 마이그레이션을 시도해 불필요한 I/O를 줄인다.
    private val appDataDir: File by lazy {
        val newDir = getBaseDir(APP_NAME)
        val oldDir = getBaseDir(OLD_APP_NAME)

        // 구형만 존재하는 경우에만 이동을 시도하고, 실패하면 데이터 손실을 피하기 위해 구형은 그대로 둔다.
        if (!newDir.exists() && oldDir.exists()) {
            try {
                if (oldDir.renameTo(newDir)) {
                    println("[PathManager] 기존 데이터 폴더('$OLD_APP_NAME')를 '$APP_NAME'(으)로 이동했습니다.")
                } else {
                    // 권한/잠금 등으로 실패할 수 있으므로, 최소 동작을 보장하기 위해 신규 폴더만 생성한다.
                    newDir.mkdirs()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                newDir.mkdirs()
            }
        }
        // 둘 다 존재하는 경우는 사용자가 데이터를 분리해 둔 상태일 수 있어 기본값은 건드리지 않는다.
        /*
        else if (newDir.exists() && oldDir.exists()) {
            oldDir.deleteRecursively() // 구형 폴더 강제 삭제
        }
        */

        if (!newDir.exists()) {
            newDir.mkdirs()
        }
        newDir
    }

    fun getLogDir(): File {
        // 로그는 사용자 데이터 하위로 고정해, 앱 실행 위치에 따라 파일이 흩어지지 않게 한다.
        val logDir = File(appDataDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return logDir
    }

    fun getBossMappingFile(): File {
        val file = File(appDataDir, "boss_mapping.json")

        // 사용자가 수정한 매핑을 유지해야 하므로, 이미 존재하면 절대 덮어쓰지 않는다.
        if (file.exists()) {
            return file
        }

        // 최초 실행 시 번들 리소스를 복사해 런타임 수정본의 기준 파일로 만든다.
        try {
            val inputStream = javaClass.getResourceAsStream("/boss_mapping.json")

            if (inputStream != null) {
                Files.copy(inputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                // 리소스 누락에도 앱이 중단되지 않도록 최소한의 유효 JSON을 생성한다.
                file.createNewFile()
                file.writeText("{}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return file
    }
}
