plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sahand.cortextchat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sahand.cortextchat"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.4.3"
    }

    // Updates only install over the old version when signed with the SAME
    // key. The debug.keystore in the PROJECT ROOT is that key (the same one
    // build-manual.sh signs with), and it is committed on purpose so the
    // GitHub Actions APK installs over earlier builds. rootProject.file —
    // not file(), which would look in app/ and silently fall back to a
    // throwaway key.
    val sharedKeystore = rootProject.file("debug.keystore")
    if (sharedKeystore.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = sharedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// No third-party dependencies on purpose: the app uses only the Android
// framework + Kotlin stdlib, so it builds with a plain kotlinc/aapt2/d8
// toolchain as well as with Gradle in Android Studio.
dependencies {
}
