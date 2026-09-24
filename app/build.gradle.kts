plugins {
    id("com.android.application")
    // Kotlin support is built into AGP 9 — no org.jetbrains.kotlin.android plugin.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dragonsim.ar"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dragonsim.ar"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // ARCore devices are all ARM64 — skip the other ABIs' Filament natives.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        // Built-in Kotlin derives jvmTarget from these.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // SceneView AR (pulls in sceneview + ARCore + Filament)
    implementation("io.github.sceneview:arsceneview:4.39.0")

    // Compose (BOM manages versions for androidx.compose.*)
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Activity / lifecycle
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
}
