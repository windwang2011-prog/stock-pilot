plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stockpilot.probe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stockpilot.probe"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-probe"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 探针刻意不引入任何第三方依赖，最大化云端构建成功率
dependencies {
}
