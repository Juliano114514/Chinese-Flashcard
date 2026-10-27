package com.example.chinese_flashcard.feature.wordlist

import com.example.chinese_flashcard.core.domain.WordTag
import com.example.chinese_flashcard.core.domain.WordTagFilter
import com.example.chinese_flashcard.core.domain.UiMessageKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.domain.StudyRepository
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.WordlistItem
import com.example.chinese_flashcard.core.domain.WordlistRepository
import com.example.chinese_flashcard.core.domain.WordlistStatus
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.domain.WordStateRepository
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.WordUserState
import com.example.chinese_flashcard.core.domain.SpeechCacheController
import com.example.chinese_flashcard.core.domain.SpeechCacheDemand
import com.example.chinese_flashcard.core.domain.SpeechCacheOwner
import com.example.chinese_flashcard.core.domain.SpeechCachePriority
import com.example.chinese_flashcard.core.domain.SpeechCacheWindow
import com.example.chinese_flashcard.core.domain.wordSpeechBasics
import com.example.chinese_flashcard.core.domain.wordSpeechExamples
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WordlistUiState(
  val tagCatalog: List<WordTag> = emptyList(),
  val tagFilter: WordTagFilter = WordTagFilter(),
  val allEntries: List<WordlistItem> = emptyList(),
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
  val error: UiMessageKey? = null,
)

class WordlistViewModel(private val repository: WordlistRepository) : ViewModel() {
  private val mutableState = MutableStateFlow(WordlistUiState(tagCatalog = repository.tagCatalog))
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

  fun resetFilters() {
    resetScroll()
    mutableState.update { it.copy(tagFilter = WordTagFilter(), query = "", status = null,
      difficulties = emptySet(), myCollectionOnly = false) }
    updateResults()
  }

  fun setTagFilter(value: WordTagFilter) {
    if (value == state.value.tagFilter) return
    resetScroll()
    mutableState.update { it.copy(tagFilter = value.normalized(it.tagCatalog)) }
    updateResults()
  }

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
          mutableState.update { it.copy(error = null, total = items.size, allEntries = items,
            learned = updated.learned, learning = updated.learning,
            unlearned = updated.unlearned, skipped = updated.skipped) }
          updateResults()
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(loading = false, error = UiMessageKey.YOUR_WORDLIST_COULDN_T_BE_LOADED_TRY_AGAIN) }
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
        val matchesTags = filters.tagFilter.matcher(filters.tagCatalog)
        catalog.filter { entry ->
          matchesTags(entry.item.tags) &&
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
  val error: UiMessageKey? = null,
  val writingSessionId: String? = null,
  val userState: WordUserState = WordUserState(),
  val learningLanguage: AppLanguage = AppLanguage.CN,
  val contextVersion: Long = 0,
)

class WordDetailViewModel(
  val wordId: String,
  private val repository: WordlistRepository,
  private val study: StudyRepository,
  private val wordState: WordStateRepository,
  private val learningLanguage: AppLanguage = AppLanguage.CN,
  private val speechCache: SpeechCacheController,
) : ViewModel() {
  private val mutableState = MutableStateFlow(WordDetailUiState(learningLanguage = learningLanguage))
  val state = mutableState.asStateFlow()
  private var loading: Job? = null
  private var spoken = false
  private var retryAction: () -> Unit = ::load
  private val speechVisible = MutableStateFlow(false)
  private val speechOwner = SpeechCacheOwner("word/detail")

  init { load(); observeSpeechWindow() }

  fun setSpeechActive(active: Boolean) { speechVisible.value = active }

  private fun observeSpeechWindow() {
    viewModelScope.launch {
      combine(state.map { it.word to it.contextVersion }.distinctUntilChanged(), speechVisible) { content, visible ->
        content.takeIf { visible }
      }.distinctUntilChanged().collectLatest { content ->
        if (content == null) { speechCache.release(speechOwner); return@collectLatest }
        val word = content.first
        if (word == null) { speechCache.release(speechOwner); return@collectLatest }
        try {
          val demands = wordSpeechBasics(word).map { SpeechCacheDemand(it, SpeechCachePriority.CURRENT_WORD) } +
            wordSpeechExamples(word).map { SpeechCacheDemand(it, SpeechCachePriority.CURRENT_EXAMPLE) }
          speechCache.updateWindow(speechOwner, SpeechCacheWindow(content.second, demands))
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { speechCache.release(speechOwner) }
        catch (_: LinkageError) { speechCache.release(speechOwner) }
      }
    }
  }

  override fun onCleared() {
    speechCache.release(speechOwner)
    super.onCleared()
  }

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
        val id = withContext(Dispatchers.IO) { study.startManualWriting(word.id, current.learningLanguage, current.contextVersion) }
        mutableState.update { it.copy(busy = false, writingSessionId = id) }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(busy = false, error = UiMessageKey.WRITING_COULDN_T_BE_OPENED_TRY_AGAIN) }
      }
    }
  }

  fun setCollected(value: Boolean) {
    updateLabel({ setCollected(value) }) { wordState.setCollected(wordId, value) }
  }

  fun setSkipped(value: Boolean) {
    updateLabel({ setSkipped(value) }) { wordState.setSkipped(wordId, value, learningLanguage) }
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
        mutableState.update { it.copy(error = UiMessageKey.THIS_CHANGE_COULDN_T_BE_SAVED_TRY_AGAIN) }
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
        val context = withContext(Dispatchers.IO) { study.context() }
        check(context.learningLanguage == learningLanguage)
        val word = withContext(Dispatchers.IO) { repository.word(wordId) }
        val labels = if (word != null) wordState.observe(wordId, learningLanguage).first() else WordUserState()
        mutableState.update { it.copy(loading = false, word = word, userState = labels, contextVersion = context.version,
          error = if (word == null) UiMessageKey.THIS_WORD_IS_NO_LONGER_AVAILABLE else null) }
        if (word != null) wordState.observe(wordId, learningLanguage).collectLatest { labels ->
          mutableState.update { it.copy(userState = labels) }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableState.update { it.copy(loading = false, error = UiMessageKey.THIS_WORD_COULDN_T_BE_LOADED_TRY_AGAIN) }
      }
    }
  }
}
