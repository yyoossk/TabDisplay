plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yyoossk.tabdisplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yyoossk.tabdisplay"
        minSdk = 23          // Android 6
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val f = System.getenv("SIGNING_STORE_FILE")
            if (f != null && f.isNotEmpty()) {
                storeFile = file(f)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val f = System.getenv("SIGNING_STORE_FILE")
            signingConfig = if (f != null && f.isNotEmpty()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // QR コード読み取り。core 3.3.0 は Android 6 (API 23) でも動く版
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.3.0")
}
