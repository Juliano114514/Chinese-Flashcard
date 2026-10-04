plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.example.chinese_flashcard.core.ui"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig { minSdk = 28 }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(project(":core:domain"))
  api(platform(libs.compose.bom))
  api(libs.compose.ui)
  api(libs.compose.foundation)
  api(libs.compose.material3)
  api(libs.compose.icons)
  api(libs.androidx.lifecycle.runtime.compose)
  api(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.compose.ui.tooling.preview)
}
