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

fun requireDebugSigningValue(name: String, value: String): String {
    if (value.isBlank()) {
        throw GradleException("缺少 Android debug 签名配置：$name")
    }

    return value
}

fun loadSigningProperties(relativePath: String): Properties {
    val properties = Properties()
    val propertiesFile = file(relativePath)

    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(properties::load)
    }

    return properties
}

fun resolveReleaseSigningValue(envName: String): String {
    return System.getenv(envName).orEmpty().trim()
}

fun resolveDebugSigningValue(
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
val debugSigningProperties = loadSigningProperties("signing/debug-signing.properties")
val debugSigningKeystorePath = resolveDebugSigningValue(
    envName = "SILLYDROID_ANDROID_DEBUG_KEYSTORE_PATH",
    propertyName = "storeFile",
    properties = debugSigningProperties
)
val debugSigningKeystorePassword = resolveDebugSigningValue(
    envName = "SILLYDROID_ANDROID_DEBUG_KEYSTORE_PASSWORD",
    propertyName = "storePassword",
    properties = debugSigningProperties
)
val debugSigningKeyAlias = resolveDebugSigningValue(
    envName = "SILLYDROID_ANDROID_DEBUG_KEY_ALIAS",
    propertyName = "keyAlias",
    properties = debugSigningProperties
)
val debugSigningKeyPassword = resolveDebugSigningValue(
    envName = "SILLYDROID_ANDROID_DEBUG_KEY_PASSWORD",
    propertyName = "keyPassword",
    properties = debugSigningProperties
)
// release 签名材料只允许从外部环境变量注入，避免正式签名 key 再次回退到仓库或开发机本地属性文件。
val releaseSigningKeystorePath = resolveReleaseSigningValue(
    envName = "SILLYDROID_ANDROID_RELEASE_KEYSTORE_PATH"
)
val releaseSigningKeystorePassword = resolveReleaseSigningValue(
    envName = "SILLYDROID_ANDROID_RELEASE_KEYSTORE_PASSWORD"
)
val releaseSigningKeyAlias = resolveReleaseSigningValue(
    envName = "SILLYDROID_ANDROID_RELEASE_KEY_ALIAS"
)
val releaseSigningKeyPassword = resolveReleaseSigningValue(
    envName = "SILLYDROID_ANDROID_RELEASE_KEY_PASSWORD"
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
        create("debugShared") {
            // debug 构建也必须走项目内显式 keystore，禁止不同机器回退到各自 ~/.android/debug.keystore，
            // 否则 gradlew 与 stage-4/apk 脚本会产出不同签名，导致同包名调试包无法覆盖安装。
            val signingKeystoreFile = file(
                requireDebugSigningValue(
                    name = "SILLYDROID_ANDROID_DEBUG_KEYSTORE_PATH / signing/debug-signing.properties:storeFile",
                    value = debugSigningKeystorePath
                )
            )

            if (!signingKeystoreFile.isFile) {
                throw GradleException("Android debug keystore 不存在：${signingKeystoreFile.absolutePath}")
            }

            storeFile = signingKeystoreFile
            storePassword = requireDebugSigningValue(
                name = "SILLYDROID_ANDROID_DEBUG_KEYSTORE_PASSWORD / signing/debug-signing.properties:storePassword",
                value = debugSigningKeystorePassword
            )
            keyAlias = requireDebugSigningValue(
                name = "SILLYDROID_ANDROID_DEBUG_KEY_ALIAS / signing/debug-signing.properties:keyAlias",
                value = debugSigningKeyAlias
            )
            keyPassword = requireDebugSigningValue(
                name = "SILLYDROID_ANDROID_DEBUG_KEY_PASSWORD / signing/debug-signing.properties:keyPassword",
                value = debugSigningKeyPassword
            )
        }

        create("release") {
            if (requiresReleaseSigning) {
                val signingKeystoreFile = file(
                    requireReleaseSigningValue(
                        name = "SILLYDROID_ANDROID_RELEASE_KEYSTORE_PATH",
                        value = releaseSigningKeystorePath
                    )
                )

                if (!signingKeystoreFile.isFile) {
                    throw GradleException("Android release keystore 不存在：${signingKeystoreFile.absolutePath}")
                }

                storeFile = signingKeystoreFile
                storePassword = requireReleaseSigningValue(
                    name = "SILLYDROID_ANDROID_RELEASE_KEYSTORE_PASSWORD",
                    value = releaseSigningKeystorePassword
                )
                keyAlias = requireReleaseSigningValue(
                    name = "SILLYDROID_ANDROID_RELEASE_KEY_ALIAS",
                    value = releaseSigningKeyAlias
                )
                keyPassword = requireReleaseSigningValue(
                    name = "SILLYDROID_ANDROID_RELEASE_KEY_PASSWORD",
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
        // App 内更新与官网首页共用同一份 latest JSON，避免继续扫描 GitHub Releases API
        // 并在 release 删除 / 编辑后读到过期的 latest 语义。
        buildConfigField(
            "String",
            "SILLYDROID_LATEST_RELEASE_METADATA_URL",
            quoteBuildConfigString("https://sd.jlmaster.online/api/releases/latest.json")
        )
    }

    buildTypes {
        debug {
            // 所有 debug 入口统一用项目内共享 debug key，保证本地 gradlew、WSL、stage-4 脚本产物签名一致。
            signingConfig = signingConfigs.getByName("debugShared")
            // debug 包需要和 release 同时安装到真机时，必须拆出独立 applicationId；
            // 仅改应用显示名不够，包管理器仍会把它们视为同一个应用。
            applicationIdSuffix = ".debug"
        }

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
    implementation(project(":core:model"))
    implementation(project(":feature:main"))
    implementation(project(":feature:settings"))
    implementation(project(":ui:update"))
    implementation(project(":data:logs"))
    implementation(project(":data:runtime"))
    implementation(project(":data:settings"))
    implementation(project(":data:extensions"))
    implementation(project(":data:update"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
}
