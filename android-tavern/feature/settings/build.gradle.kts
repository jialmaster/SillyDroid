plugins {
    id("com.android.library")
}

android {
    namespace = "com.jm.sillydroid.feature.settings"
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
    api(project(":core:ui"))
    implementation(project(":ui:update"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    // 终端页接入后 settings 运行时会同时包含 AndroidX ProfileInstaller；
    // 这里必须保留 concurrent-futures 提供的真实 ListenableFuture 定义，
    // 不能再用 empty listenablefuture 占位包，否则 Android 10 / EMUI 校验
    // ProfileVerifier 返回类型时会直接 VerifyError 并杀掉宿主进程。
    implementation("androidx.concurrent:concurrent-futures:1.1.0")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
