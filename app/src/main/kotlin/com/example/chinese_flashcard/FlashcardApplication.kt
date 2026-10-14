package com.example.chinese_flashcard

import android.app.Application
import com.example.chinese_flashcard.core.data.FlashcardRepositories
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.StudySnapshot
import com.example.chinese_flashcard.core.domain.wordSpeechResources
import com.example.chinese_flashcard.core.media.OfflineSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FlashcardApplication : Application() {
  val repositories: FlashcardRepositories by lazy { FlashcardRepositories(this) }
  val speech: OfflineSpeech by lazy { OfflineSpeech(this) }
  private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var coldSpeechPreloadStarted = false

  /** Called after normal startup has prepared the database and the current daily plan. */
  @Synchronized
  fun preloadLearnSpeechOnce(settings: StudySettings, snapshot: StudySnapshot) {
    if (coldSpeechPreloadStarted) return
    coldSpeechPreloadStarted = true
    if (settings.speechPreloadWords == 0) return
    speechScope.launch {
      try {
        val stage = snapshot.today.stageProgress.stage
        val words = repositories.study.peekLearnSpeechWords(settings.speechPreloadWords,
          snapshot.learningLanguage, snapshot.contextVersion, stage)
        for (word in words) {
          // A direction/stage change abandons this startup window without launching a second one.
          val remaining = repositories.study.peekLearnSpeechWords(settings.speechPreloadWords,
            snapshot.learningLanguage, snapshot.contextVersion, stage)
          if (remaining.isEmpty()) break
          if (remaining.none { it.id == word.id }) continue
          speech.prefetch(wordSpeechResources(word))
        }
      } catch (error: CancellationException) { throw error }
      catch (_: Exception) { /* Startup preparation is optional and never advances study. */ }
      catch (_: LinkageError) { /* Actual playback retains the installed offline voice fallback. */ }
    }
  }
}
