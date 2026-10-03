import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "local.rag"
version = "1.0.0"

repositories { mavenCentral() }

val ktorVersion = "3.5.1"

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson-jvm:$ktorVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

sourceSets {
    main {
        kotlin.srcDir("../mon/src/main/kotlin")
        kotlin.srcDir("../tue/src/main/kotlin")
        kotlin.srcDir("../wed/src/main/kotlin")
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application { mainClass.set("groundedrag.MainKt") }
tasks.test { useJUnitPlatform() }

tasks.processResources {
    from("../wed/src/main/resources") {
        include("evaluation/**")
    }
}
