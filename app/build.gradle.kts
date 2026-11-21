plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.jeep.aadiagnostic"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jeep.aadiagnostic"
        minSdk = 29
        targetSdk = 35
        versionCode = providers.gradleProperty("releaseCode").orNull?.toIntOrNull() ?: 3
        versionName = providers.gradleProperty("releaseName").orNull ?: "3.0"
    }
    signingConfigs {
        create("githubRelease") {
            val path = System.getenv("JEEP_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("JEEP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JEEP_KEY_ALIAS")
                keyPassword = System.getenv("JEEP_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!System.getenv("JEEP_KEYSTORE_PATH").isNullOrBlank()) signingConfig = signingConfigs.getByName("githubRelease")
            isMinifyEnabled = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core:1.16.0") }
