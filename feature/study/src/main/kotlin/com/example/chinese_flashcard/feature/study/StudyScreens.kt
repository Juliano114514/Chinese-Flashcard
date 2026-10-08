package com.example.chinese_flashcard.feature.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import android.content.res.Configuration
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
import com.example.chinese_flashcard.core.domain.AnswerOption
import com.example.chinese_flashcard.core.domain.DailyWordChoices
import com.example.chinese_flashcard.core.domain.ExampleChunk
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.Meaning
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.StudySnapshot
import com.example.chinese_flashcard.core.domain.TodaySummary
import com.example.chinese_flashcard.core.domain.PracticeProgress
import com.example.chinese_flashcard.core.domain.StageProgress
import com.example.chinese_flashcard.core.domain.VocabularyStage
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.ui.AvatarPickerDialog
import com.example.chinese_flashcard.core.ui.EditableAvatar
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.StageThemePreviewCase
import com.example.chinese_flashcard.core.ui.StageThemePreviewProvider
import com.example.chinese_flashcard.core.ui.WordHeading
import com.example.chinese_flashcard.core.ui.WordMeanings
import com.example.chinese_flashcard.core.ui.WordExplanation
import com.example.chinese_flashcard.core.ui.WordExamplePanel
import com.example.chinese_flashcard.core.ui.WordUserActions
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@Composable
fun WelcomeScreen(
  settings: StudySettings,
  saving: Boolean,
  error: String?,
  onSave: (StudySettings) -> Unit,
) {
  var step by rememberSaveable { mutableIntStateOf(0) }
  var name by rememberSaveable { mutableStateOf(settings.displayName) }
  var avatarId by rememberSaveable { mutableStateOf(settings.avatarId) }
  var choosingAvatar by rememberSaveable { mutableStateOf(false) }
  var dailyWords by rememberSaveable { mutableIntStateOf(DailyWordChoices.normalize(settings.dailyWords)) }
  var reviewDays by rememberSaveable { mutableStateOf(settings.reviewDays) }
  val focus = LocalFocusManager.current
  val validName = name.isNotBlank()
  val canSave = validName && DailyWordChoices.isAllowed(dailyWords) && reviewDays.isNotEmpty() && !saving
  val save = {
    if (canSave) {
      focus.clearFocus()
      onSave(settings.copy(displayName = name.trim(), avatarId = avatarId,
        dailyWords = dailyWords, reviewDays = reviewDays, welcomed = true))
    }
  }
  BackHandler(enabled = step > 0 || saving) {
    if (!saving) { focus.clearFocus(); step = (step - 1).coerceAtLeast(0) }
  }
  Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp, vertical = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()
        .verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        when (step) {
          0 -> WelcomeNameFields(name = name, avatarId = avatarId, enabled = !saving,
            onNameChange = { value ->
              if (value.length <= 40 && value.none { it.isISOControl() }) name = value
            }, onAvatar = { focus.clearFocus(); choosingAvatar = true },
            onNext = { if (!saving && validName) { focus.clearFocus(); step = 1 } })
          1 -> {
            Text("Words per day", style = MaterialTheme.typography.headlineMedium,
              textAlign = TextAlign.Center)
            Text(dailyWords.toString(), fontSize = 52.sp, lineHeight = 60.sp,
              fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Slider(value = dailyWords.toFloat(), onValueChange = {
                dailyWords = DailyWordChoices.normalize(it.roundToInt())
              }, valueRange = DailyWordChoices.MIN.toFloat()..DailyWordChoices.MAX.toFloat(),
                steps = (DailyWordChoices.MAX - DailyWordChoices.MIN) / DailyWordChoices.STEP - 1, enabled = !saving,
                colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(DailyWordChoices.MIN.toString(), style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(DailyWordChoices.MAX.toString(), style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
          2 -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("When should you review?", style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center)
              Text("Days after passing a new word", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Column(Modifier.fillMaxWidth()) {
              listOf(1, 3, 5, 7, 14, 30).forEach { day ->
                val checked = day in reviewDays
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                  .toggleable(value = checked, enabled = !saving, role = Role.Checkbox,
                    onValueChange = { selected ->
                      reviewDays = if (selected) (reviewDays + day).sorted() else reviewDays - day
                    }).padding(horizontal = 12.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                  Box(Modifier.size(22.dp).background(
                    if (checked) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.extraSmall)
                    .border(1.dp, if (checked) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
                    if (checked) Icon(Icons.Default.Check, contentDescription = null,
                      tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                  }
                  Text("$day ${if (day == 1) "day" else "days"} later",
                    style = MaterialTheme.typography.bodyLarge)
                }
              }
            }
            if (reviewDays.isEmpty()) Text("Select at least one day", color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
      error?.let { ErrorNotice(it, canSave, save) }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics {
        contentDescription = "Setup page ${step + 1} of 3"
      }) {
        repeat(3) { index ->
          Spacer(Modifier.size(6.dp).background(if (step == index) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
      }
      Button(onClick = { if (step < 2) { focus.clearFocus(); step++ } else save() },
        enabled = !saving && if (step == 0) validName else if (step == 2) canSave else true,
        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (saving) "Saving…" else if (step < 2) "Next" else "Let's start")
      }
    }
  }
  if (choosingAvatar) AvatarPickerDialog(selectedId = avatarId,
    onDismiss = { choosingAvatar = false }, onConfirm = { avatarId = it; choosingAvatar = false })
}

@Composable
private fun WelcomeNameFields(
  name: String,
  avatarId: String,
  enabled: Boolean,
  onNameChange: (String) -> Unit,
  onAvatar: () -> Unit,
  onNext: () -> Unit,
) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(24.dp)) {
    EditableAvatar(avatarId, size = 48.dp, enabled = enabled, onClick = onAvatar)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Your name", style = MaterialTheme.typography.titleLarge)
      OutlinedTextField(value = name, onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() }))
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
  vm: StudyViewModel,
  onStart: (StudyKind) -> Unit,
  onResume: () -> Unit,
  onWriting: (String) -> Unit,
  onLearnMore: () -> Unit,
) {
  val state by vm.state.collectAsStateWithLifecycle()
  var showLearnMoreBlocker by rememberSaveable { mutableStateOf(false) }
  WritingNavigation(state, vm, onWriting)
  LaunchedEffect(state.today?.learnMoreBlocker) {
    if (state.today?.learnMoreBlocker == null) showLearnMoreBlocker = false
  }
  TodayContent(state, onStart, onResume, onWriting, onLearnMore,
    onLearnMoreBlocked = { showLearnMoreBlocker = true }, onStage = vm::selectStage,
    onDailyWriting = vm::startDailyWriting, onRetry = vm::retry)
  state.today?.learnMoreBlocker?.takeIf { showLearnMoreBlocker }?.let { blocker ->
    AlertDialog(onDismissRequest = { if (!state.busy) showLearnMoreBlocker = false },
      shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surface,
      tonalElevation = 0.dp, title = { Text("Finish today's plan") }, text = {
        Text(when (blocker) {
          StudyKind.REVIEW -> "Review the due words before learning more."
          StudyKind.CARRYOVER -> "Finish the unfinished words before learning more."
          else -> "Finish the current word before learning more."
        })
      }, confirmButton = {
        TextButton(onClick = { showLearnMoreBlocker = false; onStart(blocker) }, enabled = !state.busy) {
          Text(when (blocker) {
            StudyKind.REVIEW -> "Go to review"
            StudyKind.CARRYOVER -> "Go to continue"
            else -> "Continue learning"
          })
        }
      }, dismissButton = {
        TextButton(onClick = { showLearnMoreBlocker = false }, enabled = !state.busy) { Text("Back") }
      })
  }
  if (!showLearnMoreBlocker) DailyWritingInvitation(state, vm)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayContent(
  state: StudyUiState,
  onStart: (StudyKind) -> Unit,
  onResume: () -> Unit,
  onWriting: (String) -> Unit,
  onLearnMore: () -> Unit,
  onLearnMoreBlocked: () -> Unit,
  onStage: (VocabularyStage) -> Unit,
  onDailyWriting: () -> Unit,
  onRetry: () -> Unit,
) {
  PageColumn {
    HomeStageHeading(state.today?.stageProgress?.stage ?: VocabularyStage.PRIMARY,
      enabled = !state.busy && !state.loading, onStage = onStage)
    when {
      state.loading -> LoadingNotice()
      state.today != null -> {
        val today = state.today!!
        Text(today.date, style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("${today.completed} / ${today.planned}", fontSize = 44.sp, lineHeight = 52.sp,
            fontWeight = FontWeight.SemiBold)
          Text("Words today", color = MaterialTheme.colorScheme.onSurfaceVariant)
          LinearProgressIndicator(progress = {
            if (today.planned == 0) 0f else (today.completed.toFloat() / today.planned).coerceIn(0f, 1f)
          }, modifier = Modifier.fillMaxWidth())
          Text("Learn goal · ${today.dailyGoal} words",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VocabularyStageProgress(today.stageProgress)
        state.card?.takeIf { it.phase != CardPhase.FINISHED }?.let { card ->
          ActionTile("Resume", "${card.word.hanzi}  ${card.word.pinyin}", "${card.round} / ${card.targetRounds}",
            !state.busy, onResume)
        }
        SectionLabel("Study")
        StudyGridRow {
          StudyGridTile(if (today.showLearnMore) "Learn more" else "Learn",
            if (today.showLearnMore && today.availableNewWords == 0) "No words available"
            else if (today.showLearnMore) "Next ${minOf(5, today.availableNewWords)} words"
            else if (today.stageProgress.lap > 1) "Stage words" else "New words", "${today.newCompleted} / ${today.newPlanned}",
            !state.busy && (!today.showLearnMore || today.learnMoreBlocker != null || today.availableNewWords > 0), it) {
            if (!today.showLearnMore) onStart(StudyKind.NEW)
            else if (today.learnMoreBlocker != null) onLearnMoreBlocked()
            else if (today.availableNewWords > 0) onLearnMore()
          }
          StudyGridTile("Review", "Due words", "${today.reviewCompleted} / ${today.reviewPlanned}",
            !state.busy && today.reviewCompleted < today.reviewPlanned, it) { onStart(StudyKind.REVIEW) }
        }
        if (today.carryoverPlanned > 0) ActionTile("Continue", "Unfinished words",
          "${today.carryoverCompleted} / ${today.carryoverPlanned}",
          !state.busy && today.carryoverCompleted < today.carryoverPlanned) { onStart(StudyKind.CARRYOVER) }
        StudyGridRow {
          StudyGridTile("Review my collections", "Next ${minOf(5, today.collectionsAvailable)} words",
            "${today.collectionsAvailable} available", !state.busy && today.collectionsAvailable > 0, it) {
            onStart(StudyKind.COLLECTION)
          }
          StudyGridTile("Review mistakes", "Next ${minOf(5, today.mistakesAvailable)} words",
            "${today.mistakesAvailable} to review", !state.busy && today.mistakesAvailable > 0, it) {
            onStart(StudyKind.MISTAKES)
          }
        }
        today.resumableWritingId?.let { id ->
          OutlinedButton(onClick = { onWriting(id) }, enabled = !state.busy, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            Text("Resume writing")
          }
        }
        if (today.allComplete) {
          Text("Plan complete", style = MaterialTheme.typography.titleMedium)
          if (today.todayNewWords.isNotEmpty()) TextButton(onClick = onDailyWriting, enabled = !state.busy) {
            Text("Write today's words")
          }
        }
        if (today.planned == 0) Text("No words due today.",
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Wordbook")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
          CountLabel(today.totalWords, "Total")
          CountLabel(today.learnedWords, "Learned")
          CountLabel(today.remainingWords, "Not started")
        }
      }
    }
    state.error?.let { ErrorNotice(it, !state.busy, onRetry) }
  }
}

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

@Composable
private fun StudyContent(
  state: StudyUiState,
  selectedOption: String?,
  answerChosen: Boolean,
  correctAnswerRevealed: Boolean,
  feedbackFinished: Boolean,
  feedbackPlaybackSucceeded: Boolean,
  onBack: () -> Unit,
  onWriting: (String) -> Unit,
  onSpeak: (String) -> Unit,
  onSubmit: (String, String?) -> Unit,
  onExplain: (String) -> Unit,
  onAdvance: () -> Unit,
  onWordWriting: (String) -> Unit,
  onCollection: (String, Boolean) -> Unit,
  onSkip: (String, Boolean) -> Unit,
  onRetry: () -> Unit,
) {
  val card = state.card
  val practice = state.snapshot?.practice?.takeIf { it.kind == card?.kind }
  val completed = practice?.completed ?: state.today?.completed ?: 0
  val planned = practice?.planned ?: state.today?.planned ?: 0
  val largeText = LocalDensity.current.fontScale >= 1.25f
  Box(Modifier.fillMaxSize().flashcardBackground()) {
  Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
    Column {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack, enabled = !state.busy) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
      }
      Text(when (card?.kind) {
        StudyKind.NEW -> "Learn"
        StudyKind.REVIEW -> "Review"
        StudyKind.CARRYOVER -> "Continue"
        StudyKind.COLLECTION -> "Collections"
        StudyKind.MISTAKES -> "Mistakes"
        null -> "Study"
      }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      if (card != null && card.phase != CardPhase.FINISHED && !largeText) {
        if (state.snapshot != null) {
          Text("Progress $completed / $planned", modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
        }
      }
    }
    if (card != null && card.phase != CardPhase.FINISHED && state.snapshot != null && largeText) {
      Text("Progress $completed / $planned", modifier = Modifier.align(Alignment.End).padding(horizontal = 20.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
    }
    if (card != null && card.phase != CardPhase.FINISHED && state.snapshot != null) {
      DailyProgress(completed, planned, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
    }
    }
  }, bottomBar = {
    if (card != null && card.phase != CardPhase.FINISHED) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        when (card.phase) {
          CardPhase.QUESTION -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedButton(onClick = {
                onSubmit(card.id, null)
              }, enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
                shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp,
                  if (answerChosen && selectedOption == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { selected = answerChosen && selectedOption == null }) { Text("I don't know") }
              Button(onClick = { onSubmit(card.id, selectedOption) }, enabled = answerChosen && !state.busy,
                shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Next") }
            }
          }
          CardPhase.FEEDBACK -> {
            if (card.correct == true && feedbackFinished && !feedbackPlaybackSucceeded) {
              Text("Audio unavailable. Tap Next.", style = MaterialTheme.typography.bodySmall,
                color = FlashcardStyle.colors.gradientSecondaryInk)
            }
            Button(onClick = { onExplain(card.id) }, enabled = !state.busy && feedbackFinished,
              shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Next") }
          }
          CardPhase.INTRO, CardPhase.EXPLANATION -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedButton(onClick = { onWordWriting(card.word.id) }, enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
                border = BorderStroke(1.dp, FlashcardStyle.colors.gradientAction),
                shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Write") }
              Button(onClick = onAdvance, enabled = !state.busy && card.phase != CardPhase.FEEDBACK, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text(if (card.phase == CardPhase.INTRO) "Continue" else "Next word")
              }
            }
          }
          else -> Unit
        }
      }
    }
  }) { padding ->
    PageColumn(scrollKey = "${card?.id}/${card?.phase}", modifier = Modifier.padding(padding), studyPage = true) {
      when {
        state.loading || (state.busy && card == null) -> LoadingNotice(studyPage = true)
        card == null -> {
          Text("No active session", style = MaterialTheme.typography.headlineMedium)
          Text("Choose Learn or Review from Home.", color = FlashcardStyle.colors.gradientSecondaryInk)
          Button(onClick = onBack, enabled = !state.busy, shape = RoundedCornerShape(8.dp)) { Text("Back to Home") }
        }
        card.phase == CardPhase.FINISHED -> {
          Spacer(Modifier.height(32.dp))
          val unfinishedDaily = state.today?.let { today -> when (card.kind) {
            StudyKind.NEW -> today.newCompleted < today.newPlanned
            StudyKind.REVIEW -> today.reviewCompleted < today.reviewPlanned
            StudyKind.CARRYOVER -> today.carryoverCompleted < today.carryoverPlanned
            else -> false
          } } == true
          val paused = practice?.paused == true || (practice == null && (card.isSkipped || unfinishedDaily))
          Text(if (paused) "Session paused" else "Session complete", style = MaterialTheme.typography.headlineLarge)
          if (paused) Text("Open this session from Home to continue. Skipped words stay paused until you unskip them.",
            color = FlashcardStyle.colors.gradientSecondaryInk)
          if (practice != null) {
            CompletionCount(if (practice.kind == StudyKind.COLLECTION) "Collections" else "Mistakes", completed, planned)
          } else state.today?.let { today ->
            Text("${today.completed} / ${today.planned} words today")
            CompletionCount("Learn", today.newCompleted, today.newPlanned)
            CompletionCount("Reviews", today.reviewCompleted, today.reviewPlanned)
            if (today.carryoverPlanned > 0) CompletionCount("Continued", today.carryoverCompleted, today.carryoverPlanned)
            today.nextReviewDate?.let { date ->
              Text("Next review: $date", style = MaterialTheme.typography.titleMedium,
                color = FlashcardStyle.colors.gradientAction)
            }
          }
          Button(onClick = onBack, enabled = !state.busy, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Done") }
        }
        else -> {
          StudyWordHeading(card.word, enabled = !state.busy && card.phase != CardPhase.FEEDBACK,
            onSpeak = { onSpeak(card.word.hanzi) },
            onWrite = if (card.phase in listOf(CardPhase.INTRO, CardPhase.EXPLANATION))
              ({ onWordWriting(card.word.id) }) else null, card = card,
            onCollection = { onCollection(card.word.id, it) }, onSkip = { onSkip(card.word.id, it) })
          when (card.phase) {
            CardPhase.INTRO, CardPhase.EXPLANATION -> {
              WordMeanings(card.word)
              if (card.correct != null) {
                AnswerFeedback(card.correct == true,
                  card.options.firstOrNull { it.id == card.selectedOptionId }?.english ?: "I don't know")
              }
              WordExplanation(card.word, onSpeak, compact = true)
            }
            CardPhase.QUESTION, CardPhase.FEEDBACK -> {
              val feedback = card.phase == CardPhase.FEEDBACK
              card.contextExample()?.let { example ->
                WordExamplePanel(example, onSpeak, enabled = !feedback && !state.busy, compact = true)
              }
              Text("Choose the meaning", style = MaterialTheme.typography.bodyMedium,
                color = FlashcardStyle.colors.gradientSecondaryInk)
              card.options.forEachIndexed { index, option ->
                val selected = if (feedback) card.selectedOptionId == option.id else answerChosen && selectedOption == option.id
                val correct = card.word.meanings.any { it.id == option.id }
                val revealed = feedback && (selected || (card.correct == false && correct && correctAnswerRevealed))
                MeaningOption(index, option.english, selected, enabled = !state.busy && !feedback,
                  onClick = {
                    onSubmit(card.id, option.id)
                  },
                  result = when {
                    feedback && selected && !correct -> false
                    feedback && correct && revealed -> true
                    else -> null
                  }, hanzi = if (revealed) option.hanzi else "",
                  pinyin = if (revealed) option.pinyin else "",
                  dimmed = feedback && !revealed)
              }
              if (feedback) AnswerFeedback(card.correct == true,
                card.options.firstOrNull { it.id == card.selectedOptionId }?.english ?: "I don't know")
            }
            CardPhase.WRITING -> {
              Text("Writing", style = MaterialTheme.typography.titleLarge)
              card.writingSessionId?.let { id ->
                Button(onClick = { onWriting(id) }, enabled = !state.busy, shape = RoundedCornerShape(8.dp)) { Text("Continue writing") }
              }
            }
            CardPhase.FINISHED -> Unit
          }
        }
      }
      state.error?.let { ErrorNotice(it, !state.busy, onRetry, studyPage = true) }
    }
  }
  }
}

@Composable
private fun AnswerFeedback(correct: Boolean, selectedMeaning: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(if (correct) "Correct" else "Incorrect · Round resets to 1",
      style = MaterialTheme.typography.bodySmall,
      color = if (correct) FlashcardStyle.colors.gradientSuccess else FlashcardStyle.colors.gradientError)
    if (!correct) {
      Text("Your answer: $selectedMeaning", style = MaterialTheme.typography.bodySmall,
        color = FlashcardStyle.colors.gradientSecondaryInk)
    }
  }
}

@Composable
private fun MeaningOption(index: Int, meaning: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
  result: Boolean? = null, hanzi: String = "", pinyin: String = "", dimmed: Boolean = false) {
  val colors = FlashcardStyle.colors
  val contentColor = when (result) {
    true -> colors.onSuccessContainer
    false -> MaterialTheme.colorScheme.onErrorContainer
    null -> if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
  }
  Surface(modifier = Modifier.fillMaxWidth().alpha(if (dimmed) .45f else 1f).semantics { this.selected = selected },
    shape = RoundedCornerShape(8.dp),
    color = when (result) {
      true -> colors.successContainer
      false -> MaterialTheme.colorScheme.errorContainer
      null -> if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface.copy(alpha = FlashcardStyle.opacity.choicePanel)
    }, contentColor = contentColor,
    border = BorderStroke(1.dp, when (result) {
      true -> colors.success
      false -> MaterialTheme.colorScheme.error
      null -> if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    }),
    onClick = onClick, enabled = enabled) {
    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(('A' + index).toString(), style = MaterialTheme.typography.labelLarge,
        color = if (result != null || selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hanzi.isNotBlank()) Text(buildAnnotatedString {
          withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)) { append(hanzi) }
          if (pinyin.isNotBlank()) withStyle(SpanStyle(fontSize = 13.sp)) { append("  $pinyin") }
        }, style = MaterialTheme.typography.titleMedium)
        Text(meaning, style = if (hanzi.isBlank()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
      }
      if (result == true) Icon(Icons.Default.Check, contentDescription = "Correct answer", modifier = Modifier.size(20.dp))
    }
  }
}

@Composable
private fun WritingNavigation(state: StudyUiState, vm: StudyViewModel, onWriting: (String) -> Unit) {
  val request = state.writingRequest
  LaunchedEffect(request?.token) {
    if (request != null) {
      vm.consumeWriting(request.token)
      onWriting(request.sessionId)
    }
  }
}

@Composable
private fun DailyWritingInvitation(state: StudyUiState, vm: StudyViewModel) {
  if (state.dailyInvitation) AlertDialog(onDismissRequest = vm::dismissInvitation,
    title = { Text("Write today's words?") },
    shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
    text = { Text("${state.today?.todayNewWords?.size ?: 0} new words") },
    confirmButton = { TextButton(onClick = vm::startDailyWriting, enabled = !state.busy) { Text("Write now") } },
    dismissButton = { TextButton(onClick = vm::dismissInvitation, enabled = !state.busy) { Text("Not now") } })
}

@Composable
private fun DailyProgress(completed: Int, planned: Int, modifier: Modifier = Modifier) {
  LinearProgressIndicator(progress = {
    if (planned <= 0) 0f else (completed.toFloat() / planned).coerceIn(0f, 1f)
  }, modifier = modifier.fillMaxWidth().height(4.dp))
}

@Composable
private fun RoundProgress(card: StudyCard) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics {
    contentDescription = "Round ${card.round} of ${card.targetRounds}"
  }) {
    repeat(card.targetRounds) { index ->
      Spacer(Modifier.size(width = 10.dp, height = 3.dp).background(
        if (index < card.round) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
    }
  }
}

@Composable
private fun StudyWordHeading(word: WordEntry, enabled: Boolean, onSpeak: () -> Unit, onWrite: (() -> Unit)?,
  card: StudyCard? = null, onCollection: (Boolean) -> Unit = {}, onSkip: (Boolean) -> Unit = {}) {
  var showActions by remember(word.id) { mutableStateOf(false) }
  val showRound = card != null && !card.reviewRecall &&
    card.phase in listOf(CardPhase.INTRO, CardPhase.QUESTION, CardPhase.FEEDBACK)
  val fontScale = LocalDensity.current.fontScale
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val actionsWidth = 36.dp + maxOf(36.dp, (40 * fontScale).dp) + (if (showRound) 14.dp else 0.dp)
    val actionsBelow = (word.hanzi.length * 40 * fontScale).dp + actionsWidth + 2.dp > maxWidth
    WordHeading(word, enabled, onSpeak, onLongClick = { showActions = true }, trailingOnNewLine = actionsBelow,
      trailingSpacing = 2.dp, trailing = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showRound) RoundProgress(checkNotNull(card))
        WordUserActions(isCollected = card?.isCollected ?: false, isSkipped = card?.isSkipped ?: false,
          enabled = enabled, onCollection = onCollection, onSkip = onSkip, wordId = word.id, wordLabel = word.hanzi,
          compact = true)
      }
    })
  }
  if (showActions) AlertDialog(onDismissRequest = { showActions = false }, shape = MaterialTheme.shapes.large,
    containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
    title = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(word.hanzi, style = MaterialTheme.typography.headlineSmall)
        Text(word.pinyin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }, text = {
      Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button) {
          showActions = false; onSpeak()
        }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
          Text("Listen", style = MaterialTheme.typography.bodyLarge)
        }
        onWrite?.let { write ->
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.Button) {
            showActions = false; write()
          }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("Write", style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    }, confirmButton = { TextButton(onClick = { showActions = false }) { Text("Close") } })
}

@Composable
private fun PageColumn(modifier: Modifier = Modifier, scrollKey: Any? = null, studyPage: Boolean = false,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  val scroll = rememberScrollState()
  LaunchedEffect(scrollKey) { scroll.scrollTo(0) }
  Column(modifier.fillMaxSize().then(if (studyPage) Modifier else Modifier.flashcardBackground())
    .verticalScroll(scroll).padding(horizontal = 20.dp, vertical = if (studyPage) 12.dp else 20.dp),
    verticalArrangement = Arrangement.spacedBy(if (studyPage) 8.dp else 16.dp), content = content)
}

@Composable
private fun SectionLabel(value: String) {
  Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CountLabel(count: Int, label: String) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun CompletionCount(label: String, completed: Int, planned: Int) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
      color = FlashcardStyle.colors.gradientSecondaryInk)
    Text("$completed / $planned", style = MaterialTheme.typography.titleMedium)
  }
}

@Composable
private fun HomeStageHeading(stage: VocabularyStage, enabled: Boolean, onStage: (VocabularyStage) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val fontScale = LocalDensity.current.fontScale
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 300.dp || fontScale >= 1.25f
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button) {
      expanded = true
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Home · stage ${stage.rarity}", modifier = Modifier.weight(1f),
        style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge)
      Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Choose learning stage", modifier = Modifier.size(24.dp))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      VocabularyStage.entries.forEach { entry ->
        DropdownMenuItem(text = {
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Stage ${entry.rarity}", style = MaterialTheme.typography.titleMedium)
            Text(entry.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }, leadingIcon = if (entry == stage) {
          { Icon(Icons.Default.Check, contentDescription = "Selected stage", modifier = Modifier.size(20.dp)) }
        } else null, onClick = { expanded = false; onStage(entry) }, enabled = enabled)
      }
    }
  }
}

@Composable
private fun StudyGridRow(content: @Composable (Modifier) -> Unit) {
  val fontScale = LocalDensity.current.fontScale
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth < 300.dp || fontScale >= 1.25f) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content(Modifier.fillMaxWidth())
      }
    } else {
      Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        content(Modifier.weight(1f).fillMaxHeight())
      }
    }
  }
}

@Composable
private fun StudyGridTile(title: String, subtitle: String, progress: String, enabled: Boolean,
  modifier: Modifier, onClick: () -> Unit) {
  Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.heightIn(min = 132.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.weight(1f))
      Text(progress, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun ActionTile(title: String, subtitle: String, progress: String, enabled: Boolean, onClick: () -> Unit) {
  Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
          color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(progress, style = MaterialTheme.typography.titleMedium)
      }
      Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun VocabularyStageProgress(progress: StageProgress) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("stage ${progress.stage.rarity} ${progress.stage.label}", style = MaterialTheme.typography.titleSmall)
    LinearProgressIndicator(progress = {
      if (progress.total <= 0) 0f else (progress.learned.toFloat() / progress.total).coerceIn(0f, 1f)
    }, modifier = Modifier.fillMaxWidth())
    Text("${progress.learned} / ${progress.total} learned · lap ${progress.lap}", style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun LoadingNotice(studyPage: Boolean = false) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    CircularProgressIndicator(Modifier.size(28.dp))
    Text("Loading…", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
      color = if (studyPage) FlashcardStyle.colors.gradientSecondaryInk else MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun ErrorNotice(message: String, enabled: Boolean, onRetry: () -> Unit, studyPage: Boolean = false) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(message, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
      color = if (studyPage) FlashcardStyle.colors.gradientError else MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = onRetry, enabled = enabled, colors = ButtonDefaults.textButtonColors(
      contentColor = if (studyPage) FlashcardStyle.colors.gradientAction else MaterialTheme.colorScheme.primary)) { Text("Try again") }
  }
}

private val PreviewWord = WordEntry(
  id = "preview-thank-you", hanzi = "谢谢", pinyin = "xièxie",
  meanings = listOf(Meaning("thank-you", "thank you", "expression")),
  examples = listOf(ExampleSentence("谢谢你的帮助。", "Xièxie nǐ de bāngzhù.", "Thank you for your help.",
    listOf(ExampleChunk("谢谢", "xièxie", "thank"), ExampleChunk("你", "nǐ", "you"),
      ExampleChunk("的", "de", "DE"), ExampleChunk("帮助。", "bāngzhù.", "help")))),
  parts = emptyList(), distractorMeaningIds = emptyList(), literalExplanations = listOf("Express thanks."),
)

private val PreviewCard = StudyCard("preview", PreviewWord, StudyKind.NEW, CardPhase.QUESTION, 2, 4, false,
  listOf(AnswerOption("thank-you", "thank you", "谢谢", "xièxie"),
    AnswerOption("morning", "good morning", "早上好", "zǎoshang hǎo"),
    AnswerOption("welcome", "you're welcome", "不客气", "bú kèqi"),
    AnswerOption("tomorrow", "see you tomorrow", "明天见", "míngtiān jiàn")))

private val PreviewToday = TodaySummary(date = "2026-10-06", dailyGoal = 10, totalWords = 7723,
  remainingWords = 7483, learnedWords = 240, newPlanned = 10, newCompleted = 6,
  reviewPlanned = 3, reviewCompleted = 1, carryoverPlanned = 2, carryoverCompleted = 0,
  availableNewWords = 50, collectionsAvailable = 12, mistakesAvailable = 3,
  stageProgress = StageProgress(VocabularyStage.PRIMARY, learned = 240, total = 1545))

@Preview(name = "Home · stage themes", widthDp = 360, heightDp = 760)
@Preview(name = "Home · compact", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable
private fun HomePreview(@PreviewParameter(StageThemePreviewProvider::class) theme: StageThemePreviewCase) {
  FlashcardTheme(darkTheme = theme.darkTheme, stage = theme.stage) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
      contentColor = MaterialTheme.colorScheme.onBackground) {
      val today = PreviewToday.copy(stageProgress = PreviewToday.stageProgress.copy(stage = theme.stage))
      TodayContent(StudyUiState(loading = false, snapshot = StudySnapshot(today, null)),
        onStart = {}, onResume = {}, onWriting = {}, onLearnMore = {}, onLearnMoreBlocked = {},
        onStage = {}, onDailyWriting = {}, onRetry = {})
    }
  }
}

@Composable
private fun StudyPagePreview(theme: StageThemePreviewCase, card: StudyCard, selectedOption: String? = null,
  revealCorrect: Boolean = false, feedbackFinished: Boolean = false) {
  FlashcardTheme(darkTheme = theme.darkTheme, stage = theme.stage) {
    val practice = if (card.kind in listOf(StudyKind.COLLECTION, StudyKind.MISTAKES)) PracticeProgress(card.kind, 2, 5) else null
    StudyContent(StudyUiState(loading = false, snapshot = StudySnapshot(PreviewToday, card, practice)),
      selectedOption = selectedOption, answerChosen = selectedOption != null,
      correctAnswerRevealed = revealCorrect, feedbackFinished = feedbackFinished,
      feedbackPlaybackSucceeded = feedbackFinished, onBack = {}, onWriting = {}, onSpeak = {},
      onSubmit = { _, _ -> }, onExplain = {}, onAdvance = {}, onWordWriting = {},
      onCollection = { _, _ -> }, onSkip = { _, _ -> }, onRetry = {})
  }
}

@Preview(name = "Stage progress · stage themes", widthDp = 360, heightDp = 144)
@Preview(name = "Stage progress · compact", widthDp = 320, heightDp = 144, fontScale = 1.3f)
@Composable
private fun VocabularyStageProgressPreview(@PreviewParameter(StageThemePreviewProvider::class) theme: StageThemePreviewCase) {
  FlashcardTheme(darkTheme = theme.darkTheme, stage = theme.stage) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
      contentColor = MaterialTheme.colorScheme.onBackground) {
      Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
        VocabularyStageProgress(StageProgress(theme.stage, learned = 240, total = 600))
      }
    }
  }
}

@Preview(name = "Welcome · light", widthDp = 360, heightDp = 760)
@Preview(name = "Welcome · dark", widthDp = 360, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Welcome · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Preview(name = "Welcome · compact dark", widthDp = 320, heightDp = 640, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WelcomePreview() {
  FlashcardTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
      contentColor = MaterialTheme.colorScheme.onBackground) {
      Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 44.dp),
        contentAlignment = Alignment.Center) {
        WelcomeNameFields(name = "Alex", avatarId = "1", enabled = true,
          onNameChange = {}, onAvatar = {}, onNext = {})
      }
    }
  }
}

@Preview(name = "Question · stage themes", widthDp = 360, heightDp = 640)
@Preview(name = "Question · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable
private fun QuestionPreview(@PreviewParameter(StageThemePreviewProvider::class) theme: StageThemePreviewCase) {
  StudyPagePreview(theme, PreviewCard)
}

@Preview(name = "Wrong answer · Chinese revealed", widthDp = 360, heightDp = 640)
@Preview(name = "Wrong answer · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable
private fun WrongAnswerPreview(@PreviewParameter(StageThemePreviewProvider::class) theme: StageThemePreviewCase) =
  AnswerSequencePreview(theme, revealCorrect = false)

@Preview(name = "Wrong answer · correct revealed", widthDp = 360, heightDp = 640)
@Preview(name = "Wrong answer revealed · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable
private fun CorrectAnswerRevealedPreview(@PreviewParameter(StageThemePreviewProvider::class) theme: StageThemePreviewCase) =
  AnswerSequencePreview(theme, revealCorrect = true)

@Preview(name = "Correct answer · Chinese revealed", widthDp = 360, heightDp = 640)
@Preview(name = "Correct answer · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable
private fun CorrectAnswerFeedbackPreview(@PreviewParameter(StageThemePreviewProvider::class) theme: StageThemePreviewCase) =
  AnswerSequencePreview(theme, revealCorrect = true, selectedCorrect = true)

@Composable
private fun AnswerSequencePreview(theme: StageThemePreviewCase, revealCorrect: Boolean, selectedCorrect: Boolean = false) {
  val selected = if (selectedCorrect) "thank-you" else "morning"
  StudyPagePreview(theme, PreviewCard.copy(phase = CardPhase.FEEDBACK, selectedOptionId = selected,
    correct = selectedCorrect), selectedOption = selected, revealCorrect = revealCorrect,
    feedbackFinished = revealCorrect)
}

@Preview(name = "Explanation · stage themes", widthDp = 360, heightDp = 640)
@Preview(name = "Explanation · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Composable
private fun ExplanationPreview(@PreviewParameter(StageThemePreviewProvider::class) theme: StageThemePreviewCase) {
  StudyPagePreview(theme, PreviewCard.copy(phase = CardPhase.EXPLANATION, correct = true,
    selectedOptionId = "thank-you", isCollected = true))
}

@Preview(name = "Collections · long word", widthDp = 360, heightDp = 760)
@Preview(name = "Collections · compact large text", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable
private fun LongWordPreview(@PreviewParameter(StageThemePreviewProvider::class) theme: StageThemePreviewCase) {
  val word = PreviewWord.copy(id = "preview-lesson", hanzi = "吃一堑，长一智", pinyin = "chī yī qiàn, zhǎng yī zhì",
    meanings = listOf(Meaning("lesson", "learn from a setback", "idiom")),
    literalExplanations = listOf("After a setback, you gain wisdom."),
    examples = listOf(ExampleSentence("吃一堑，长一智。", "Chī yī qiàn, zhǎng yī zhì.",
      "Learn from your setbacks.", emptyList())))
  StudyPagePreview(theme, PreviewCard.copy(word = word, kind = StudyKind.COLLECTION,
    phase = CardPhase.EXPLANATION, isCollected = true))
}
