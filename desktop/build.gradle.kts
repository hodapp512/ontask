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


// Classifier, categories, page rules and the heat score, shared with the Android app.
kotlin.sourceSets.named("main") { kotlin.srcDir("../shared/src/main/kotlin") }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Tests render screens off-screen; headless keeps the test JVM ("GradleWorkerMain") from taking the front on macOS
    // (OnTask would otherwise track it as an app you used).
    systemProperty("java.awt.headless", "true")
}
tasks.processResources { filesMatching("version.properties") { expand("version" to project.version) } }

// The Swift helper is built before `run` so the app can launch it.
val helperDir = layout.projectDirectory.dir("../helper")
// macOS ties privacy permissions (Accessibility, Screen Recording) to the signing certificate. "-" is ad hoc: grants
// then belong to one exact build and are lost on every rebuild. Builds use, in order: -PsigningIdentity="…", the local
// certificate made by scripts/make-signing-cert.sh ("OnTask Local Signing"), or ad hoc.
val localSigningName = "OnTask Local Signing"
val signingIdentity = (findProperty("signingIdentity") as String?) ?: run {
    val p = ProcessBuilder("security", "find-identity", "-p", "codesigning").redirectErrorStream(true).start()
    if (p.inputStream.bufferedReader().readText().contains("\"$localSigningName\"")) localSigningName else "-"
}
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
        // SwiftPM only linker-signs it as "ontask-helper"; re-sign with a stable identifier (Package.swift embeds its Info.plist).
        val helperBin = helperDir.file(".build/release/ontask-helper").asFile
        val sign = ProcessBuilder("codesign", "--force", "-s", signingIdentity, "--identifier", "dev.ontask.helper", helperBin.path).redirectErrorStream(true).start()
        val signOut = sign.inputStream.bufferedReader().readText()
        if (sign.waitFor() != 0) throw GradleException("codesign of the helper failed: $signOut")
    }
    inputs.dir(helperDir.dir("Sources"))
    inputs.file(helperDir.file("Package.swift"))
    inputs.file(helperDir.file("Info.plist"))
    inputs.property("signingIdentity", signingIdentity)
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
// jpackage ad-hoc signs OnTask.app, so Accessibility/Screen Recording grants are tied to that one build and have to be
// re-granted after each rebuild. With -PsigningIdentity the app is re-signed with that certificate, which keeps them.
// (For notarized releases use Compose's macOS signing with a Developer ID instead.)
val appBundle = layout.buildDirectory.dir("compose/binaries/main/app/OnTask.app")
tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        if (signingIdentity == "-") return@doLast
        val p = ProcessBuilder(
            "codesign", "--force", "--deep", "--options", "runtime", "--entitlements", file("entitlements.plist").path, "-s", signingIdentity, appBundle.get().asFile.path,
        ).redirectErrorStream(true).start()
        val o = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw GradleException("codesign of OnTask.app failed: $o")
    }
}

compose.desktop {
    application {
        mainClass = "dev.ontask.MainKt"
        // Resource budget: keep the heap small.
        jvmArgs += listOf(
            "-Xmx192m", "--enable-native-access=ALL-UNNAMED", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:MaxMetaspaceSize=128m",
            // Trackpad pinch (Apple's gesture events) for zooming the timeline; see ui/Pinch.kt.
            "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED",
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
                // LSUIElement (below): menu bar only — no Dock icon and no app menu.
                iconFile.set(project.file("icons/OnTask.icns")) // regenerate with icons/MakeIcons.java
                entitlementsFile.set(project.file("entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("entitlements.plist"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>LSUIElement</key>
                        <true/>
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
