import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

group = "dev.futonweb"
version = "0.1.0"

application {
    mainClass.set("dev.futonweb.MainKt")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi",
        )
    }
}

val ktorVersion = "3.2.3"

dependencies {
    // kotatsu-redo parsers, pinned by commit; bump with scripts/update-parsers.sh
    implementation("com.github.kotatsu-redo:kotatsu-parsers-redo:204c9ee032")

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.openjdk.nashorn:nashorn-core:15.6")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}
