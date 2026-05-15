plugins {
    id("com.android.library")
}

android {
    namespace = "com.jm.sillydroid.core.model"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:common"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
