import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.roondial"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.roondial"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.10.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the artifact is directly installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // MediaSession is how Gemini, the notification, the lock screen, headset
    // buttons and Wear all reach playback controls.
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath, ahead of the android.jar stub.
    testImplementation("org.json:json:20240303")
    // Renders the dial for real on the JVM, so the drawing and the rotary
    // gesture maths are exercised without a device.
    testImplementation("org.robolectric:robolectric:4.16.1")
}
