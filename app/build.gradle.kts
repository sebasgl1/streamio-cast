plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sebasgl1.streamiocast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sebasgl1.streamiocast"
        // libtorrent4j 2.1.0-39 exige Android 9 (API 28) o superior.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"

        // Solo celulares de 64 bits (todos los de los últimos años): APK mucho más liviano.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Con el NDK disponible, Gradle quita los símbolos de depuración de las librerías nativas.
    System.getenv("ANDROID_NDK_LATEST_HOME")?.let { ndkVersion = java.io.File(it).name }

    // Firma fija para que cada APK nuevo se pueda instalar encima del anterior.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // El APK que se publica es el "debug": R8 quita el código que no se usa (sin ofuscar,
        // para que los informes de error sigan siendo legibles).
        debug {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Reproductor
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Chromecast (Google Cast SDK) + botón y selector de dispositivos
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Estilo de notificación multimedia (botones en la vista compacta y la pantalla de bloqueo)
    implementation("androidx.media:media:1.7.0")

    // Motor de torrents (libtorrent) + binario nativo para celulares ARM de 64 bits
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")

    testImplementation("junit:junit:4.13.2")
}
