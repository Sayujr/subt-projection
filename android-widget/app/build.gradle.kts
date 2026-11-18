plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.subt.trainingwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.subt.trainingwidget"
        minSdk = 26
        targetSdk = 35
        // CI run number, so every build is a newer version that installs over the last one
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
    }

    // One fixed key for every build, so updates install over the existing app
    // (keeping placed widgets and settings) instead of needing an uninstall.
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("keystore/training-widget.jks")
            storePassword = "trainingwidget"
            keyAlias = "trainingwidget"
            keyPassword = "trainingwidget"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            signingConfig = signingConfigs.getByName("stable")
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

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
