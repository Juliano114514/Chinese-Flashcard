plugins {
  id("flashcard.android.library")
  id("flashcard.android.compose")
}

android {
  namespace = "com.example.chinese_flashcard.core.ui"
}

dependencies {
  implementation(project(":core:domain"))
  implementation(libs.kotlinx.coroutines.core)
  api(platform(libs.compose.bom))
  api(libs.compose.ui)
  api(libs.compose.foundation)
  api(libs.compose.material3)
  api(libs.compose.icons)
  api(libs.androidx.lifecycle.runtime.compose)
  api(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
}
