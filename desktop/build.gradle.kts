import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "dev.ontask"
version = "0.1.0"


dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// The Swift helper is built before `run` so the app can launch it.
val helperDir = layout.projectDirectory.dir("../helper")
val buildHelper by tasks.registering(Exec::class) {
    workingDir = helperDir.asFile
    commandLine("swift", "build", "-c", "release")
    inputs.dir(helperDir.dir("Sources"))
    inputs.file(helperDir.file("Package.swift"))
    outputs.file(helperDir.file(".build/release/ontask-helper"))
}
tasks.matching { it.name == "run" }.configureEach { dependsOn(buildHelper) }

compose.desktop {
    application {
        mainClass = "dev.ontask.MainKt"
        // Resource budget: keep the heap small.
        jvmArgs += listOf(
            "-Xmx192m", "--enable-native-access=ALL-UNNAMED", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:MaxMetaspaceSize=128m",
            "-Dontask.helper=${helperDir.file(".build/release/ontask-helper").asFile.path}",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "OnTask"
            packageVersion = "1.0.0"
        }
    }
}
