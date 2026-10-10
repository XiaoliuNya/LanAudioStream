plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreFile = rootProject.file("keystore/lan-audio.jks")

android {
    namespace = "com.lasaudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lasaudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                // 正式发布请用环境变量 LAS_KEYSTORE_PASSWORD / LAS_KEYSTORE_ALIAS 提供，
                // 默认值仅供本地开发（与 scripts/setup_toolchain.ps1 生成密钥时一致）。
                val ksPass = System.getenv("LAS_KEYSTORE_PASSWORD") ?: "lanaudio"
                storePassword = ksPass
                keyAlias = System.getenv("LAS_KEYSTORE_ALIAS") ?: "lanaudio"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("io.github.jaredmdobson:concentus:1.0.2")
}
