import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("maven-publish")
}

group = "io.github.phillip9933"
version = "0.2.0"

android {
    namespace = "io.github.phillip9933.appupdater"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    testOptions { unitTests.isIncludeAndroidResources = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.work:work-runtime:2.11.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.11.2")
    testImplementation("androidx.room:room-runtime:2.7.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

ktlint { android.set(true) }
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt.yml"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "android-updater"
                pom {
                    name.set("Android App Updater")
                    description.set("GitHub release discovery and verified user-confirmed Android APK updates")
                    licenses {
                        license {
                            name.set("GNU Affero General Public License version 3")
                            url.set("https://www.gnu.org/licenses/agpl-3.0.html")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "Bundle"
                url = uri(rootProject.layout.buildDirectory.dir("maven"))
            }
        }
    }
}
