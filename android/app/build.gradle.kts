plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreFile = rootProject.file("keystore/lan-audio.jks")

// 口令/别名可用环境变量覆盖（CI 用 Secret 注入），未设置或为空时回落到本地开发默认值
fun envOr(name: String, fallback: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

// 版本号可由 -PlasVersion=x.y.z 传入（CI 用 tag 名），默认 1.0.0；versionCode 由版本号推导
val lasVersion = (project.findProperty("lasVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"
val lasVersionCode = lasVersion.split(".").let { p ->
    (p.getOrNull(0)?.toIntOrNull() ?: 1) * 10000 +
        (p.getOrNull(1)?.toIntOrNull() ?: 0) * 100 +
        (p.getOrNull(2)?.toIntOrNull() ?: 0)
}

android {
    namespace = "com.lasaudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lasaudio"
        minSdk = 26
        targetSdk = 35
        versionCode = lasVersionCode
        versionName = lasVersion
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                // 正式发布请用环境变量 LAS_KEYSTORE_PASSWORD / LAS_KEYSTORE_ALIAS 提供，
                // 默认值仅供本地开发（与 scripts/setup_toolchain.ps1 生成密钥时一致）。
                val ksPass = envOr("LAS_KEYSTORE_PASSWORD", "lanaudio")
                storePassword = ksPass
                keyAlias = envOr("LAS_KEYSTORE_ALIAS", "lanaudio")
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
