plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kaleem.blindassist"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.kaleem.blindassist"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "tflite" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    val camx = "1.3.4"
    implementation("androidx.camera:camera-core:$camx")
    implementation("androidx.camera:camera-camera2:$camx")
    implementation("androidx.camera:camera-lifecycle:$camx")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    testImplementation("junit:junit:4.13.2")
}

// Downloads the on-device object-detection model once (needs internet at build time).
// Offline? Put efficientdet_lite0.tflite into app/src/main/assets/ yourself, or pass -PskipModel (unit tests do not need it).
val modelUrl = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite"
val modelFile = file("src/main/assets/efficientdet_lite0.tflite")
val downloadModel by tasks.registering {
    onlyIf { !project.hasProperty("skipModel") }
    outputs.file(modelFile)
    doLast {
        if (!modelFile.exists()) {
            modelFile.parentFile.mkdirs()
            uri(modelUrl).toURL().openStream().use { i -> modelFile.outputStream().use { o -> i.copyTo(o) } }
        }
    }
}
// Only packaging steps need the model; `./gradlew testDebugUnitTest` should not hit the network.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(downloadModel)
}
