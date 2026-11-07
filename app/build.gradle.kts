plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mhmmtbg.x5reframe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mhmmtbg.x5reframe"
        minSdk = 29
        targetSdk = 34
        versionCode = 22
        versionName = "1.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Sabit imza: her CI derlemesi aynı anahtarla imzalanır, böylece telefonda güncelleme kurulabilir.
    // (Varsayılan debug anahtarı her GitHub Actions makinesinde yeniden üretiliyordu.)
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = "x5reframe-ks"
            keyAlias = "x5reframe"
            keyPassword = "x5reframe-ks"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            // Telefonlar 64 bit ARM: diğer mimarilerin yerel kütüphaneleri APK'yı gereksiz büyütüyor.
            ndk { abiFilters += listOf("arm64-v8a") }
            signingConfig = signingConfigs.getByName("stable")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Cihaz içi (modeli APK'ya gömülü, internetsiz) yüz ve nesne algılama
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
