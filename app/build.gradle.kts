import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 署名情報: keystore.properties (ローカル) または環境変数 (CI)
val ks = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signProp(key: String, env: String): String? = ks.getProperty(key) ?: System.getenv(env)

android {
    namespace = "io.github.nuko6925.flickkb"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.nuko6925.flickkb"
        minSdk = 29
        targetSdk = 35
        versionCode = 15
        versionName = "0.6.4"
    }

    signingConfigs {
        create("release") {
            val file = signProp("storeFile", "KEYSTORE_FILE")
            if (file != null) {
                storeFile = rootProject.file(file)
                storePassword = signProp("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signProp("keyAlias", "KEY_ALIAS")
                keyPassword = signProp("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            if (rel.storeFile != null) signingConfig = rel
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 自動入力のインライン候補の見た目 (InlineSuggestionUi)
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("androidx.annotation:annotation:1.9.1")
}
