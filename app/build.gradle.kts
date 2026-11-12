import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Local signing key lives in signing/ (git-ignored): keystore.properties + bbhost.jks.
val signingProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.isFile) f.inputStream().use(::load)
}

android {
    // The namespace stays com.jarves.mh: the native JNI symbols are named after it.
    namespace = "com.jarves.mh"
    compileSdk = 36
    // NDK 28 emits flexible-page-size binaries for the static PRoot loader.
    ndkVersion = providers.gradleProperty("mhNdkVersion").orNull ?: "28.2.13676358"

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("local") {
                storeFile = rootProject.file("signing/bbhost.jks")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.jarves.mh.bbhost"
        minSdk = 28
        // Target 28 keeps the PRoot execution path the original app proved.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.10-bbhost.1"
        ndk.abiFilters += "arm64-v8a"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("local")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures.buildConfig = false
    lint.checkReleaseBuilds = false
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging.jniLibs.useLegacyPackaging = true
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.7-12@aar")

    testImplementation("junit:junit:4.13.2")
}
