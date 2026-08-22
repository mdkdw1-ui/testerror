plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // 패키지명을 Tesla Dash 코딩에 맞게 변경
    namespace = "com.example.tesladash"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tesladash"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // PKCS12 (.p12) 키스토어 서명 설정
    signingConfigs {
        create("release") {
            storeFile = file("${project.rootDir}/my-release-key.p12")
            storePassword = "1234"
            keyAlias = "my-alias"
            keyPassword = "l234"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // 🔥 Tesla Dash 네트워크 및 FCM 필수 의존성
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging-ktx:23.4.0")
}
