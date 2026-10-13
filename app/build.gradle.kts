import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firma de distribución: solo si las variables de entorno (o release.properties) existen. Nunca se guardan claves en el repo.
val releaseProps = Properties().apply {
    val f = rootProject.file("release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun sign(key: String, env: String): String? = System.getenv(env) ?: releaseProps.getProperty(key)
val releaseStore = sign("storeFile", "RELEASE_KEYSTORE_PATH")
val hasReleaseSigning = releaseStore != null && file(releaseStore).exists() &&
    sign("storePassword", "RELEASE_STORE_PASSWORD") != null && sign("keyAlias", "RELEASE_KEY_ALIAS") != null && sign("keyPassword", "RELEASE_KEY_PASSWORD") != null

android {
    namespace = "dev.dreamrally.citybuilder"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.dreamrally.citybuilder"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0.0"
    }
    signingConfigs {
        // Clave de depuración fija y pública (app/debug.keystore): las APK de depuración de cualquier build se actualizan unas sobre otras.
        getByName("debug") {
            storeFile = file("debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
        if (hasReleaseSigning) create("release") {
            storeFile = file(releaseStore!!)
            storePassword = sign("storePassword", "RELEASE_STORE_PASSWORD")
            keyAlias = sign("keyAlias", "RELEASE_KEY_ALIAS")
            keyPassword = sign("keyPassword", "RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            // Sin R8: no se puede probar en dispositivo en este entorno; se prefiere un APK más grande pero sin riesgo de reflexión rota.
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
