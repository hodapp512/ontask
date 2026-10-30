import java.io.ByteArrayOutputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "dev.ontask"
version = (findProperty("appVersion") as String?) ?: "0.1.0"


dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
tasks.processResources { filesMatching("version.properties") { expand("version" to project.version) } }

// The Swift helper is built before `run` so the app can launch it.
val helperDir = layout.projectDirectory.dir("../helper")
val buildHelper = tasks.register<Exec>("buildHelper") {
    workingDir = helperDir.asFile
    commandLine("swift", "build", "-c", "release")
    // On some machines SwiftPM exits 1 with "accessing build database ... disk I/O error" *after* a successful
    // build (seen under ~/workspace). Judge success by the "Build complete!" line instead of the exit code.
    isIgnoreExitValue = true
    val out = ByteArrayOutputStream()
    standardOutput = out
    doLast {
        print(out.toString())
        if (!out.toString().contains("Build complete!")) throw GradleException("swift build failed")
    }
    inputs.dir(helperDir.dir("Sources"))
    inputs.file(helperDir.file("Package.swift"))
    outputs.file(helperDir.file(".build/release/ontask-helper"))
}
tasks.matching { it.name == "run" }.configureEach { dependsOn(buildHelper) }
tasks.withType<JavaExec>().configureEach {
    systemProperty("ontask.helper", helperDir.file(".build/release/ontask-helper").asFile.path)
}

// Files shipped inside the .app: the Swift helper and the root-daemon scripts.
val stageResources = tasks.register<Copy>("stageResources") {
    dependsOn(buildHelper)
    into(layout.buildDirectory.dir("appres/common"))
    from(helperDir.file(".build/release/ontask-helper")) { into("helper") }
    from(layout.projectDirectory.dir("../daemon")) { into("daemon") }
}
tasks.matching { it.name.startsWith("prepareAppResources") || it.name == "createDistributable" || it.name == "packageDmg" }.configureEach { dependsOn(stageResources) }

compose.desktop {
    application {
        mainClass = "dev.ontask.MainKt"
        // Resource budget: keep the heap small.
        jvmArgs += listOf(
            "-Xmx192m", "--enable-native-access=ALL-UNNAMED", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:MaxMetaspaceSize=128m",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "OnTask"
            // jpackage requires MAJOR.MINOR.PATCH with MAJOR >= 1 on macOS.
            packageVersion = ((findProperty("appVersion") as String?) ?: "1.0.0").let { v -> if (v.substringBefore(".").toIntOrNull() == 0) "1.${v.substringAfter(".")}" else v }.substringBefore("-")
            description = "Focus tracking, blocking and accountability"
            modules("java.sql", "java.net.http", "java.management", "jdk.management", "jdk.unsupported")
            // Bundles daemon/ and helper/ (see stageResources) next to the app.
            appResourcesRootDir.set(layout.buildDirectory.dir("appres"))
            macOS {
                bundleID = "dev.ontask.app"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSAppleEventsUsageDescription</key>
                        <string>OnTask reads the current page URL and title of your browser to classify what you're doing.</string>
                        <key>NSScreenCaptureUsageDescription</key>
                        <string>OnTask can read on-screen text to classify your activity. Screenshots are never stored.</string>
                    """.trimIndent()
                }
            }
        }
    }
}
