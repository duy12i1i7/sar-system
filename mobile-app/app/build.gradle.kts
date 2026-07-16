import java.util.Properties

// Khoa API doc tu local.properties (da gitignore) roi bom vao manifest luc build.
// KHONG hardcode trong AndroidManifest.xml: repo nay public, khoa Google Maps ma lo
// ra la nguoi khac goi duoc va hoa don ve tai khoan minh.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = localProps.getProperty(name) ?: ""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.djidrone.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.djidrone.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["DJI_API_KEY"] = secret("DJI_API_KEY")
        manifestPlaceholders["MAPS_API_KEY"] = secret("MAPS_API_KEY")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "META-INF/rxjava.properties"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // DJI SDK v5
    implementation("com.dji:dji-sdk-v5-aircraft:5.18.0")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.18.0")
    implementation("com.dji:dji-sdk-v5-networkImp:5.18.0")
    implementation(project(":android-sdk-v5-uxsdk"))

    // MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")
}
