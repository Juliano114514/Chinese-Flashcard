package com.example.chinese_flashcard.feature.study

import android.os.SystemClock
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.StudyCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Main-thread playback coordination only. Study progress remains owned by the ViewModel/repository. */
internal class StudyPlayback(private val speakAndWait: suspend (String) -> Boolean) {
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
    suspend fun speak(text: String, immediate: Boolean = false): Boolean {
      if (text.isBlank()) return false
      if (!immediate) lastCompletedAt?.let { completedAt ->
        delay((500L - (SystemClock.elapsedRealtime() - completedAt)).coerceAtLeast(0L))
      }
      currentCoroutineContext().ensureActive()
      val played = speakAndWait(text)
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
