plugins { id("flashcard.android.library"); id("flashcard.android.compose") }
android {
  namespace = "com.example.chinese_flashcard.feature.wordlist"
}
dependencies {
  implementation(project(":core:domain"))
  implementation(project(":core:ui"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.activity.compose)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
}
