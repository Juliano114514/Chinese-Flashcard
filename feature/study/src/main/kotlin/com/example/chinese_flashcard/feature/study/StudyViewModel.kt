package com.example.chinese_flashcard.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.domain.NewStudyDayException
import com.example.chinese_flashcard.core.domain.SettingsRepository
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudyRepository
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.StudySnapshot
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.domain.WordStateRepository
import com.example.chinese_flashcard.core.domain.WordlistRepository
import com.example.chinese_flashcard.core.domain.WordlistItem
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.UiMessageKey
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.WordEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class WritingRequest(val token: Long, val sessionId: String)

data class StudyUiState(
  val loading: Boolean = true,
  val busy: Boolean = false,
  val settings: StudySettings = StudySettings(),
  val snapshot: StudySnapshot? = null,
  val error: UiMessageKey? = null,
  val dailyInvitation: Boolean = false,
  val writingRequest: WritingRequest? = null,
  val homeWord: WordlistItem? = null,
  val homeWordError: Boolean = false,
  val homeWordStage: VocabularyStage? = null,
  val homeShuffleWaiting: Boolean = false,
  val homeShuffleReady: Int = 0,
  val homeShuffleFailed: Boolean = false,
  val homeSpeechEvent: HomeSpeechEvent? = null,
  val directionGeneration: Long = 0,
) {
  val today get() = snapshot?.today
  val card get() = snapshot?.card
}

sealed interface StudyAction {
  data object Refresh : StudyAction
  data object Retry : StudyAction
  data class Start(val kind: StudyKind, val language: AppLanguage? = null) : StudyAction
  data class SelectStage(val stage: VocabularyStage, val language: AppLanguage? = null) : StudyAction
  data class LearnMore(val expectedDate: String, val expectedNewPlanned: Int,
    val expectedStage: VocabularyStage, val expectedLap: Int, val language: AppLanguage? = null) : StudyAction
  data class SetCollected(val wordId: String, val value: Boolean) : StudyAction
  data class SetSkipped(val wordId: String, val value: Boolean, val language: AppLanguage? = null) : StudyAction
  data class Submit(val cardId: String, val optionId: String?, val language: AppLanguage? = null,
    val generation: Long? = null) : StudyAction
  data class Advance(val cardId: String, val language: AppLanguage? = null, val generation: Long? = null) : StudyAction
  data class Explain(val cardId: String, val language: AppLanguage? = null, val generation: Long? = null) : StudyAction
  data class ManualWriting(val wordId: String, val language: AppLanguage? = null,
    val generation: Long? = null) : StudyAction
  data object DailyWriting : StudyAction
  data object DismissInvitation : StudyAction
  data class ConsumeWriting(val token: Long) : StudyAction
}

sealed interface StudyMutation {
  data object Busy : StudyMutation
  data object Idle : StudyMutation
  data class Loaded(val snapshot: StudySnapshot, val settings: StudySettings? = null) : StudyMutation
  data class Failed(val message: UiMessageKey) : StudyMutation
  data class Invitation(val visible: Boolean) : StudyMutation
  data class WritingReady(val request: WritingRequest) : StudyMutation
  data class WritingConsumed(val token: Long) : StudyMutation
}

object StudyReducer {
  fun reduce(state: StudyUiState, mutation: StudyMutation): StudyUiState = when (mutation) {
    StudyMutation.Busy -> state.copy(busy = true, error = null)
    StudyMutation.Idle -> state.copy(busy = false)
    is StudyMutation.Loaded -> state.copy(loading = false,
      // Revision is an observation watermark, not displayed content. Keep unchanged UI references.
      snapshot = state.snapshot?.takeIf { it == mutation.snapshot.copy(revision = it.revision) } ?: mutation.snapshot,
      settings = (mutation.settings ?: state.settings).copy(learningLanguage = mutation.snapshot.learningLanguage), error = null,
      directionGeneration = mutation.snapshot.contextVersion,
      writingRequest = state.writingRequest.takeIf { state.snapshot?.contextVersion == mutation.snapshot.contextVersion },
      dailyInvitation = state.dailyInvitation && mutation.snapshot.learningLanguage == AppLanguage.CN &&
        state.snapshot?.learningLanguage == mutation.snapshot.learningLanguage &&
        state.snapshot?.contextVersion == mutation.snapshot.contextVersion &&
        state.snapshot?.today?.date == mutation.snapshot.today.date && mutation.snapshot.today.allComplete)
    is StudyMutation.Failed -> state.copy(loading = false, error = mutation.message)
    is StudyMutation.Invitation -> state.copy(dailyInvitation = mutation.visible)
    is StudyMutation.WritingReady -> state.copy(writingRequest = mutation.request, dailyInvitation = false)
    is StudyMutation.WritingConsumed -> if (state.writingRequest?.token == mutation.token) {
      state.copy(writingRequest = null)
    } else state
  }
}

class StudyViewModel(
  private val study: StudyRepository,
  private val settings: SettingsRepository,
  private val wordState: WordStateRepository,
  private val wordlist: WordlistRepository,
  prefetchSpeech: suspend (List<SpeechRequest>) -> Boolean = { true },
  speechReady: suspend (List<SpeechRequest>) -> Boolean = { true },
  speechChanges: Flow<Long> = emptyFlow(),
) : ViewModel() {
  private val mutableState = MutableStateFlow(StudyUiState())
  val state = mutableState.asStateFlow()
  private val operationLock = Mutex()
  private var observation: Job? = null
  private var failedAction: StudyAction = StudyAction.Refresh
  private var writingToken = 0L
  private var openedCardWriting: String? = null
  private var lastSnapshotRevision = 0L
  private var refreshing = false

  private var homeWords = emptyList<WordlistItem>()
  private var homeWordObservation: Job? = null
  private var homeSpeechToken = 0L
  private var homeSpeechActive = false
  private val homeQueue = HomeSpeechQueue(viewModelScope, prefetchSpeech, speechReady,
    currentWord = { state.value.homeWord },
    onWaiting = { waiting, failed -> mutableState.update { it.copy(homeShuffleWaiting = waiting, homeShuffleFailed = failed) } },
    onSelected = { word, request -> mutableState.update { current ->
      if (homeSpeechActive && !current.busy && !current.loading &&
        current.homeWordStage == current.today?.stageProgress?.stage &&
        word.difficulty == current.today?.stageProgress?.stage?.rarity &&
        request.language == current.settings.learningLanguage) {
        current.copy(homeWord = word, homeWordError = false, homeSpeechEvent = HomeSpeechEvent(++homeSpeechToken, request))
      } else current
    } }, onReadyCount = { count -> mutableState.update { it.copy(homeShuffleReady = count) } })

  init {
    observe(); observeHomeWords()
    viewModelScope.launch { speechChanges.collect { homeQueue.refreshReadiness() } }
  }

  fun shuffleHomeWord() {
    if (state.value.busy || state.value.loading) return
    if (homeWordObservation?.isActive != true) { observeHomeWords(); return }
    if (state.value.homeWordStage != state.value.today?.stageProgress?.stage) return
    homeQueue.shuffle()
  }

  fun setHomeSpeechActive(active: Boolean) {
    homeSpeechActive = active
    syncHomeSpeechActivity()
  }

  private fun syncHomeSpeechActivity() {
    val current = state.value
    val active = homeSpeechActive && !current.busy && !current.loading &&
      current.homeWordStage == current.today?.stageProgress?.stage
    if (!active || current.homeSpeechEvent?.request?.language?.let { it != current.settings.learningLanguage } == true)
      mutableState.update { it.copy(homeSpeechEvent = null) }
    homeQueue.setActive(active)
  }

  fun consumeHomeSpeech(token: Long) { mutableState.update {
    if (it.homeSpeechEvent?.token == token) it.copy(homeSpeechEvent = null) else it
  } }

  suspend fun speechPreloadWords(cardId: String, nextCount: Int, language: AppLanguage,
    contextVersion: Long): List<WordEntry> = withContext(Dispatchers.IO) {
    study.peekSpeechWords(cardId, nextCount, language, contextVersion)
  }

  private fun observeHomeWords() {
    homeWordObservation?.cancel()
    homeWordObservation = viewModelScope.launch {
      try {
        combine(state.map { it.today?.stageProgress?.stage to it.settings }.distinctUntilChanged(), wordlist.entries) {
          (stage, preferences), words -> Triple(stage, preferences,
            if (stage == null) emptyList() else words.filter { it.difficulty == stage.rarity })
        }.distinctUntilChanged().flowOn(Dispatchers.Default).collect { (stage, preferences, words) ->
          if (stage != state.value.today?.stageProgress?.stage) return@collect
          homeWords = words
          mutableState.update { current -> current.copy(
            homeWord = words.firstOrNull { it.id == current.homeWord?.id } ?: words.randomOrNull(),
            homeWordError = false, homeWordStage = stage) }
          homeQueue.configure(words, preferences.learningLanguage, preferences.speechOptions, preferences.homeShuffleQueueSize)
          syncHomeSpeechActivity()
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        homeWords = emptyList()
        homeQueue.configure(emptyList(), state.value.settings.learningLanguage,
          state.value.settings.speechOptions, state.value.settings.homeShuffleQueueSize)
        mutableState.update { it.copy(homeWord = null, homeWordError = true) }
      }
    }
  }

  fun onAction(action: StudyAction) {
    when (action) {
      StudyAction.DismissInvitation -> mutate(StudyMutation.Invitation(false))
      is StudyAction.ConsumeWriting -> mutate(StudyMutation.WritingConsumed(action.token))
      StudyAction.Retry -> {
        if (observation?.isActive != true) observe()
        execute(failedAction)
      }
      else -> execute(action)
    }
  }

  fun start(kind: StudyKind) = onAction(StudyAction.Start(kind, state.value.settings.learningLanguage))
  fun selectStage(stage: VocabularyStage) = onAction(StudyAction.SelectStage(stage, state.value.settings.learningLanguage))
  fun setCollected(wordId: String, value: Boolean) = onAction(StudyAction.SetCollected(wordId, value))
  fun setSkipped(wordId: String, value: Boolean) = setSkipped(wordId, value, state.value.settings.learningLanguage)
  fun setSkipped(wordId: String, value: Boolean, language: AppLanguage) =
    onAction(StudyAction.SetSkipped(wordId, value, language))
  fun learnMore() { state.value.today?.let {
    onAction(StudyAction.LearnMore(it.date, it.newPlanned, it.stageProgress.stage, it.stageProgress.lap, it.learningLanguage))
  } }
  fun refresh() = onAction(StudyAction.Refresh)
  fun retry() = onAction(StudyAction.Retry)
  fun advance() { state.value.card?.let { onAction(StudyAction.Advance(it.id, it.learningLanguage, state.value.directionGeneration)) } }
  fun submit(optionId: String?) { state.value.card?.let { onAction(StudyAction.Submit(it.id, optionId, it.learningLanguage, state.value.directionGeneration)) } }
  fun explain() { state.value.card?.let { onAction(StudyAction.Explain(it.id, it.learningLanguage, state.value.directionGeneration)) } }
  fun startManualWriting(wordId: String) = onAction(StudyAction.ManualWriting(wordId,
    state.value.settings.learningLanguage, state.value.directionGeneration))
  fun startDailyWriting() = onAction(StudyAction.DailyWriting)
  fun consumeWriting(token: Long) = onAction(StudyAction.ConsumeWriting(token))
  fun dismissInvitation() = onAction(StudyAction.DismissInvitation)

  /** The shared VM survives navigation, so returning from tracing cannot open it again. */
  fun claimCardWriting(cardId: String, sessionId: String): Boolean {
    val key = "$cardId/$sessionId"
    if (openedCardWriting == key) return false
    openedCardWriting = key
    return true
  }

  private fun mutate(mutation: StudyMutation) {
    if (mutation is StudyMutation.Loaded) {
      lastSnapshotRevision = mutation.snapshot.revision
      if (state.value.snapshot?.contextVersion != mutation.snapshot.contextVersion) {
        failedAction = StudyAction.Refresh
        openedCardWriting = null
      }
    }
    mutableState.update { StudyReducer.reduce(it, mutation) }
    syncHomeSpeechActivity()
  }

  private fun observe() {
    observation?.cancel()
    observation = viewModelScope.launch {
      try {
        combine(settings.settings, study.changes.onStart { emit(0L) }) { value, revision -> value to revision }
          .collect { (value, revision) ->
            operationLock.withLock {
              val loaded = state.value
              // Actions already return the snapshot saved at this revision. Keep later external changes observable.
              if (loaded.snapshot != null && loaded.snapshot.learningLanguage == value.learningLanguage &&
                revision <= lastSnapshotRevision && value == loaded.settings) {
                return@withLock
              }
              val (snapshot, value) = withContext(Dispatchers.IO) {
                var snapshot = study.snapshot()
                val value = settings.settings.first()
                if (snapshot.learningLanguage != value.learningLanguage) snapshot = study.snapshot()
                snapshot to value
              }
              if (snapshot.learningLanguage != value.learningLanguage) return@withLock
              mutate(StudyMutation.Loaded(snapshot, value))
              offerDailyWriting(snapshot)
            }
          }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        failedAction = StudyAction.Refresh
        mutate(StudyMutation.Failed(UiMessageKey.YOUR_SAVED_PROGRESS_COULDN_T_BE_LOADED_TRY_AGAIN))
      }
    }
  }

  private fun execute(action: StudyAction) {
    // Set busy before launch: two quick taps cannot queue duplicate writes.
    if (state.value.busy) return
    val refresh = action == StudyAction.Refresh
    if (refresh && refreshing) return
    val showBusy = !refresh || state.value.snapshot == null
    val generation = when (action) {
      is StudyAction.Submit -> action.generation
      is StudyAction.Advance -> action.generation
      is StudyAction.Explain -> action.generation
      is StudyAction.ManualWriting -> action.generation
      else -> null
    }
    if (generation != null && generation != state.value.directionGeneration) return
    val expectedLanguage = when (action) {
      is StudyAction.Start -> action.language
      is StudyAction.SelectStage -> action.language
      is StudyAction.LearnMore -> action.language
      is StudyAction.SetSkipped -> action.language
      is StudyAction.Submit -> action.language
      is StudyAction.Advance -> action.language
      is StudyAction.Explain -> action.language
      is StudyAction.ManualWriting -> action.language
      else -> null
    } ?: state.value.settings.learningLanguage
    if (refresh) refreshing = true
    if (showBusy) mutate(StudyMutation.Busy)
    viewModelScope.launch {
      var stored = false
      try {
        operationLock.withLock {
          if (generation != null && generation != state.value.directionGeneration) return@withLock
          val currentSettings = settings.settings.first()
          if (action != StudyAction.Refresh && currentSettings.learningLanguage != expectedLanguage) {
            mutate(StudyMutation.Loaded(withContext(Dispatchers.IO) { study.snapshot() }, currentSettings))
            return@withLock
          }
          val snapshot = withContext(Dispatchers.IO) {
            when (action) {
              is StudyAction.Start -> study.start(action.kind, expectedLanguage)
              is StudyAction.SelectStage -> study.selectStage(action.stage, expectedLanguage)
              is StudyAction.LearnMore -> study.learnMore(action.expectedDate, action.expectedNewPlanned,
                action.expectedStage, action.expectedLap, expectedLanguage)
              is StudyAction.SetCollected -> {
                wordState.setCollected(action.wordId, action.value)
                stored = true
                study.snapshot()
              }
              is StudyAction.SetSkipped -> {
                wordState.setSkipped(action.wordId, action.value, expectedLanguage)
                stored = true
                study.snapshot()
              }
              is StudyAction.Submit -> study.submit(action.cardId, action.optionId, expectedLanguage,
                expectedContextVersion = generation ?: state.value.directionGeneration)
              is StudyAction.Advance -> study.advance(action.cardId, expectedLanguage,
                expectedContextVersion = generation ?: state.value.directionGeneration)
              is StudyAction.Explain -> study.explain(action.cardId, expectedLanguage,
                expectedContextVersion = generation ?: state.value.directionGeneration)
              is StudyAction.ManualWriting -> {
                val id = study.startManualWriting(action.wordId, expectedLanguage,
                  expectedContextVersion = generation ?: state.value.directionGeneration)
                stored = true
                mutate(StudyMutation.WritingReady(WritingRequest(++writingToken, id)))
                study.snapshot()
              }
              StudyAction.DailyWriting -> {
                if (expectedLanguage != AppLanguage.CN) return@withContext study.snapshot()
                val id = study.startDailyWriting(expectedLanguage)
                stored = true
                if (id != null) mutate(StudyMutation.WritingReady(WritingRequest(++writingToken, id)))
                else mutate(StudyMutation.Invitation(false))
                study.snapshot()
              }
              else -> study.snapshot()
            }
          }
          stored = true
          mutate(StudyMutation.Loaded(snapshot))
          offerDailyWriting(snapshot)
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: NewStudyDayException) {
        failedAction = StudyAction.Refresh
        try {
          operationLock.withLock {
            mutate(StudyMutation.Loaded(withContext(Dispatchers.IO) { study.snapshot() }))
          }
        } catch (error: CancellationException) {
          throw error
        } catch (_: Exception) {
          // Preserve the last saved snapshot and keep retry available.
        }
        mutate(StudyMutation.Failed(UiMessageKey.A_NEW_DAY_HAS_STARTED_YOUR_DAILY_PLAN_HAS_BEEN_REFRESHED))
      } catch (_: Exception) {
        failedAction = if (stored) StudyAction.Refresh else action
        mutate(StudyMutation.Failed(UiMessageKey.THIS_STEP_COULDN_T_BE_COMPLETED_TRY_AGAIN))
      } finally {
        if (refresh) refreshing = false
        if (showBusy) mutate(StudyMutation.Idle)
      }
    }
  }

  private suspend fun offerDailyWriting(snapshot: StudySnapshot) {
    if (snapshot.learningLanguage != AppLanguage.CN || !snapshot.today.allComplete || state.value.dailyInvitation) return
    try {
      if (withContext(Dispatchers.IO) { study.claimDailyInvitation(snapshot.learningLanguage) }) {
        mutate(StudyMutation.Invitation(true))
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      failedAction = StudyAction.Refresh
      mutate(StudyMutation.Failed(UiMessageKey.THE_HANDWRITING_INVITATION_COULDN_T_BE_PREPARED_TRY_AGAIN))
    }
  }

}
