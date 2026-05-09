import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync
import java.util.Properties

fun resolvePackagedBuildConfigFile() = sequenceOf(
    rootProject.file("sillydroid-build-config.json"),
    rootProject.file("../sillydroid-build-config.json")
).firstOrNull { candidate -> candidate.isFile }

val packagedBootstrapAssetsDir = layout.buildDirectory.dir("generated/sillydroid-bootstrap-assets")
val packagedBootstrapAssetsDirFile = packagedBootstrapAssetsDir.get().asFile
val packagedBuildConfigFile = resolvePackagedBuildConfigFile()
val bundledExtensionsSourceDir = rootProject.file("extensions")
val syncBootstrapExtensionAssets by tasks.registering(Sync::class) {
    into(packagedBootstrapAssetsDir)

    if (bundledExtensionsSourceDir.isDirectory) {
        from(bundledExtensionsSourceDir) {
            into("bootstrap/bundled-extensions")
        }
    }

    if (packagedBuildConfigFile != null) {
        from(packagedBuildConfigFile) {
            into("bootstrap/default-extensions")
            rename { "sillydroid-build-config.json" }
        }
    }
}

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
    envName = "SILLYDROID_ANDROID_RELEASE_KEYSTORE_PATH",
    propertyName = "storeFile",
    properties = releaseSigningProperties
)
val releaseSigningKeystorePassword = resolveReleaseSigningValue(
    envName = "SILLYDROID_ANDROID_RELEASE_KEYSTORE_PASSWORD",
    propertyName = "storePassword",
    properties = releaseSigningProperties
)
val releaseSigningKeyAlias = resolveReleaseSigningValue(
    envName = "SILLYDROID_ANDROID_RELEASE_KEY_ALIAS",
    propertyName = "keyAlias",
    properties = releaseSigningProperties
)
val releaseSigningKeyPassword = resolveReleaseSigningValue(
    envName = "SILLYDROID_ANDROID_RELEASE_KEY_PASSWORD",
    propertyName = "keyPassword",
    properties = releaseSigningProperties
)

fun resolveAndroidVersionCode(rawValue: String): Int {
    if (rawValue.isBlank()) {
        return 1
    }

    return rawValue.toIntOrNull()?.takeIf { it > 0 }
        ?: throw GradleException("SILLYDROID_ANDROID_VERSION_CODE 必须是正整数：$rawValue")
}

fun resolveAndroidHostVersion(): String {
    val envValue = System.getenv("SILLYDROID_ANDROID_HOST_VERSION").orEmpty().trim()
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

val androidVersionCode = resolveAndroidVersionCode(System.getenv("SILLYDROID_ANDROID_VERSION_CODE").orEmpty().trim())
val androidHostVersion = resolveAndroidHostVersion()
val androidUpstreamVersion = System.getenv("SILLYDROID_ANDROID_UPSTREAM_VERSION").orEmpty().trim()
val androidVersionName = resolveAndroidVersionName(
    rawValue = System.getenv("SILLYDROID_ANDROID_VERSION_NAME").orEmpty().trim(),
    hostVersion = androidHostVersion,
    upstreamVersion = androidUpstreamVersion
)

android {
    namespace = "com.jm.sillydroid"
    compileSdk = 36

    signingConfigs {
        create("release") {
            if (requiresReleaseSigning) {
                val signingKeystoreFile = file(
                    requireReleaseSigningValue(
                        name = "SILLYDROID_ANDROID_RELEASE_KEYSTORE_PATH / signing/release-signing.properties:storeFile",
                        value = releaseSigningKeystorePath
                    )
                )

                if (!signingKeystoreFile.isFile) {
                    throw GradleException("Android release keystore 不存在：${signingKeystoreFile.absolutePath}")
                }

                storeFile = signingKeystoreFile
                storePassword = requireReleaseSigningValue(
                    name = "SILLYDROID_ANDROID_RELEASE_KEYSTORE_PASSWORD / signing/release-signing.properties:storePassword",
                    value = releaseSigningKeystorePassword
                )
                keyAlias = requireReleaseSigningValue(
                    name = "SILLYDROID_ANDROID_RELEASE_KEY_ALIAS / signing/release-signing.properties:keyAlias",
                    value = releaseSigningKeyAlias
                )
                keyPassword = requireReleaseSigningValue(
                    name = "SILLYDROID_ANDROID_RELEASE_KEY_PASSWORD / signing/release-signing.properties:keyPassword",
                    value = releaseSigningKeyPassword
                )
            }
        }
    }

    defaultConfig {
        applicationId = "com.jm.sillydroid"
        minSdk = 29
        targetSdk = 36
        versionCode = androidVersionCode
        versionName = androidVersionName
        buildConfigField("String", "SILLYDROID_HOST_VERSION", quoteBuildConfigString(androidHostVersion))
        buildConfigField("String", "SILLYDROID_UPSTREAM_VERSION", quoteBuildConfigString(androidUpstreamVersion))
        buildConfigField("String", "SILLYDROID_GITHUB_REPOSITORY", quoteBuildConfigString("jialmaster/SillyDroid"))
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

    sourceSets.getByName("main").assets.srcDir(packagedBootstrapAssetsDirFile)

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncBootstrapExtensionAssets)
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
