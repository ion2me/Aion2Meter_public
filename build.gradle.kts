import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    application

    // JavaFX 플러그인
    id("org.openjfx.javafxplugin") version "0.1.0"

    // [변경] Fat Jar 생성을 위한 Shadow 플러그인 추가
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.a2m"
version = "2.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    // 메인 클래스 경로 (패키지명.파일명Kt)
    mainClass.set("com.tbread.MainKt")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

javafx {
    version = "17.0.10"
    modules("javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.web")
}

dependencies {
    // 1) Packet Capture
    implementation("org.pcap4j:pcap4j-core:1.8.2")
    implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")

    // 2) Coroutines / Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // 3) Logging (logback 사용)
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // 4) JNA
    implementation("net.java.dev.jna:jna:5.13.0")

    // 5) Ktor (웹 서버)
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-websockets:2.3.7")
    implementation("io.ktor:ktor-server-html-builder-jvm:2.3.7")

    // 6) DB
    implementation("org.jetbrains.exposed:exposed-core:0.41.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.41.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.41.1")
    implementation("org.xerial:sqlite-jdbc:3.41.2.1")
}

// [추가] Shadow Jar 설정 (모든 라이브러리를 하나로 합침)
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("Aion2Meter") // 결과물 파일명: Aion2Meter-1.0.0-all.jar
    archiveClassifier.set("all")

    // 서명 파일 제외 (합칠 때 에러 방지)
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    manifest {
        attributes["Main-Class"] = "com.tbread.MainKt"
    }
}