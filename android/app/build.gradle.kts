import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Optional release signing. Credentials live in `keystore.properties` (git-ignored, never
// committed); when it's absent — clones, CI without secrets — release falls back to the debug
// key so `assembleRelease` always produces an installable APK. See docs/BUILD.md.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.noop"
    compileSdk = 34

    defaultConfig {
        // Personal fork identity (kimchaily/noop). The original NoopApp build shipped as
        // com.noop.whoop; this fork ships under its own applicationId so it is a distinct install
        // and can be signed + updated with the fork owner's own key. The code `namespace` below
        // stays com.noop (all sources are `package com.noop.*`) — only the on-device app identity
        // changes. App display name is "Choop" (see res/values/strings.xml).
        applicationId = "com.kimchai.choop"
        minSdk = 26
        targetSdk = 34
        // Fork versioning continues the upstream line (NOOP ended at 8.2.2 / build 261) so the
        // in-app updater's numeric compare keeps working and the lineage stays legible. Keep
        // versionName numeric-only — UpdateCheck.isNewer parses digits.
        //   8.2.3 / 262 — first Choop release (rebrand + own key).
        //   8.2.4 / 263 — in-app "NOOP" wordmarks/copy → "Choop"; versioned APK filename.
        versionCode = 270
        versionName = "8.2.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Build provenance for the Today-header stamp (see ui/BuildStamp.kt). CI injects the short
        // sha + branch via -PchoopGitSha/-PchoopGitBranch; local builds leave them blank and the UI
        // omits the segment. Kept in defaultConfig so every flavor carries the fields.
        val gitSha = (project.findProperty("choopGitSha") as String?) ?: ""
        val gitBranch = (project.findProperty("choopGitBranch") as String?) ?: ""
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
    }

    // CI-only version override, used by the release workflow for PREVIEW-CHANNEL builds: it passes
    // -PchoopVersionCodeOverride=1000+<run#> so every newer CI preview build carries a strictly
    // higher versionCode than any earlier one — REGARDLESS of which branch it was built from. That
    // is what lets you install a preview APK cut from a feature branch over an installed "Choop
    // Preview" without Android's downgrade block, even when the branch's own versionName/-Code lag
    // behind main. Local builds and the stable channel never set these properties and are untouched.
    (project.findProperty("choopVersionCodeOverride") as String?)?.toIntOrNull()?.let {
        defaultConfig.versionCode = it
    }
    (project.findProperty("choopVersionNameOverride") as String?)?.let {
        defaultConfig.versionName = it
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 full-mode was stripping classes the app needs at runtime (Compose/Room/Tink
            // reflective paths), so release builds installed then crashed on launch. This is an
            // offline app where a ~20 MB APK is fine, so we ship UNMINIFIED for reliability.
            // Re-enabling minify later requires device-verified keep rules first.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real release key when keystore.properties is present; otherwise the debug key,
            // so a fresh clone can still build an installable release APK.
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    // Three clearly-distinct apps that install side-by-side:
    //   • full    → "Choop"         (com.kimchai.choop)         — the real app, starts empty; pair a strap / import.
    //   • demo    → "Choop Demo"    (com.kimchai.choop.demo)    — preloaded with 120 days of synthetic data and
    //                                a visible DEMO badge, so anyone can explore every screen with no strap.
    //   • preview → "Choop Preview" (com.kimchai.choop.preview) — the PREVIEW CHANNEL: functionally the
    //                                full app (same TIER), own sandbox, updated from GitHub *pre*-releases
    //                                (see UpdateCheck + BuildConfig.CHANNEL). Lets a stable Choop and a
    //                                features-ahead Choop coexist, Edge-Canary style. Same signing key as
    //                                stable — the separate applicationId is what keeps them apart.
    //                                NOTE: the strap should stay paired to STABLE — history offload is
    //                                consume-on-read, so whichever app syncs a slice owns it. Feed Preview
    //                                with a .noopbak import instead (see MIGRATION.md).
    // Build e.g. ./gradlew assembleFullRelease assembleDemoRelease assemblePreviewRelease.
    flavorDimensions += "tier"
    productFlavors {
        create("full") {
            dimension = "tier"
            buildConfigField("String", "TIER", "\"full\"")
            buildConfigField("boolean", "ENABLE_DEMO", "false")
            buildConfigField("String", "CHANNEL", "\"stable\"")
        }
        create("demo") {
            dimension = "tier"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "TIER", "\"demo\"")
            buildConfigField("boolean", "ENABLE_DEMO", "true")
            buildConfigField("String", "CHANNEL", "\"stable\"")
        }
        create("preview") {
            dimension = "tier"
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            // Functionally the full app — only the channel differs.
            buildConfigField("String", "TIER", "\"full\"")
            buildConfigField("boolean", "ENABLE_DEMO", "false")
            buildConfigField("String", "CHANNEL", "\"preview\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Compose Compiler extension matched to Kotlin 1.9.24 (see the official
        // Compose-to-Kotlin compatibility map). Bumping Kotlin requires bumping this.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- Compose (BOM pins all Compose artifact versions in lockstep) ---
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // --- Home-screen widget (1.1.x: last line compatible with compileSdk 34) ---
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Glance's own POM pins work-runtime 2.7.1 (Oct 2021) — pre-Android-14. Pin a current one
    // explicitly so the widget scheduler runs on a WorkManager that's maintained for targetSdk 34.
    // (2.10+ needs compileSdk 35; 2.9.x is the ceiling for this module.)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // --- Activity / lifecycle / navigation ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2") // collectAsStateWithLifecycle
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Room (local-only persistence; on-device, nothing leaves the phone) ---
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // --- AI Coach (opt-in, bring-your-own-key). HTTP client + Keystore-backed key storage. ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // --- Health Connect (optional native Android import of steps/HR/HRV/sleep/etc.) ---
    // Pinned to alpha07: alpha11+ require compileSdk 35; this module is compileSdk 34.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // --- Unit / instrumentation tests ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests (android.jar ships throwing stubs)
    testImplementation("net.sf.kxml:kxml2:2.3.0") // real XmlPullParser for JVM tests (android.util.Xml is a throwing stub)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // --- Compose tooling (debug-only) ---
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
