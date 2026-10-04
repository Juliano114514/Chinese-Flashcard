plugins { alias(libs.plugins.android.library); alias(libs.plugins.ksp) }

android {
  namespace = "com.example.chinese_flashcard.core.data"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  defaultConfig { minSdk = 28 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(project(":core:domain"))
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)
  implementation(libs.kotlinx.coroutines.core)
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
