plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.yukichi006.fliphome"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yukichi006.fliphome"
        minSdk = 31
        targetSdk = 35
        // CI の実行番号を使い、更新のたびに大きくなるようにする
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        // 表示用のバージョンは VERSION ファイル（メジャー.マイナー.パッチ）。Releases のタグ（v<バージョン>）と揃え、
        // Obtainium が新旧を比べられるようにする
        versionName = rootProject.file("VERSION").readText().trim()
    }

    signingConfigs {
        // CI では Secrets の固定鍵で署名し、上書き更新できるようにする（FlipCover と同じ鍵を使える）
        getByName("debug") {
            System.getenv("FLIPHOME_KEYSTORE")?.let {
                storeFile = file(it)
                storePassword = System.getenv("FLIPHOME_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FLIPHOME_KEY_ALIAS") ?: "flipcover"
                keyPassword = System.getenv("FLIPHOME_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // リリース版のビルドで走る簡易チェック（lint）は、問題を記録だけして止めない
    lint { abortOnError = false }
}

// 画面はすべてコードで描くので、AndroidX などのライブラリは使わない（Kotlin の標準ライブラリだけ）
