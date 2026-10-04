plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose) }
android {
  namespace = "com.example.chinese_flashcard.feature.writing"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig { minSdk = 28 }
  buildFeatures { compose = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:ui"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
}
dependencies { implementation(libs.androidx.activity.compose) }
