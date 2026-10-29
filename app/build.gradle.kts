plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Short commit id baked into the app so you can verify which source an APK was built from.
// GitHub Actions sets GITHUB_SHA; local builds show "local".
val gitSha: String = (System.getenv("GITHUB_SHA") ?: "local").take(7)

android {
    namespace = "app.cloudplayx.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.cloudplayx.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Debug signing key. Google's Android OAuth client is bound to the SHA-1 of the key that signs the APK, so the
    // key MUST stay the same between builds. Supply it from outside the repository (never commit a keystore):
    //   -PCPX_KEYSTORE=/path/file.jks -PCPX_KEYSTORE_PASSWORD=... -PCPX_KEY_ALIAS=... -PCPX_KEY_PASSWORD=...
    // or the same names as environment variables. Without them Android's auto-generated debug key is used.
    val ksPath = (findProperty("CPX_KEYSTORE") as String?) ?: System.getenv("CPX_KEYSTORE")
    if (!ksPath.isNullOrBlank()) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(ksPath)
                storePassword = (findProperty("CPX_KEYSTORE_PASSWORD") as String?) ?: System.getenv("CPX_KEYSTORE_PASSWORD")
                keyAlias = (findProperty("CPX_KEY_ALIAS") as String?) ?: System.getenv("CPX_KEY_ALIAS")
                keyPassword = (findProperty("CPX_KEY_PASSWORD") as String?) ?: System.getenv("CPX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Release signing is deliberately not configured here: sign release builds outside the repository.
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
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    testImplementation("junit:junit:4.13.2")
}
