import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
}

fun requireReleaseSigningValue(name: String, value: String): String {
    if (value.isBlank()) {
        throw GradleException("缺少 Android release 签名配置：$name")
    }

    return value
}

fun loadReleaseSigningProperties(): Properties {
    val properties = Properties()
    val propertiesFile = file("signing/release-signing.properties")

    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(properties::load)
    }

    return properties
}

fun resolveReleaseSigningValue(
    envName: String,
    propertyName: String,
    properties: Properties
): String {
    return System.getenv(envName).orEmpty().trim().ifBlank {
        properties.getProperty(propertyName).orEmpty().trim()
    }
}

val requestedTasks = gradle.startParameter.taskNames.map { it.lowercase() }
val requiresReleaseSigning = requestedTasks.any { taskName ->
    taskName.contains("release") || taskName.contains("bundle")
}
val releaseSigningProperties = loadReleaseSigningProperties()
val releaseSigningKeystorePath = resolveReleaseSigningValue(
    envName = "STAI_ANDROID_RELEASE_KEYSTORE_PATH",
    propertyName = "storeFile",
    properties = releaseSigningProperties
)
val releaseSigningKeystorePassword = resolveReleaseSigningValue(
    envName = "STAI_ANDROID_RELEASE_KEYSTORE_PASSWORD",
    propertyName = "storePassword",
    properties = releaseSigningProperties
)
val releaseSigningKeyAlias = resolveReleaseSigningValue(
    envName = "STAI_ANDROID_RELEASE_KEY_ALIAS",
    propertyName = "keyAlias",
    properties = releaseSigningProperties
)
val releaseSigningKeyPassword = resolveReleaseSigningValue(
    envName = "STAI_ANDROID_RELEASE_KEY_PASSWORD",
    propertyName = "keyPassword",
    properties = releaseSigningProperties
)

fun resolveAndroidVersionCode(rawValue: String): Int {
    if (rawValue.isBlank()) {
        return 1
    }

    return rawValue.toIntOrNull()?.takeIf { it > 0 }
        ?: throw GradleException("STAI_ANDROID_VERSION_CODE 必须是正整数：$rawValue")
}

fun resolveAndroidHostVersion(): String {
    val envValue = System.getenv("STAI_ANDROID_HOST_VERSION").orEmpty().trim()
    if (envValue.isNotBlank()) {
        return envValue
    }

    return providers.gradleProperty("staiAndroidHostVersion").orNull?.trim().orEmpty().ifBlank { "0.1.0" }
}

fun resolveAndroidVersionName(
    rawValue: String,
    hostVersion: String,
    upstreamVersion: String
): String {
    if (rawValue.isNotBlank()) {
        return rawValue
    }

    return if (upstreamVersion.isBlank()) {
        hostVersion
    } else {
        "$hostVersion+tavern.$upstreamVersion"
    }
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

val androidVersionCode = resolveAndroidVersionCode(System.getenv("STAI_ANDROID_VERSION_CODE").orEmpty().trim())
val androidHostVersion = resolveAndroidHostVersion()
val androidUpstreamVersion = System.getenv("STAI_ANDROID_UPSTREAM_VERSION").orEmpty().trim()
val androidVersionName = resolveAndroidVersionName(
    rawValue = System.getenv("STAI_ANDROID_VERSION_NAME").orEmpty().trim(),
    hostVersion = androidHostVersion,
    upstreamVersion = androidUpstreamVersion
)

android {
    namespace = "com.stai.sillytavern"
    compileSdk = 36

    signingConfigs {
        create("release") {
            if (requiresReleaseSigning) {
                val signingKeystoreFile = file(
                    requireReleaseSigningValue(
                        name = "STAI_ANDROID_RELEASE_KEYSTORE_PATH / signing/release-signing.properties:storeFile",
                        value = releaseSigningKeystorePath
                    )
                )

                if (!signingKeystoreFile.isFile) {
                    throw GradleException("Android release keystore 不存在：${signingKeystoreFile.absolutePath}")
                }

                storeFile = signingKeystoreFile
                storePassword = requireReleaseSigningValue(
                    name = "STAI_ANDROID_RELEASE_KEYSTORE_PASSWORD / signing/release-signing.properties:storePassword",
                    value = releaseSigningKeystorePassword
                )
                keyAlias = requireReleaseSigningValue(
                    name = "STAI_ANDROID_RELEASE_KEY_ALIAS / signing/release-signing.properties:keyAlias",
                    value = releaseSigningKeyAlias
                )
                keyPassword = requireReleaseSigningValue(
                    name = "STAI_ANDROID_RELEASE_KEY_PASSWORD / signing/release-signing.properties:keyPassword",
                    value = releaseSigningKeyPassword
                )
            }
        }
    }

    defaultConfig {
        applicationId = "com.stai.sillytavern"
        minSdk = 29
        targetSdk = 36
        versionCode = androidVersionCode
        versionName = androidVersionName
        buildConfigField("String", "STAI_HOST_VERSION", quoteBuildConfigString(androidHostVersion))
        buildConfigField("String", "STAI_GITHUB_REPOSITORY", quoteBuildConfigString("jialmaster/ST.AI.SillyTavern.Android"))
    }

    buildTypes {
        release {
            if (requiresReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("zip")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.yaml:snakeyaml:2.2")
}