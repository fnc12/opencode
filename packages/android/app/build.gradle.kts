plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.paparazzi)
}

// Code coverage (kotlinx-kover). `./gradlew :app:koverHtmlReportDebug` writes an
// HTML report; `koverXmlReportDebug` an XML one we parse in scripts/coverage.sh.
// We exclude generated code and pure-Android UI shells that can only be
// exercised on a device/emulator (instrumented tests) — the JVM unit coverage
// number then reflects the logic we CAN drive headlessly, so a gap is a real
// missing test, not framework noise.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "*.databinding.*",
                    "*.BuildConfig",
                    "*.R", "*.R$*",
                    // Compose UI screens & FCM service are covered by instrumented /
                    // screenshot tests, not JVM unit tests — keep them out of the
                    // JVM coverage denominator so it measures testable logic.
                    "studio.eugenezakharov.opencode.MainActivity*",
                    "studio.eugenezakharov.opencode.push.ShubatMessagingService*",
                    "*ComposableSingletons*",
                )
            }
        }
    }
}

// FCM push needs the (secret) google-services.json, which is git-ignored. Apply
// the plugin only when it's present so the repo still builds without it — the
// app just runs without push.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "studio.eugenezakharov.opencode"
    compileSdk = 35

    defaultConfig {
        applicationId = "studio.eugenezakharov.shubat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Emit coverage from instrumented (androidTest) runs so Kover can merge
            // the emulator-only paths (loaded screens, images, gestures) with the
            // JVM unit coverage — the last mile to 100%.
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // Syntax highlighting for read results (Kotlin-native, any language).
    implementation("dev.snipme:highlights:1.0.0")
    // QR scanner for relay pairing (#14): battle-tested embedded ZXing capture
    // activity — handles the camera + runtime permission itself.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
