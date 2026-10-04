plugins { alias(libs.plugins.android.library) }
android {
  namespace = "com.example.chinese_flashcard.core.media"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig { minSdk = 28 }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies { implementation(project(":core:domain")); implementation(libs.kotlinx.coroutines.core) }
