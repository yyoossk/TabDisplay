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
        // ビルド番号（GitHub Actions の実行番号）。PC ソフトと同じ番号になり、新しいか比べるのに使う
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    // 署名：毎回同じ鍵で署名しないと、アプリの更新（上書き）ができない。
    // GitHub の Secrets に鍵があればそれを、なければリポジトリの tabdisplay.jks を使う
    signingConfigs {
        create("release") {
            val f = System.getenv("SIGNING_STORE_FILE")
            if (f != null && f.isNotEmpty()) {
                storeFile = file(f)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                storeFile = file("tabdisplay.jks")
                storePassword = "tabdisplay"
                keyAlias = "tabdisplay"
                keyPassword = "tabdisplay"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 更新履歴（リポジトリの changelog/CHANGELOG.md を、PC ソフトと同じものをアプリに入れる）
    sourceSets["main"].assets.srcDirs("../../changelog")

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
