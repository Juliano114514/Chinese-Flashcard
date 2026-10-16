plugins { id("flashcard.android.library"); id("flashcard.android.compose") }
android {
  namespace = "com.example.chinese_flashcard.feature.profile"
}
dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:ui"))
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
  implementation(libs.kotlinx.coroutines.core)
}
