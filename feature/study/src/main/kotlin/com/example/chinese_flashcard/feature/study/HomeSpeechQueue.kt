package com.example.chinese_flashcard.feature.study

import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.ChinesePronunciation
import com.example.chinese_flashcard.core.domain.SpeechMode
import com.example.chinese_flashcard.core.domain.SpeechOptions
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.SpeechScene
import com.example.chinese_flashcard.core.domain.SpeechCachePriority
import com.example.chinese_flashcard.core.domain.StudySettingsRules
import com.example.chinese_flashcard.core.domain.WordlistItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val HOME_SHUFFLE_SOURCE_ID = "home/shuffle"
data class HomeSpeechEvent(val token: Long, val request: SpeechRequest)

internal fun WordlistItem.homeSpeechRequest(language: AppLanguage, sourceId: String): SpeechRequest = SpeechRequest(
  if (language == AppLanguage.CN) hanzi else english, language,
  SpeechScene.HOME_WORD, if (language == AppLanguage.CN) english else hanzi,
  ChinesePronunciation(hanzi, pinyin), sourceId = sourceId,
)

/** Main-thread queue ownership; media work is injected and never changes study progress. */
internal class HomeSpeechQueue(
  private val scope: CoroutineScope,
  private val prepare: suspend (List<SpeechRequest>, Int) -> Boolean,
  private val isReady: suspend (List<SpeechRequest>) -> Boolean,
  private val currentWord: () -> WordlistItem?,
  private val onWaiting: (Boolean, Boolean) -> Unit,
  private val onSelected: (WordlistItem, SpeechRequest) -> Unit,
  private val onReadyCount: (Int) -> Unit = {},
) {
  private data class Spec(val language: AppLanguage, val mode: SpeechMode, val capacity: Int)
  private data class AudioWord(val id: String, val hanzi: String, val pinyin: String, val english: String)
  private class Candidate(val word: WordlistItem, val requests: List<SpeechRequest>) {
    var ready = false
    var failed = false
    var readinessRetries = 0
    var job: Job? = null
  }
  private var active = false
  private var generation = 0L
  private var words = emptyList<WordlistItem>()
  private var audioWords = emptyList<AudioWord>()
  private var spec = Spec(AppLanguage.CN, SpeechMode.ONLY_CN, StudySettingsRules.DEFAULT_HOME_SHUFFLE_QUEUE_SIZE)
  private val candidates = mutableListOf<Candidate>()
  private var warmJob: Job? = null
  private var selectionJob: Job? = null
  private var validationJob: Job? = null
  private var validationPending = false
  private var validationRetryPending = false
  private var pending = false

  fun configure(pool: List<WordlistItem>, language: AppLanguage, options: SpeechOptions, capacity: Int) {
    val next = Spec(language, options.mode(SpeechScene.HOME_WORD, language), capacity)
    val content = pool.map { AudioWord(it.id, it.hanzi, it.pinyin, it.english) }
    words = pool
    if (next == spec && content == audioWords) return
    spec = next
    audioWords = content
    reset()
  }

  fun setActive(value: Boolean) {
    if (active == value) return
    active = value
    generation++
    cancelJobs()
    pending = false
    onWaiting(false, false)
    // Retain the unseen candidates for this ViewModel lifecycle; only their work is paused.
    if (active) {
      warmCurrent()
      fill()
      refreshReadiness()
    }
    publishReadyCount()
  }

  fun refreshReadiness(retryMissing: Boolean = true) {
    if (!active) return
    if (validationJob?.isActive == true) {
      validationPending = true
      validationRetryPending = validationRetryPending || retryMissing
      return
    }
    val version = generation
    val job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        for (candidate in candidates.toList()) {
          val available = quietlyReady(candidate.requests)
          if (version != generation || !active) return@launch
          if (candidate !in candidates) continue
          candidate.ready = available
          if (available) candidate.failed = false
          else if (retryMissing && candidate.job?.isActive != true && !candidate.failed) prepare(candidate)
        }
        publishReadyCount()
        consumeReady()
      } finally {
        if (version == generation) {
          validationJob = null
          if (validationPending) {
            val retry = validationRetryPending
            validationPending = false
            validationRetryPending = false
            refreshReadiness(retry)
          }
        }
      }
    }
    validationJob = job
    job.start()
  }

  fun shuffle() {
    if (!active || pending || words.isEmpty()) return
    if (candidates.isEmpty() || candidates.all { it.failed }) reset()
    candidates.forEach { it.readinessRetries = 0 }
    pending = true
    onWaiting(true, false)
    refreshReadiness()
    consumeReady()
  }

  private fun reset() {
    generation++
    cancelJobs()
    candidates.clear()
    pending = false
    onWaiting(false, false)
    publishReadyCount()
    if (!active) return
    warmCurrent()
    fill()
  }

  private fun cancelJobs() {
    warmJob?.cancel()
    warmJob = null
    selectionJob?.cancel()
    selectionJob = null
    validationJob?.cancel()
    validationJob = null
    validationPending = false
    validationRetryPending = false
    candidates.forEach { it.job?.cancel(); it.job = null }
  }

  private fun warmCurrent() {
    // Queue the currently displayed word before the unseen candidates.
    currentWord()?.let { word -> warmJob = scope.launch {
      quietlyPrepare(requests(word), SpeechCachePriority.CURRENT_WORD)
    } }
  }

  private fun publishReadyCount() = onReadyCount(candidates.count { it.ready && !it.failed })

  private fun request(word: WordlistItem): SpeechRequest = word.homeSpeechRequest(spec.language, HOME_SHUFFLE_SOURCE_ID)

  private fun requests(word: WordlistItem) = SpeechOptions(mapOf(SpeechScene.HOME_WORD to spec.mode))
    .requests(request(word)).map { it.copy(sourceId = null) }

  private fun fill() {
    if (!active) return
    val excluded = candidates.mapTo(mutableSetOf()) { it.word.id }
    val current = currentWord()?.id
    if (words.size > 1 && current != null) excluded += current
    while (candidates.size < spec.capacity) {
      var selected: WordlistItem? = null
      repeat(24) { if (selected == null) words.randomOrNull()?.takeIf { it.id !in excluded }?.let { selected = it } }
      val word = selected ?: words.firstOrNull { it.id !in excluded } ?: break
      excluded += word.id
      Candidate(word, requests(word)).also { candidate -> candidates += candidate; prepare(candidate) }
    }
  }

  private fun prepare(candidate: Candidate) {
    val version = generation
    candidate.ready = false
    candidate.failed = false
    publishReadyCount()
    val job = scope.launch(start = CoroutineStart.LAZY) {
      val ready = quietlyPrepare(candidate.requests)
      if (version != generation || !active || candidate !in candidates) return@launch
      candidate.ready = ready
      candidate.failed = !ready
      candidate.job = null
      publishReadyCount()
      consumeReady()
    }
    candidate.job = job
    job.start()
  }

  private suspend fun quietlyReady(requests: List<SpeechRequest>): Boolean = try {
    requests.isEmpty() || isReady(requests)
  } catch (error: CancellationException) { throw error }
  catch (_: Exception) { false }
  catch (_: LinkageError) { false }

  private suspend fun quietlyPrepare(requests: List<SpeechRequest>, priority: Int = SpeechCachePriority.UPCOMING_WORD): Boolean = try {
    requests.isEmpty() || prepare(requests, priority)
  } catch (error: CancellationException) {
    throw error
  } catch (_: Exception) {
    false
  } catch (_: LinkageError) {
    false
  }

  private fun consumeReady() {
    if (!active || !pending || selectionJob?.isActive == true) return
    val version = generation
    val job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        for (candidate in candidates.filter { it.ready }) {
          val available = quietlyReady(candidate.requests)
          if (version != generation || !active) return@launch
          if (!available) {
            // A resource may have been evicted, or could not fit in either cache.
            // Retry it once for this click; never spin forever on a successful synthesis without retention.
            if (candidate.readinessRetries++ == 0) prepare(candidate)
            else { candidate.ready = false; candidate.failed = true }
            publishReadyCount()
            continue
          }
          candidates.remove(candidate)
          candidate.job?.cancel()
          publishReadyCount()
          pending = false
          onWaiting(false, false)
          onSelected(candidate.word, request(candidate.word))
          fill()
          return@launch
        }
        if (version == generation && candidates.none { !it.failed }) {
          pending = false
          onWaiting(false, true)
        }
      } finally {
        if (version == generation) {
          selectionJob = null
          if (pending && candidates.any { it.ready }) consumeReady()
        }
      }
    }
    selectionJob = job
    job.start()
  }
}
