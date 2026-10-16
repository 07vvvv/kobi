plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xixi.timetable"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xixi.timetable"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        // 只打包手机实际使用的 ABI：ML Kit 的 native 库每个 ABI 约 2 MB，
        // 去掉 x86/x86_64 可抵消一部分体积增量（模拟器不在交付目标内）。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 固定签名：用仓库内的 kobi.keystore（PKCS12，密码 android，别名 androiddebugkey）。
    // 目的：debug 与 release 用同一个固定签名，CI 每次构建的证书指纹稳定，
    // 便于日后（若需要）绑定厂商开放平台的签名校验，也便于反复覆盖安装。
    signingConfigs {
        create("kobi") {
            storeFile = file("kobi.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kobi")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("kobi")
        }
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    // Room 本地数据库
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 显式声明协程依赖：data 与 notify 两个模块都直接使用 kotlinx.coroutines
    // （CoroutineScope / Dispatchers.IO / Flow），不依赖 lifecycle 或 room 的传递依赖，
    // 避免传递依赖版本漂移导致编译失败。
    implementation(libs.kotlinx.coroutines.android)

    // 图片识别课程：ML Kit 内置中文文本识别（模型打进 APK，不联网、不依赖 GMS）
    implementation(libs.mlkit.text.recognition.chinese)
}
