plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.seb.retropop"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.seb.retropop"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Le Freebox Pop Player et la plupart des box Android TV sont en ARM.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // Clé de debug versionnée dans le dépôt (keystore/debug.keystore) :
        // chaque build CI a la même signature, donc les mises à jour s'installent
        // par-dessus sans perdre les sauvegardes.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // Usage perso en sideload : on signe la release avec la même clé.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // Les cœurs libretro sont chargés par chemin : on les extrait sur le disque.
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.github.Swordfish90:LibretroDroid:0.14.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Mini serveur web pour envoyer les jeux depuis un téléphone ou un PC
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
