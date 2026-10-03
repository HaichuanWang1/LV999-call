import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ── 发布签名凭据：一律不入库 ────────────────────────────────────────────
// 优先读 local.properties（该文件已被 .gitignore 忽略），其次读环境变量。
// 两者都缺时不启用签名配置，构建照常成功、release 产出 *-unsigned.apk ——
// 新克隆的仓库本来也没有 release.jks，不该因为缺签名而整个构建失败。
// 配置方法见 README「发布签名」。
val signingProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun signingCredential(propKey: String, envKey: String): String? =
    (signingProps.getProperty(propKey) ?: System.getenv(envKey))?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile = signingCredential("lv999.storeFile", "LV999_STORE_FILE")
val releaseStorePassword = signingCredential("lv999.storePassword", "LV999_STORE_PASSWORD")
val releaseKeyAlias = signingCredential("lv999.keyAlias", "LV999_KEY_ALIAS")
val releaseKeyPassword = signingCredential("lv999.keyPassword", "LV999_KEY_PASSWORD")

/** 四项齐全才启用签名；缺任何一项都只降级，不报错。 */
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { it != null }

if (!hasReleaseSigning) {
    logger.lifecycle(
        "[lv999call] 未配置 release 签名凭据 → release 产物将未签名。" +
            "在 local.properties 写入 lv999.storeFile / lv999.storePassword / " +
            "lv999.keyAlias / lv999.keyPassword，或改用 LV999_* 环境变量。"
    )
}

android {
    namespace = "com.lv999call.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lv999call.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "1.8.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打包真机在用的两种 ARM 架构。libvosk.so 每个 ABI 约 8–9 MB，
        // 跟着 AAR 进来的 x86 / x86_64 只有模拟器用得上，白胖约 18 MB。
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // 相对路径以项目根为基准（release.jks 就放在仓库根），绝对路径原样使用
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没配凭据就保持 null：Gradle 产出 app-release-unsigned.apk
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // 默认（extractNativeLibs=false）会把 native 库原样存进 APK 以便直接 mmap，
            // 但 libvosk.so 的 deflate 压缩率高达 68%（8.9 MB → 2.8 MB）。
            // 改回「安装时解压」后下载体积立减约 11 MB；安装后总占用仍然更小
            // （旧：APK 120 MB 全在盘上；新：APK 84 MB + 解压出的库 17 MB ≈ 101 MB）。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Serialization
    implementation(libs.serialization.json)

    // Image loading
    implementation(libs.coil.compose)

    // Vosk offline ASR
    implementation(libs.vosk.android)

    // WorkManager：记忆提醒通知的周期调度（版本必须 2.9.0，见 libs.versions.toml 的注释）
    implementation(libs.work.runtime.ktx)
}
