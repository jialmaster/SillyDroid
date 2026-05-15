plugins {
    id("com.android.library")
}

android {
    namespace = "com.jm.sillydroid.data.runtime"
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
    api(project(":domain"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // 强制走本地缓存的 1.3.1：避免离线/受限网络下从 dl.google.com 拉取 1.3.0
    // （androidx.lifecycle:lifecycle-runtime:2.6.2 默认带入 1.3.0，但 1.3.1 在缓存里且兼容）。
    testImplementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
