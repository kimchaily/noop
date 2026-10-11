import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
}

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
            }
        }

        val desktopMain by getting {
            kotlin.srcDirs("src/main/kotlin")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                implementation("org.xerial:sqlite-jdbc:3.46.1.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("org.json:json:20240303")
                // kxml2 is an Android-only XML parser; not needed on desktop JVM.
                //implementation("net.sf.kxml2:kxml2:2.3.0")
                implementation(compose.desktop.currentOs)
            }
        }

        val desktopTest by getting {
            kotlin.srcDirs("src/test/kotlin")
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.noop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "NOOP"
            packageVersion = "8.2.2"
            description = "Offline WHOOP strap companion"
            vendor = "NoopApp"
            windows {
                menuGroup = "NOOP"
                upgradeUuid = "7b8e9f2a-3c4d-4e5f-8a9b-0c1d2e3f4a5b"
            }
        }
    }
}
