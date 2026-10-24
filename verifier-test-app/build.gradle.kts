import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.rogers.updaterprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.rogers.updaterprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/updater-test-assets"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
}

dependencies {
    implementation(project(":updater"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Match synthetic verifier fixtures to this build's disposable default debug key.
// In particular, a checked-in APK would not match another developer's debug signer.
val generateUpdaterTestApks =
    tasks.register<Exec>("generateUpdaterTestApks") {
        group = "verification"
        description = "Generates signed, manifest-only updater APKs for Android instrumentation tests."
        dependsOn("validateSigningDebug")
        val signing = android.signingConfigs.getByName("debug")
        val debugKey = requireNotNull(signing.storeFile) { "Default debug keystore is unavailable" }
        doFirst {
            check(signing.storePassword == "android" && signing.keyPassword == "android") {
                "Updater fixtures require the standard Android debug signing configuration"
            }
        }
        val script = rootProject.file("scripts/generate_updater_test_apks.py")
        val output = layout.buildDirectory.dir("generated/updater-test-assets/updater")
        inputs.file(script)
        inputs.file(debugKey)
        inputs.property("applicationId", android.defaultConfig.applicationId)
        inputs.property("versionCode", android.defaultConfig.versionCode)
        inputs.property("minSdk", android.defaultConfig.minSdk)
        inputs.property("compileSdk", android.compileSdk)
        outputs.dir(output)
        val javaHome = System.getProperty("java.home")
        val defaultPython = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val python = providers.gradleProperty("pythonExecutable").orElse(defaultPython).get()
        inputs.property("javaHome", javaHome)
        environment("FIXTURE_STORE_PASSWORD", "android")
        commandLine(
            python,
            script.absolutePath,
            "--java-home",
            javaHome,
            "--project",
            rootProject.projectDir.absolutePath,
            "--keystore",
            debugKey.absolutePath,
            "--key-alias",
            requireNotNull(signing.keyAlias),
            "--application-id",
            requireNotNull(android.defaultConfig.applicationId),
            "--installed-code",
            requireNotNull(android.defaultConfig.versionCode).toString(),
            "--min-sdk",
            requireNotNull(android.defaultConfig.minSdk).toString(),
            "--compile-sdk",
            requireNotNull(android.compileSdk).toString(),
            "--output",
            output.get().asFile.absolutePath,
        )
    }
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(generateUpdaterTestApks)
}

