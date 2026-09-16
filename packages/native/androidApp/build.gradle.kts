plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.meshboard.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.meshboard.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }

// Boundary-only checkpoint: neither the released app nor the live Android
// controller uses CRDT yet. No Rust/NDK requirement without this explicit opt-in.
if (providers.gradleProperty("meshboard.crdtAndroid").orNull == "true") {
    android.ndkVersion = "28.2.13676358"
    val targets = mapOf("x86_64" to "x86_64-linux-android", "arm64-v8a" to "aarch64-linux-android")
    val host = when {
        System.getProperty("os.name").startsWith("Linux") -> "linux-x86_64"
        System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
        else -> error("The Android CRDT build checkpoint currently supports Linux and macOS hosts")
    }
    val toolchain = android.sdkDirectory.resolve("ndk/${android.ndkVersion}/toolchains/llvm/prebuilt/$host/bin")
    val rustBuild = layout.buildDirectory.dir("crdt-core")
    val builds = targets.map { (abi, target) ->
        tasks.register<Exec>("buildCrdtAndroid${abi.replace('-', '_')}") {
            workingDir(rootProject.file("../crdt-core"))
            commandLine("cargo", "build", "--locked", "--lib", "--features", "jvm", "--target", target)
            environment("CARGO_TARGET_DIR", rustBuild.get().asFile.absolutePath)
            environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER", toolchain.resolve("${target}26-clang").absolutePath)
            // Explicit 16 KiB alignment for Rust-linked Android libraries too.
            environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
            outputs.upToDateWhen { false } // Cargo owns incremental/toolchain checks.
            doFirst {
                require(toolchain.resolve("${target}26-clang").isFile) {
                    "Install ndk;${android.ndkVersion} and Rust Android targets; see androidApp/README.md"
                }
            }
        }
    }
    val prepareCrdtAndroid by tasks.registering(Sync::class) {
        dependsOn(builds)
        targets.forEach { (abi, target) ->
            from(rustBuild.map { it.file("$target/debug/libmeshboard_crdt_core.so") }) { into(abi) }
        }
        into(layout.buildDirectory.dir("generated/crdtJniLibs"))
    }
    android.sourceSets.getByName("debug") {
        java.srcDir(rootProject.file("src/crdtJvmMain/kotlin"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/crdtJniLibs"))
    }
    android.sourceSets.getByName("androidTest") {
        java.srcDir(rootProject.file("src/crdtJvmTest/kotlin"))
        java.srcDir("src/crdtAndroidTest/kotlin")
    }
    tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(prepareCrdtAndroid) }
    android.testOptions {
        resultsDir = layout.buildDirectory.dir("outputs/androidTest-results/crdt").get().asFile.absolutePath
        reportDir = layout.buildDirectory.dir("reports/androidTests/crdt").get().asFile.absolutePath
    }
    dependencies { androidTestImplementation(kotlin("test-junit")) }
}

dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui:1.9.3")
    implementation("androidx.compose.foundation:foundation:1.9.3")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.3")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.3")
}
