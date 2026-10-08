package com.example.chinese_flashcard.feature.study

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.example.chinese_flashcard.core.domain.CardPhase
import kotlinx.coroutines.flow.first

@Composable
fun StudyScreen(vm: StudyViewModel, onBack: () -> Unit, onWriting: (String) -> Unit,
  onSpeakAndWait: suspend (String) -> Boolean) {
  val state by vm.state.collectAsStateWithLifecycle()
  val card = state.card
  var selectedOption by rememberSaveable(card?.id) { mutableStateOf<String?>(null) }
  var answerChosen by rememberSaveable(card?.id) { mutableStateOf(false) }
  var correctAnswerRevealed by rememberSaveable(card?.id) { mutableStateOf(false) }
  var feedbackFinished by rememberSaveable(card?.id) { mutableStateOf(false) }
  var feedbackPlaybackSucceeded by rememberSaveable(card?.id) { mutableStateOf(false) }
  var feedbackAdvanceRequested by rememberSaveable(card?.id) { mutableStateOf(false) }
  var spokenPage by rememberSaveable { mutableStateOf<String?>(null) }
  var manualSpeechRevision by remember { mutableLongStateOf(0L) }
  val latestSpeakAndWait by rememberUpdatedState(onSpeakAndWait)
  val playback = remember { StudyPlayback { latestSpeakAndWait(it) } }
  val speechScope = rememberCoroutineScope()
  var manualSpeechJob by remember { mutableStateOf<Job?>(null) }
  val manualSpeak: (String) -> Unit = { text ->
    manualSpeechRevision++
    manualSpeechJob?.cancel()
    playback.cancel()
    manualSpeechJob = speechScope.launch { playback.sequence { speak(text, immediate = true) } }
  }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(card?.id, card?.phase, lifecycle, playback) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
        manualSpeechJob?.cancel()
        playback.cancel()
      }
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer); manualSpeechJob?.cancel(); playback.cancel() }
  }
  val speechPage = card?.takeIf { it.phase in listOf(CardPhase.INTRO, CardPhase.QUESTION, CardPhase.EXPLANATION) }
    ?.let { "${it.id}/${it.phase}" }
  LaunchedEffect(speechPage, lifecycle) {
    val entryRevision = manualSpeechRevision
    if (speechPage != null) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.state.first { !it.busy && !it.loading }
        val activeCard = vm.state.value.card
        if (spokenPage != speechPage) {
          if (activeCard == null || "${activeCard.id}/${activeCard.phase}" != speechPage) return@repeatOnLifecycle
          spokenPage = speechPage
          if (manualSpeechRevision != entryRevision) return@repeatOnLifecycle
          playback.sequence {
            if (!speak(activeCard.word.hanzi)) return@sequence
            for (example in activeCard.playbackExamples()) {
              val current = vm.state.value.card
              if (manualSpeechRevision != entryRevision || current?.id != activeCard.id ||
                current.phase != activeCard.phase) break
              if (example.hanzi.isNotBlank() && !speak(example.hanzi)) break
            }
          }
        }
        awaitCancellation()
      }
    }
  }
  WritingNavigation(state, vm, onWriting)
  LaunchedEffect(card?.id, card?.phase, state.loading, lifecycle) {
    if (card?.phase == CardPhase.FEEDBACK && !state.loading) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        if (!feedbackFinished) {
          playback.sequence {
            if (!correctAnswerRevealed) {
              if (card.correct == false) {
                withFrameNanos { }; withFrameNanos { }
                card.options.firstOrNull { it.id == card.selectedOptionId }?.hanzi
                  ?.takeIf(String::isNotBlank)?.let { speak(it) }
                currentCoroutineContext().ensureActive()
              }
              correctAnswerRevealed = true
            }
            withFrameNanos { }; withFrameNanos { }
            val active = vm.state.value.card
            if (active == null || active.id != card.id || active.phase != CardPhase.FEEDBACK) return@sequence
            val played = speak(card.word.hanzi)
            currentCoroutineContext().ensureActive()
            val current = vm.state.value.card
            if (current != null && current.id == card.id && current.phase == CardPhase.FEEDBACK) {
              feedbackPlaybackSucceeded = played
              feedbackFinished = true
            }
          }
        }
        awaitCancellation()
      }
    }
  }
  LaunchedEffect(card?.id, card?.writingSessionId, card?.phase, state.busy) {
    val id = card?.writingSessionId
    if (!state.busy && card != null && card.phase == CardPhase.WRITING && id != null &&
      vm.claimCardWriting(card.id, id)) onWriting(id)
  }
  // Audio failure leaves Next available; a saved feedback page never submits the answer again.
  LaunchedEffect(card?.id, card?.phase, state.loading, state.busy, state.error,
    feedbackFinished, feedbackPlaybackSucceeded, feedbackAdvanceRequested, lifecycle) {
    if (card?.phase == CardPhase.FEEDBACK && card.correct == true && feedbackFinished &&
      feedbackPlaybackSucceeded && !feedbackAdvanceRequested && !state.loading && !state.busy && state.error == null) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        val current = vm.state.value
        if (current.card?.id == card.id && current.card?.phase == CardPhase.FEEDBACK && !current.busy && current.error == null) {
          feedbackAdvanceRequested = true
          vm.onAction(StudyAction.Explain(card.id))
        }
        awaitCancellation()
      }
    }
  }
  StudyContent(state, selectedOption, answerChosen, correctAnswerRevealed, feedbackFinished,
    feedbackPlaybackSucceeded, onBack, onWriting, manualSpeak,
    onSubmit = { id, option ->
      selectedOption = option; answerChosen = true
      vm.onAction(StudyAction.Submit(id, option))
    }, onExplain = { vm.onAction(StudyAction.Explain(it)) }, onAdvance = vm::advance,
    onWordWriting = vm::startManualWriting, onCollection = vm::setCollected, onSkip = vm::setSkipped,
    onRetry = vm::retry)
  DailyWritingInvitation(state, vm)
}
