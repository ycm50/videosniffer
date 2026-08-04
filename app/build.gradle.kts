plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.videosniffer"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.videosniffer"
        minSdk = 29
        targetSdk = 36
        // 版本号由 CI 传入（-PVERSION_NAME / -PVERSION_CODE），本地默认 1.0 / 1
        versionCode = (project.findProperty("VERSION_CODE")?.toString() ?: "1").toInt()
        versionName = project.findProperty("VERSION_NAME")?.toString() ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // 密码固定 123456，别名 release；keystore 文件由 CI 的 KEYSTORE_BASE64 解密提供
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrEmpty()) {
                storeFile = file(keystorePath)
                storePassword = "123456"
                keyAlias = "release"
                keyPassword = "123456"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true // 开启 R8 代码压缩
            }
            val hasKeystore = !System.getenv("KEYSTORE_PATH").isNullOrEmpty()
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // debug 不开启 R8，便于调试
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}