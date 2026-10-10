import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "fully.local.rag"
version = "1.0.0"

repositories { mavenCentral() }

val ktorVersion = "3.6.0"

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson-jvm:$ktorVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

sourceSets {
    main {
        // Reuse the document loaders, chunkers, local embeddings and SQLite vector index.
        kotlin.srcDir("../../Week_5/mon/src/main/kotlin")
    }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("localrag.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = project.projectDir
}

tasks.test {
    useJUnitPlatform()
}
