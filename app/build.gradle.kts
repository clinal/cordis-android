plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.clinal.cordis"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    val ciKeystoreFile = System.getenv("CORDIS_ANDROID_KEYSTORE_FILE")
    val ciKeystorePassword = System.getenv("CORDIS_ANDROID_KEYSTORE_PASSWORD")
    val ciKeyAlias = System.getenv("CORDIS_ANDROID_KEY_ALIAS").takeUnless { it.isNullOrBlank() } ?: "cordis-ci"
    val ciKeyPassword = System.getenv("CORDIS_ANDROID_KEY_PASSWORD").takeUnless { it.isNullOrBlank() } ?: ciKeystorePassword
    val hasCiSigning = listOf(
        ciKeystoreFile,
        ciKeystorePassword,
        ciKeyPassword,
    ).all { !it.isNullOrBlank() }
    val packageVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
        .find(rootProject.file("plugins/android/packages/android/package.json").readText())
        ?.groupValues
        ?.get(1)
        ?: error("Unable to read cordis-plugin-android version")

    defaultConfig {
        applicationId = "io.github.clinal.cordis"
        minSdk = 28
        targetSdk = 28
        versionCode = System.getenv("CORDIS_ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("CORDIS_ANDROID_VERSION_NAME") ?: packageVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                storeFile = file(ciKeystoreFile!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            isMinifyEnabled = false
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    buildFeatures {
        compose = true
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    val bootstrapAssetsDir = providers.gradleProperty("cordisBootstrapAssetsDir")
    sourceSets {
        named("main") {
            bootstrapAssetsDir.orNull?.let { assets.srcDir(rootProject.file("$it/assets")) }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
