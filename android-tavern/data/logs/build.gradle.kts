plugins {
    id("com.android.library")
}

fun quoteBuildConfigString(value: String): String {
    return buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(character)
            }
        }
        append('"')
    }
}

fun resolveAndroidHostVersion(): String {
    val envValue = System.getenv("SILLYDROID_ANDROID_HOST_VERSION").orEmpty().trim()
    if (envValue.isNotBlank()) {
        return envValue
    }

    return providers.gradleProperty("staiAndroidHostVersion").orNull?.trim().orEmpty().ifBlank { "0.1.0" }
}

android {
    namespace = "com.jm.sillydroid.data.logs"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        buildConfigField("String", "SILLYDROID_HOST_VERSION", quoteBuildConfigString(resolveAndroidHostVersion()))
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":domain"))
    implementation(project(":data:runtime"))
    implementation(project(":data:settings"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
