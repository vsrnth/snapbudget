plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "dev.snapbudget"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.snapbudget"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    testOptions { animationsDisabled = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
tasks.configureEach {
    if (name.startsWith("connected") && name.endsWith("AndroidTest")) {
        doFirst {
            val environment = System.getenv()
            val ci = if (!environment["JENKINS_URL"].isNullOrBlank()) true else
                listOf(
                    "CI", "GITHUB_ACTIONS", "BUILDKITE", "GITLAB_CI", "CIRCLECI", "TRAVIS",
                    "TF_BUILD", "TEAMCITY_VERSION", "APPVEYOR", "DRONE", "CODEBUILD_BUILD_ID",
                ).any { name ->
                    val value = environment[name]?.trim()
                    !value.isNullOrEmpty() && value.lowercase() !in setOf("0", "false", "no", "off")
                }
            check(!ci) { "Instrumented tests are local-device-only and forbidden on CI." }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    // Bundled model, NOT the Play Services downloadable model.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
