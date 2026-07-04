import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.yukiai"

    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.yukiai"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(FileInputStream(localPropertiesFile))
        }

        val apiKey = properties.getProperty("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/**/libsherpa-onnx-*.so"
            pickFirsts += "lib/**/libonnxruntime*.so"
            pickFirsts += "lib/**/libmediapipe_tasks_vision_jni.so"
        }
    }

    aaptOptions {
        noCompress("tflite")
    }

    buildFeatures {
        buildConfig = true
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.3")

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
//    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    implementation("com.google.code.gson:gson:2.10.1")

    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1") // добавить

    implementation(libs.camera.core)
    implementation(libs.camera.view)
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")

    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}