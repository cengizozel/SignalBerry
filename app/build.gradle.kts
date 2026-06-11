plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.signalberry"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.signalberry"
        minSdk = 18
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {

    implementation("androidx.appcompat:appcompat:1.6.1")
    // implementation(libs.material)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    // androidTestImplementation(libs.ext.junit)
    // androidTestImplementation(libs.espresso.core)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // modern TLS engine bundled in the APK — gives Android 4.3 / BB10 TLS 1.3
    // (2.5.2 declares minSdk 9, so no override needed; verified loading on a Q10)
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}