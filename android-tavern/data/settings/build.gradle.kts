plugins {
    id("com.android.library")
}

android {
    namespace = "com.jm.sillydroid.data.settings"
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
    implementation("org.yaml:snakeyaml:2.2")
}
