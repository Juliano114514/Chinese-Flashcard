package com.example.chinese_flashcard.feature.study

import android.os.SystemClock
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.AnswerOption
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.SpeechScene
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Main-thread playback coordination only. Study progress remains owned by the ViewModel/repository. */
internal class StudyPlayback(private val requests: (SpeechRequest) -> List<SpeechRequest> = { listOf(it) },
  private val speakAndWait: suspend (SpeechRequest) -> Boolean) {
  private var activeSequence: Job? = null
  private var lastCompletedAt: Long? = null

  suspend fun sequence(block: suspend Sequence.() -> Unit) = coroutineScope {
    cancel()
    val sequence = currentCoroutineContext().job
    activeSequence = sequence
    try { Sequence().block() }
    finally { if (activeSequence === sequence) activeSequence = null }
  }

  fun cancel() { activeSequence?.cancel(); activeSequence = null }

  inner class Sequence internal constructor() {
    suspend fun speak(request: SpeechRequest, immediate: Boolean = false): Boolean {
      val first = requests(request).firstOrNull() ?: return true
      if (request.text.isBlank()) return false
      if (!immediate) lastCompletedAt?.let { completedAt ->
        val gap = if (first.language == AppLanguage.EN) 250L else 500L
        delay((gap - (SystemClock.elapsedRealtime() - completedAt)).coerceAtLeast(0L))
      }
      currentCoroutineContext().ensureActive()
      val played = try { speakAndWait(request) }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { false }
      currentCoroutineContext().ensureActive()
      lastCompletedAt = if (played) SystemClock.elapsedRealtime() else null
      return played
    }
  }
}

internal fun StudyCard.contextExample(): ExampleSentence? = if (showContext && word.examples.isNotEmpty()) {
  word.examples[(round - 1).coerceAtLeast(0) % word.examples.size]
} else null

internal fun StudyCard.playbackExamples(): List<ExampleSentence> = when (phase) {
  CardPhase.INTRO, CardPhase.EXPLANATION -> word.examples
  CardPhase.QUESTION -> listOfNotNull(contextExample())
  else -> emptyList()
}

internal fun StudyCard.questionMeaning(): String = word.meanings.firstOrNull { it.id == questionMeaningId }
  ?.english ?: word.meanings.firstOrNull()?.english.orEmpty()

internal fun StudyCard.promptSpeech(): SpeechRequest = targetSpeech().copy(
  scene = if (phase == CardPhase.QUESTION) SpeechScene.STUDY_PROMPT else
    if (learningLanguage == AppLanguage.EN) SpeechScene.MEANING_PRONUNCIATION else SpeechScene.WORD_PRONUNCIATION)

internal fun StudyCard.targetSpeech(): SpeechRequest {
  val scene = when (phase) {
    CardPhase.QUESTION -> SpeechScene.STUDY_PROMPT
    CardPhase.FEEDBACK -> SpeechScene.ANSWER_FEEDBACK
    else -> SpeechScene.STUDY_EXPLANATION
  }
  // Before submission the counterpart is an answer, so it is deliberately unavailable.
  val counterpart = if (phase == CardPhase.QUESTION) null else
    if (learningLanguage == AppLanguage.CN) questionMeaning() else word.hanzi
  return SpeechRequest(if (learningLanguage == AppLanguage.CN) word.hanzi else questionMeaning(),
    learningLanguage, scene, counterpart)
}

internal fun StudyCard.optionSpeech(option: AnswerOption): SpeechRequest = if (learningLanguage == AppLanguage.CN)
  SpeechRequest(option.hanzi, AppLanguage.CN, SpeechScene.ANSWER_FEEDBACK, option.english)
  else SpeechRequest(option.english, AppLanguage.EN, SpeechScene.ANSWER_FEEDBACK, option.hanzi)

internal fun StudyCard.optionLabel(option: AnswerOption): String = if (learningLanguage == AppLanguage.CN)
  option.english else option.hanzi

internal fun StudyCard.exampleSpeech(example: ExampleSentence): SpeechRequest {
  val language = learningLanguage
  val scene = if (phase == CardPhase.QUESTION) SpeechScene.STUDY_CONTEXT else SpeechScene.STUDY_EXPLANATION
  val counterpart = if (phase == CardPhase.QUESTION) null else
    if (language == AppLanguage.CN) example.english else example.hanzi
  return SpeechRequest(if (language == AppLanguage.CN) example.hanzi else example.english, language, scene, counterpart)
}
