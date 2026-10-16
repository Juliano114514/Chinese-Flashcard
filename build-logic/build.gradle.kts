plugins { `kotlin-dsl` }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
  // The root build supplies these plugins at the same catalog versions.
  compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
  compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}

gradlePlugin {
  plugins {
    register("androidApplication") {
      id = "flashcard.android.application"
      implementationClass = "com.example.chinese_flashcard.buildlogic.AndroidApplicationConventionPlugin"
    }
    register("androidLibrary") {
      id = "flashcard.android.library"
      implementationClass = "com.example.chinese_flashcard.buildlogic.AndroidLibraryConventionPlugin"
    }
    register("androidCompose") {
      id = "flashcard.android.compose"
      implementationClass = "com.example.chinese_flashcard.buildlogic.AndroidComposeConventionPlugin"
    }
  }
}
