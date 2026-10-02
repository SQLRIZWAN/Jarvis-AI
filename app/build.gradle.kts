plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sqlai.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sqlai.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "5.4"

        // Dynamic launcher label: version-stamped app name, single source of
        // truth (strings.xml no longer defines app_name - duplicate resource
        // would break the build).
        resValue("string", "app_name", "SQL AI v$versionName Pro")

        vectorDrawables.useSupportLibrary = true
    }

    // Stable signing: CI injects SQLAI_KEYSTORE_* secrets so EVERY build has
    // the SAME certificate (auto-generated debug keystores change per runner
    // and make upgrades fail with "package invalid"). Local builds without
    // the env vars fall back to the normal debug keystore.
    signingConfigs {
        if (System.getenv("SQLAI_KEYSTORE_FILE") != null) {
            create("stable") {
                storeFile = file(System.getenv("SQLAI_KEYSTORE_FILE")!!)
                storePassword = System.getenv("SQLAI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SQLAI_KEY_ALIAS")
                keyPassword = System.getenv("SQLAI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("SQLAI_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        debug {
            isMinifyEnabled = false
            if (System.getenv("SQLAI_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("stable")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
