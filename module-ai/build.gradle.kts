plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.banread.ai"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // AiLog uses android.util.Log; JVM tests only need it to be a no-op.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core-model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.tink.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
