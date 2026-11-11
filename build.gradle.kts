plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "eu.kanade.tachiyomi.extension.zh.bzmgcn"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.zh.bzmgcn"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*", "kotlin/**")
    }
}

dependencies {
    compileOnly("com.github.keiyoushi:extensions-lib:1.4.2.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
