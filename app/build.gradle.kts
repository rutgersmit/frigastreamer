import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- Versiebeheer -------------------------------------------------------------------------
// versionCode = GITHUB_RUN_NUMBER in CI (lokaal 1), zodat elke CI-build hoger is dan de vorige.
// versionName = APP_VERSION_NAME (door de workflow gezet: tag zonder 'v', of 0.0.0-dev.<run>).
val ciRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val appVersionName: String = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: "0.0.0-dev.local"

// ---- Signing ------------------------------------------------------------------------------
// De release-build wordt ALLEEN ondertekend met de vaste keystore uit de environment.
// Ontbreekt die, dan faalt de release-build expliciet (geen stille terugval op debug-signing).
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val hasReleaseSigning: Boolean = keystorePath != null &&
    file(keystorePath).exists() &&
    !System.getenv("SIGNING_STORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("SIGNING_KEY_ALIAS").isNullOrBlank() &&
    !System.getenv("SIGNING_KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "nl.frigastreamer"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.frigastreamer"
        minSdk = 26
        targetSdk = 37
        versionCode = ciRunNumber
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 is bewust uit: Media3/RTSP gebruikt reflectie-loze maar uitgebreide code en
            // voor een LAN-app op één tablet weegt de kleinere APK niet op tegen het risico.
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Faal hard en duidelijk als iemand een release bouwt zonder signing-secrets.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release-signing ontbreekt. Zet SIGNING_KEYSTORE_PATH, SIGNING_STORE_PASSWORD, " +
                    "SIGNING_KEY_ALIAS en SIGNING_KEY_PASSWORD (in CI via de GitHub Secrets " +
                    "SIGNING_KEYSTORE_BASE64, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD). " +
                    "Er wordt bewust niet teruggevallen op debug-signing."
            )
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
