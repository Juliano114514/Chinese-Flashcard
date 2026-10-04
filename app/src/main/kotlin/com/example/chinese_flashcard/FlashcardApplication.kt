package com.example.chinese_flashcard

import android.app.Application
import com.example.chinese_flashcard.core.data.FlashcardRepositories

class FlashcardApplication : Application() {
  val repositories: FlashcardRepositories by lazy { FlashcardRepositories(this) }
}
