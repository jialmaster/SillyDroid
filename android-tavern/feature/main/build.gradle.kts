plugins {
    id("com.android.library")
}

android {
    namespace = "com.jm.sillydroid.feature.main"
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
    // 复用 :feature:settings 里 BootstrapSettingsExtensionsCoordinator 实现的“默认扩展安装”完整流程
    // （GitHub 可达性预检 + 按仓库批量预检 + 用户勾选确认 + 百分比进度 + 结果汇总），
    // MainActivity 首次启动后用一个独立小窗触发同一套流程，不再跳转到设置页。
    implementation(project(":feature:settings"))
    implementation(project(":ui:update"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
