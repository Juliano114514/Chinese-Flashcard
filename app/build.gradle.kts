plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.example.chinese_flashcard"
  compileSdk {
    version = release(36) {
      minorApiLevel = 1
    }
  }

  defaultConfig {
    applicationId = "com.example.chinese_flashcard"
    minSdk = 28
    targetSdk = 36
    versionCode = 12
    versionName = "1.5.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
  // ChineseSpeech maps this asset with openFd; EnglishSpeech reads its model as a stream.
  androidResources { noCompress += "chinese-speech/model-duration.onnx" }
  // Android extracts the unchanged native libraries at install time, reducing APK download size.
  packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(project(":core:domain"))
  implementation(project(":core:data"))
  implementation(project(":core:media"))
  implementation(project(":core:ui"))
  implementation(project(":feature:study"))
  implementation(project(":feature:profile"))
  implementation(project(":feature:writing"))
  implementation(project(":feature:wordlist"))
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.material3)
  implementation(libs.compose.icons)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.kotlinx.coroutines.core)
  debugImplementation(libs.compose.ui.tooling)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
}
