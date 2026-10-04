package com.example.chinese_flashcard.feature.wordlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.domain.StudyRepository
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.WordlistItem
import com.example.chinese_flashcard.core.domain.WordlistRepository
import com.example.chinese_flashcard.core.domain.WordlistStatus
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WordlistUiState(
  val loading: Boolean = true,
  val entries: List<WordlistItem> = emptyList(),
  val total: Int = 0,
  val learned: Int = 0,
  val learning: Int = 0,
  val unlearned: Int = 0,
  val query: String = "",
  val status: WordlistStatus? = null,
  val difficulties: Set<Int> = emptySet(),
  val error: String? = null,
)

class WordlistViewModel(private val repository: WordlistRepository) : ViewModel() {
  private val mutableState = MutableStateFlow(WordlistUiState())
  val state = mutableState.asStateFlow()
  private data class SearchEntry(val item: WordlistItem, val pinyin: String, val meanings: String)
  private var searchEntries = emptyList<SearchEntry>()
  private var observation: Job? = null
  private var searchJob: Job? = null
  private var searchGeneration = 0L
  var scrollIndex: Int = 0
    private set
  var scrollOffset: Int = 0
    private set

  init { observe() }

  fun setQuery(value: String) {
    if (value == state.value.query) return
    resetScroll()
    mutableState.update { it.copy(query = value) }
    updateResults()
  }

  fun setStatus(value: WordlistStatus?) {
    if (value == state.value.status) return
    resetScroll()
    mutableState.update { it.copy(status = value) }
    updateResults()
  }

  /** An empty set is All. Selecting All clears the numbered choices. */
  fun toggleDifficulty(value: Int?) {
    if (value != null && value !in 0..3) return
    val current = state.value.difficulties
    val next = if (value == null) emptySet() else if (value in current) current - value else current + value
    if (next == current) return
    resetScroll()
    mutableState.update { it.copy(difficulties = next) }
    updateResults()
  }

  fun rememberScroll(index: Int, offset: Int) {
    scrollIndex = index.coerceAtLeast(0)
    scrollOffset = offset.coerceAtLeast(0)
  }

  fun retry() = observe()

  private fun resetScroll() { scrollIndex = 0; scrollOffset = 0 }

  private fun observe() {
    observation?.cancel()
    mutableState.update { it.copy(loading = searchEntries.isEmpty(), error = null) }
    observation = viewModelScope.launch {
      try {
        repository.entries.collectLatest { items ->
          // Normalize once per catalog update, away from the UI thread.
          searchEntries = withContext(Dispatchers.Default) {
            items.map { SearchEntry(it, searchText(it.pinyin).filterNot(Char::isWhitespace), searchText(it.searchMeanings)) }
          }
          mutableState.update { it.copy(error = null, total = items.size,
            learned = items.count { it.status == WordlistStatus.LEARNED },
            learning = items.count { it.status == WordlistStatus.LEARNING },
            unlearned = items.count { it.status == WordlistStatus.UNLEARNED }) }
          updateResults()
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(loading = false, error = "Your wordlist couldn't be loaded. Try again.") }
      }
    }
  }

  private fun updateResults() {
    searchJob?.cancel()
    val generation = ++searchGeneration
    val filters = state.value
    val catalog = searchEntries
    searchJob = viewModelScope.launch {
      val filtered = withContext(Dispatchers.Default) {
        val needle = searchText(filters.query.trim())
        val pinyinNeedle = needle.filterNot(Char::isWhitespace)
        catalog.filter { entry ->
          (filters.status == null || filters.status == entry.item.status) &&
            (filters.difficulties.isEmpty() || entry.item.difficulty in filters.difficulties) &&
            (needle.isEmpty() || entry.item.hanzi.contains(needle) ||
              entry.pinyin.contains(pinyinNeedle) || entry.meanings.contains(needle))
        }.map(SearchEntry::item)
      }
      // A catalog refresh or newer keystroke cancels this job and owns the result.
      if (generation == searchGeneration) mutableState.update { it.copy(entries = filtered, loading = false) }
    }
  }
}

private fun searchText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
  .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
  .lowercase(Locale.ROOT)

data class WordDetailUiState(
  val loading: Boolean = true,
  val busy: Boolean = false,
  val word: WordEntry? = null,
  val error: String? = null,
  val writingSessionId: String? = null,
)

class WordDetailViewModel(
  val wordId: String,
  private val repository: WordlistRepository,
  private val study: StudyRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(WordDetailUiState())
  val state = mutableState.asStateFlow()
  private var loading: Job? = null
  private var spoken = false

  init { load() }

  fun retry() {
    if (state.value.busy) return
    if (state.value.word == null) load() else write()
  }

  /** The view model survives a writing round trip and only claims playback once. */
  fun claimAutoplay(): Boolean {
    if (spoken || state.value.loading || state.value.word == null) return false
    spoken = true
    return true
  }

  fun write() {
    val current = state.value
    val word = current.word ?: return
    if (current.loading || current.busy || current.writingSessionId != null) return
    // Set this before launching: two taps in the same frame cannot create two sessions.
    mutableState.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      try {
        val id = withContext(Dispatchers.IO) { study.startManualWriting(word.id) }
        mutableState.update { it.copy(busy = false, writingSessionId = id) }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(busy = false, error = "Writing couldn't be opened. Try again.") }
      }
    }
  }

  fun claimWriting(sessionId: String): Boolean {
    if (state.value.writingSessionId != sessionId) return false
    mutableState.update { it.copy(writingSessionId = null) }
    return true
  }

  private fun load() {
    loading?.cancel()
    mutableState.update { it.copy(loading = true, error = null) }
    loading = viewModelScope.launch {
      try {
        val word = withContext(Dispatchers.IO) { repository.word(wordId) }
        mutableState.update { it.copy(loading = false, word = word,
          error = if (word == null) "This word is no longer available." else null) }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(loading = false, error = "This word couldn't be loaded. Try again.") }
      }
    }
  }
}
