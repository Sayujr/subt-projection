import java.time.Duration

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

    // One private key for every CI build (from GitHub secrets), so updates install over the existing
    // app and keep placed widgets and settings. Local builds without it use the normal debug key.
    val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    val ksPw = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
    if (ksPath != null && ksPw != null) {
        signingConfigs {
            create("stable") {
                storeFile = file(ksPath)
                storePassword = ksPw
                keyAlias = "trainingtoday"
                keyPassword = ksPw
            }
        }
    }

    buildTypes {
        debug {
            if (ksPath != null && ksPw != null) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            if (ksPath != null && ksPw != null) signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
        }
    }
    lint {
        // Release builds run a lint check by default; don't let warnings block the APK
        checkReleaseBuilds = false
        abortOnError = false
    }
    testOptions {
        unitTests {
            // Robolectric: real resources + native drawing, so tests can render the screens to PNGs
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("screens.dir", rootProject.file("build/screens").absolutePath)
                it.maxHeapSize = "2g"
                it.timeout.set(Duration.ofMinutes(10))
                it.testLogging { events("started", "passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.9.1")
}
