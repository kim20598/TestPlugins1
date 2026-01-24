// use an integer for version numbers
version = 4

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
}

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.akwam"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    // REMOVE the problematic kotlinOptions block entirely
}

dependencies {
    val cloudstream by configurations

    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.room:room-ktx:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    cloudstream("com.lagradost:cloudstream3:pre-release")
}

cloudstream {
    language = "ar"
    authors = listOf("kim20598")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&amp;type=FAVICON&amp;fallback_opts=TYPE,SIZE,URL&amp;url=https://a.asd.homes&amp;size=%size%"
    isCrossPlatform = true
}
