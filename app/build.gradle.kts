plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.jeep.aadiagnostic"
    compileSdk = 35
    defaultConfig { applicationId = "com.jeep.aadiagnostic"; minSdk = 29; targetSdk = 35; versionCode = 2; versionName = "2.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core:1.16.0") }