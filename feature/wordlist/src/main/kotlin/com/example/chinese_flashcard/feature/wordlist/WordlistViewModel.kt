package com.example.chinese_flashcard.feature.wordlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.domain.StudyRepository
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.WordlistItem
import com.example.chinese_flashcard.core.domain.WordlistRepository
import com.example.chinese_flashcard.core.domain.WordlistStatus
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.domain.WordStateRepository
import com.example.chinese_flashcard.core.domain.WordUserState
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
  val myCollectionOnly: Boolean = false,
  val skipped: Int = 0,
  val error: String? = null,
)

class WordlistViewModel(private val repository: WordlistRepository) : ViewModel() {
  private val mutableState = MutableStateFlow(WordlistUiState())
  val state = mutableState.asStateFlow()
  private data class SearchEntry(val item: WordlistItem, val pinyin: String, val meanings: String)
  private data class CatalogUpdate(val entries: List<SearchEntry>, val learned: Int, val learning: Int,
    val unlearned: Int, val skipped: Int)
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

  fun setMyCollection(value: Boolean) {
    if (value == state.value.myCollectionOnly) return
    resetScroll()
    mutableState.update { it.copy(myCollectionOnly = value) }
    updateResults()
  }

  /** An empty set is All. Selecting All clears the numbered choices. */
  fun toggleDifficulty(value: Int?) {
    if (value != null && value !in VocabularyStage.rarityRange) return
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
          // Progress/collection changes reuse the catalog's normalized text.
          val previous = searchEntries
          val updated = withContext(Dispatchers.Default) {
            val cached = previous.associateBy { it.item.id }
            val entries = items.map { item ->
              val entry = cached[item.id]
              if (entry != null && entry.item.pinyin == item.pinyin && entry.item.searchMeanings == item.searchMeanings) {
                if (entry.item == item) entry else entry.copy(item = item)
              } else SearchEntry(item, searchText(item.pinyin).filterNot(Char::isWhitespace), searchText(item.searchMeanings))
            }
            CatalogUpdate(entries, items.count { it.learningStatus == WordlistStatus.LEARNED },
              items.count { it.learningStatus == WordlistStatus.LEARNING },
              items.count { it.learningStatus == WordlistStatus.UNLEARNED }, items.count { it.isSkipped })
          }
          searchEntries = updated.entries
          mutableState.update { it.copy(error = null, total = items.size,
            learned = updated.learned, learning = updated.learning,
            unlearned = updated.unlearned, skipped = updated.skipped) }
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
            (!filters.myCollectionOnly || entry.item.isCollected) &&
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
  val userState: WordUserState = WordUserState(),
)

class WordDetailViewModel(
  val wordId: String,
  private val repository: WordlistRepository,
  private val study: StudyRepository,
  private val wordState: WordStateRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(WordDetailUiState())
  val state = mutableState.asStateFlow()
  private var loading: Job? = null
  private var spoken = false
  private var retryAction: () -> Unit = ::load

  init { load() }

  fun retry() {
    if (state.value.busy) return
    retryAction()
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
    retryAction = ::write
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

  fun setCollected(value: Boolean) {
    updateLabel({ setCollected(value) }) { wordState.setCollected(wordId, value) }
  }

  fun setSkipped(value: Boolean) {
    updateLabel({ setSkipped(value) }) { wordState.setSkipped(wordId, value) }
  }

  private fun updateLabel(retry: () -> Unit, operation: suspend () -> Unit) {
    if (state.value.loading || state.value.busy || state.value.word == null) return
    retryAction = retry
    mutableState.update { it.copy(busy = true, error = null) }
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { operation() }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(error = "This change couldn't be saved. Try again.") }
      } finally {
        mutableState.update { it.copy(busy = false) }
      }
    }
  }

  fun claimWriting(sessionId: String): Boolean {
    if (state.value.writingSessionId != sessionId) return false
    mutableState.update { it.copy(writingSessionId = null) }
    return true
  }

  private fun load() {
    retryAction = ::load
    loading?.cancel()
    mutableState.update { it.copy(loading = true, error = null) }
    loading = viewModelScope.launch {
      try {
        val word = withContext(Dispatchers.IO) { repository.word(wordId) }
        val labels = if (word != null) wordState.observe(wordId).first() else WordUserState()
        mutableState.update { it.copy(loading = false, word = word, userState = labels,
          error = if (word == null) "This word is no longer available." else null) }
        if (word != null) wordState.observe(wordId).collectLatest { labels ->
          mutableState.update { it.copy(userState = labels) }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(loading = false, error = "This word couldn't be loaded. Try again.") }
      }
    }
  }
}
