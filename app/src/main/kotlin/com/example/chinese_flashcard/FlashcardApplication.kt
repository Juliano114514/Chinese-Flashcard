package com.example.chinese_flashcard

import android.app.Application
import com.example.chinese_flashcard.core.data.FlashcardRepositories
import com.example.chinese_flashcard.core.media.OfflineSpeech

class FlashcardApplication : Application() {
  val repositories: FlashcardRepositories by lazy { FlashcardRepositories(this) }
  val speech: OfflineSpeech by lazy { OfflineSpeech(this) }
}
