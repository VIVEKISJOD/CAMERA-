plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Passed in by the GitHub workflow so every build has an increasing number the updater can compare.
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
val githubRepo = (project.findProperty("githubRepo") as String?) ?: ""

android {
    namespace = "app.fovea.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.fovea.camera"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.3.$buildNumber"
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }
    // PERSONAL-USE signing key, committed on purpose so updates install over older builds.
    // Replaced by a secret-based key before any public release (Stage 6).
    signingConfigs {
        create("personal") {
            storeFile = file("fovea-personal.jks")
            storePassword = "fovea-personal"
            keyAlias = "fovea"
            keyPassword = "fovea-personal"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("personal") }
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    testImplementation("junit:junit:4.13.2")
}
