import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("com.android.library") version "8.9.3" apply false
    id("com.android.application") version "8.9.3" apply false
    kotlin("android") version "2.2.21" apply false
}

// Desktop builds do not require an Android SDK. Android scripts opt in explicitly.
val androidEnabled = providers.gradleProperty("meshboard.android").orNull == "true"
if (androidEnabled) apply(plugin = "com.android.library")

// Match the JVM architecture (including an Intel JDK under Rosetta), not the CPU.
val desktopOs = System.getProperty("os.name").lowercase().let {
    when {
        it.startsWith("mac") -> "macos"
        it.startsWith("linux") -> "linux"
        it.startsWith("windows") -> "windows"
        else -> error("Unsupported desktop OS: $it")
    }
}
val desktopArch = when (val arch = System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "aarch64"
    "amd64", "x86_64" -> "x86_64"
    else -> error("Unsupported desktop JVM architecture: $arch")
}

kotlin {
    jvm("desktop")
    if (androidEnabled) androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
    }
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("dev.onvoid.webrtc:webrtc-java:0.17.0")
                runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.17.0:$desktopOs-$desktopArch")
                implementation("com.google.zxing:core:3.5.3")
            }
        }
        val desktopTest by getting {
            dependencies { implementation(compose.desktop.uiTestJUnit4) }
        }
    }
}

if (androidEnabled) extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "dev.meshboard.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Keep the supplied PNG in icons/ as the single source for native UI resources.
val prepareBranding by tasks.registering(Sync::class) {
    from(rootProject.file("../../icons/meshboard-icon-1024.png")) {
        into("drawable")
        rename { "meshboard_icon.png" }
    }
    into(layout.buildDirectory.dir("generated/branding"))
}
compose.resources {
    packageOfResClass = "meshboard.resources"
    customDirectory(sourceSetName = "commonMain", directoryProvider = prepareBranding.map { layout.buildDirectory.dir("generated/branding").get() })
}

compose.desktop {
    application {
        mainClass = "meshboard.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Meshboard"
            packageVersion = "0.1.0"
            description = "A session-only collaborative whiteboard"
            vendor = "Meshboard"
            linux {
                packageName = "meshboard" // Debian package identifiers stay lowercase.
                iconFile.set(rootProject.file("../../icons/meshboard-icon-1024.png"))
            }
            macOS {
                // Apple's bundle/build version requires a positive major number.
                packageVersion = "1.0.0"
                bundleID = "dev.meshboard.desktop"
                iconFile.set(rootProject.file("../../icons/meshboard.icns"))
            }
            modules("java.net.http", "jdk.unsupported", "java.desktop", "java.logging")
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("meshboard.fixtures", rootProject.file("../shared-protocol/fixtures").absolutePath)
}

// The harness uses the exact desktop transport without opening a window.
val desktop = kotlin.targets.getByName("desktop")
val desktopMainCompilation = desktop.compilations.getByName("test")
tasks.register<JavaExec>("interopPeer") {
    dependsOn("desktopTestClasses")
    classpath(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    mainClass.set("meshboard.InteropPeerKt")
    standardInput = System.`in`
}
tasks.register("prepareInterop") {
    dependsOn("desktopTestClasses")
    val output = layout.buildDirectory.file("interop/classpath.txt")
    outputs.file(output)
    // Absolute dependency paths are machine-specific; regenerate each time.
    outputs.upToDateWhen { false }
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(files(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles).asPath)
        }
    }
}
