package com.example.chinese_flashcard.feature.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
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
import android.content.res.Configuration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.AnswerOption
import com.example.chinese_flashcard.core.domain.DailyWordChoices
import com.example.chinese_flashcard.core.domain.ExampleChunk
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.Meaning
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.ui.AvatarPickerDialog
import com.example.chinese_flashcard.core.ui.EditableAvatar
import com.example.chinese_flashcard.core.ui.studyBackgroundBrush
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.WordHeading
import com.example.chinese_flashcard.core.ui.WordMeanings
import com.example.chinese_flashcard.core.ui.WordExplanation
import com.example.chinese_flashcard.core.ui.WordExamplePanel
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
  Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 24.dp, vertical = 20.dp),
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
                    if (checked) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .border(1.dp, if (checked) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
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
  PageColumn {
    Text("Home", style = MaterialTheme.typography.headlineLarge)
    when {
      state.loading -> LoadingNotice()
      state.today != null -> {
        val today = state.today!!
        Text(today.date, style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("${today.completed} / ${today.planned}", fontSize = 48.sp, lineHeight = 56.sp,
            fontWeight = FontWeight.SemiBold)
          Text("Words today", color = MaterialTheme.colorScheme.onSurfaceVariant)
          LinearProgressIndicator(progress = {
            if (today.planned == 0) 0f else (today.completed.toFloat() / today.planned).coerceIn(0f, 1f)
          }, modifier = Modifier.fillMaxWidth())
          Text("Daily goal · ${today.dailyGoal} new words",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TotalProgress(learned = today.learnedWords, total = today.totalWords)
        state.card?.takeIf { it.phase != CardPhase.FINISHED }?.let { card ->
          ActionTile("Resume", "${card.word.hanzi}  ${card.word.pinyin}", "${card.round} / ${card.targetRounds}",
            !state.busy, onResume)
        }
        SectionLabel("Study")
        ActionTile(if (today.showLearnMore) "Learn more" else "Learn",
          if (today.showLearnMore && today.availableNewWords == 0) "No new words"
          else if (today.showLearnMore) "Next ${minOf(5, today.availableNewWords)} words"
          else "New words", "${today.newCompleted} / ${today.newPlanned}",
          !state.busy && (!today.showLearnMore || today.learnMoreBlocker != null || today.availableNewWords > 0)) {
          if (!today.showLearnMore) onStart(StudyKind.NEW)
          else if (today.learnMoreBlocker != null) showLearnMoreBlocker = true
          else if (today.availableNewWords > 0) onLearnMore()
        }
        ActionTile("Review", "Due words", "${today.reviewCompleted} / ${today.reviewPlanned}",
          !state.busy && today.reviewCompleted < today.reviewPlanned) { onStart(StudyKind.REVIEW) }
        if (today.carryoverPlanned > 0) ActionTile("Continue", "Unfinished words",
          "${today.carryoverCompleted} / ${today.carryoverPlanned}",
          !state.busy && today.carryoverCompleted < today.carryoverPlanned) { onStart(StudyKind.CARRYOVER) }
        today.resumableWritingId?.let { id ->
          OutlinedButton(onClick = { onWriting(id) }, enabled = !state.busy, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            Text("Resume writing")
          }
        }
        if (today.allComplete) {
          Text("Plan complete", style = MaterialTheme.typography.titleMedium)
          if (today.todayNewWords.isNotEmpty()) TextButton(onClick = vm::startDailyWriting, enabled = !state.busy) {
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
    state.error?.let { ErrorNotice(it, !state.busy, vm::retry) }
  }
  state.today?.learnMoreBlocker?.takeIf { showLearnMoreBlocker }?.let { blocker ->
    AlertDialog(onDismissRequest = { if (!state.busy) showLearnMoreBlocker = false },
      shape = RoundedCornerShape(12.dp), title = { Text("Finish today's plan") }, text = {
        Text(when (blocker) {
          StudyKind.REVIEW -> "Review the due words before learning more."
          StudyKind.CARRYOVER -> "Finish the unfinished words before learning more."
          StudyKind.NEW -> "Finish the current word before learning more."
        })
      }, confirmButton = {
        TextButton(onClick = { showLearnMoreBlocker = false; onStart(blocker) }, enabled = !state.busy) {
          Text(when (blocker) {
            StudyKind.REVIEW -> "Go to review"
            StudyKind.CARRYOVER -> "Go to continue"
            StudyKind.NEW -> "Continue learning"
          })
        }
      }, dismissButton = {
        TextButton(onClick = { showLearnMoreBlocker = false }, enabled = !state.busy) { Text("Back") }
      })
  }
  if (!showLearnMoreBlocker) DailyWritingInvitation(state, vm)
}

@Composable
fun StudyScreen(vm: StudyViewModel, onBack: () -> Unit, onWriting: (String) -> Unit, onSpeak: (String) -> Unit,
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
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val speechPage = card?.takeIf { it.phase in listOf(CardPhase.INTRO, CardPhase.QUESTION, CardPhase.EXPLANATION) }
    ?.let { "${it.id}/${it.phase}" }
  LaunchedEffect(speechPage, state.busy, state.loading, lifecycle) {
    if (speechPage != null && !state.busy && !state.loading) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        if (spokenPage != speechPage) {
          spokenPage = speechPage
          onSpeak(card.word.hanzi)
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
          if (!correctAnswerRevealed) {
            if (card.correct == false) {
              withFrameNanos { }; withFrameNanos { }
              card.options.firstOrNull { it.id == card.selectedOptionId }?.hanzi
                ?.takeIf(String::isNotBlank)?.let { onSpeakAndWait(it) }
              currentCoroutineContext().ensureActive()
            }
            correctAnswerRevealed = true
          }
          withFrameNanos { }; withFrameNanos { }
          val active = vm.state.value.card
          if (active == null || active.id != card.id || active.phase != CardPhase.FEEDBACK) return@repeatOnLifecycle
          val played = onSpeakAndWait(card.word.hanzi)
          currentCoroutineContext().ensureActive()
          val current = vm.state.value.card
          if (current != null && current.id == card.id && current.phase == CardPhase.FEEDBACK) {
            feedbackPlaybackSucceeded = played
            feedbackFinished = true
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
  Box(Modifier.fillMaxSize().background(studyBackgroundBrush())) {
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
        null -> "Study"
      }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      if (card != null && card.phase != CardPhase.FINISHED) {
        state.today?.let { today ->
          Text("Progress ${today.completed} / ${today.planned}", modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
        }
      }
    }
    if (card != null && card.phase != CardPhase.FINISHED) state.today?.let { today ->
      DailyProgress(today.completed, today.planned, Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp))
    }
    }
  }, bottomBar = {
    if (card != null && card.phase != CardPhase.FINISHED) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        when (card.phase) {
          CardPhase.QUESTION -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedButton(onClick = {
                selectedOption = null; answerChosen = true
                vm.onAction(StudyAction.Submit(card.id, null))
              }, enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
                shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp,
                  if (answerChosen && selectedOption == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { selected = answerChosen && selectedOption == null }) { Text("I don't know") }
              Button(onClick = { vm.onAction(StudyAction.Submit(card.id, selectedOption)) }, enabled = answerChosen && !state.busy,
                shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Next") }
            }
          }
          CardPhase.FEEDBACK -> {
            if (card.correct == true && feedbackFinished && !feedbackPlaybackSucceeded) {
              Text("Audio unavailable. Tap Next.", style = MaterialTheme.typography.bodySmall,
                color = FlashcardStyle.colors.gradientSecondaryInk)
            }
            Button(onClick = { vm.onAction(StudyAction.Explain(card.id)) }, enabled = !state.busy && feedbackFinished,
              shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Next") }
          }
          CardPhase.INTRO, CardPhase.EXPLANATION -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedButton(onClick = { vm.startManualWriting(card.word.id) }, enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
                border = BorderStroke(1.dp, FlashcardStyle.colors.gradientAction),
                shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Write") }
              Button(onClick = vm::advance, enabled = !state.busy && card.phase != CardPhase.FEEDBACK, shape = RoundedCornerShape(8.dp),
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
          Text("Session complete", style = MaterialTheme.typography.headlineLarge)
          state.today?.let { today ->
            Text("${today.completed} / ${today.planned} words today")
            CompletionCount("New words", today.newCompleted, today.newPlanned)
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
              ({ vm.startManualWriting(card.word.id) }) else null, card = card)
          when (card.phase) {
            CardPhase.INTRO, CardPhase.EXPLANATION -> {
              WordMeanings(card.word)
              if (card.correct != null) {
                AnswerFeedback(card.correct == true,
                  card.options.firstOrNull { it.id == card.selectedOptionId }?.english ?: "I don't know")
              }
              WordExplanation(card.word, onSpeak)
            }
            CardPhase.QUESTION, CardPhase.FEEDBACK -> {
              val feedback = card.phase == CardPhase.FEEDBACK
              if (card.showContext && card.word.examples.isNotEmpty()) {
                val index = (card.round - 1).coerceAtLeast(0) % card.word.examples.size
                WordExamplePanel(card.word.examples[index], onSpeak, enabled = !feedback && !state.busy)
              }
              Text("Choose the meaning", style = MaterialTheme.typography.bodyMedium,
                color = FlashcardStyle.colors.gradientSecondaryInk)
              card.options.forEachIndexed { index, option ->
                val selected = if (feedback) card.selectedOptionId == option.id else answerChosen && selectedOption == option.id
                val correct = card.word.meanings.any { it.id == option.id }
                val revealed = feedback && (selected || (card.correct == false && correct && correctAnswerRevealed))
                MeaningOption(index, option.english, selected, enabled = !state.busy && !feedback,
                  onClick = {
                    selectedOption = option.id; answerChosen = true
                    vm.onAction(StudyAction.Submit(card.id, option.id))
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
      state.error?.let { ErrorNotice(it, !state.busy, vm::retry, studyPage = true) }
    }
  }
  DailyWritingInvitation(state, vm)
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
    shape = RoundedCornerShape(12.dp),
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
      Spacer(Modifier.size(width = 10.dp, height = 3.dp).background(if (index < card.round) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant))
    }
  }
}

@Composable
private fun StudyWordHeading(word: WordEntry, enabled: Boolean, onSpeak: () -> Unit, onWrite: (() -> Unit)?,
  card: StudyCard? = null) {
  var showActions by remember(word.id) { mutableStateOf(false) }
  WordHeading(word, enabled, onSpeak, onLongClick = { showActions = true }, trailing = {
    if (card != null && !card.reviewRecall) RoundProgress(card)
  })
  if (showActions) AlertDialog(onDismissRequest = { showActions = false }, shape = RoundedCornerShape(12.dp),
    title = { Text(word.hanzi) }, text = {
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
          }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
  Column(modifier.fillMaxSize().then(if (studyPage) Modifier else Modifier.background(MaterialTheme.colorScheme.background))
    .verticalScroll(scroll).padding(horizontal = 24.dp, vertical = if (studyPage) 12.dp else 20.dp),
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
private fun ActionTile(title: String, subtitle: String, progress: String, enabled: Boolean, onClick: () -> Unit) {
  Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge,
          color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(progress, style = MaterialTheme.typography.titleMedium)
      }
      Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun TotalProgress(learned: Int, total: Int) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Total progress", style = MaterialTheme.typography.titleSmall)
    LinearProgressIndicator(progress = {
      if (total <= 0) 0f else (learned.toFloat() / total).coerceIn(0f, 1f)
    }, modifier = Modifier.fillMaxWidth())
    Text("$learned learned / $total in total", style = MaterialTheme.typography.bodySmall,
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

private val PreviewCard = StudyCard("preview", PreviewWord, StudyKind.NEW, CardPhase.QUESTION, 3, 4, false,
  listOf(AnswerOption("thank-you", "thank you", "谢谢", "xièxie"),
    AnswerOption("morning", "good morning", "早上好", "zǎoshang hǎo"),
    AnswerOption("welcome", "you're welcome", "不客气", "bú kèqi"),
    AnswerOption("tomorrow", "see you tomorrow", "明天见", "míngtiān jiàn")))

@Preview(name = "Total progress · light", widthDp = 360, heightDp = 144)
@Preview(name = "Total progress · dark", widthDp = 360, heightDp = 144, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Total progress · compact", widthDp = 320, heightDp = 144, fontScale = 1.3f)
@Preview(name = "Total progress · compact dark", widthDp = 320, heightDp = 144, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TotalProgressPreview() {
  FlashcardTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
      contentColor = MaterialTheme.colorScheme.onBackground) {
      Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        TotalProgress(learned = 240, total = 6648)
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
      Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 44.dp),
        contentAlignment = Alignment.Center) {
        WelcomeNameFields(name = "Alex", avatarId = "1", enabled = true,
          onNameChange = {}, onAvatar = {}, onNext = {})
      }
    }
  }
}

@Preview(name = "Question · light", widthDp = 360, heightDp = 640)
@Preview(name = "Question · dark", widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Question · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Preview(name = "Question · compact dark", widthDp = 320, heightDp = 640, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QuestionPreview() {
  FlashcardTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize().background(studyBackgroundBrush())) {
        PageColumn(studyPage = true) {
          Text("Progress 6 / 10", modifier = Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
          DailyProgress(6, 10)
          StudyWordHeading(PreviewWord, enabled = true, onSpeak = {}, onWrite = null, card = PreviewCard)
          WordExamplePanel(PreviewWord.examples.first(), onSpeak = {})
          Text("Choose the meaning", color = FlashcardStyle.colors.gradientSecondaryInk)
          listOf("thank you", "good morning", "you're welcome", "see you tomorrow").forEachIndexed { index, meaning ->
            MeaningOption(index, meaning, selected = index == 0, enabled = true, onClick = {})
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction)) { Text("I don't know") }
            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Next") }
          }
        }
      }
    }
  }
}

@Preview(name = "Wrong answer · Chinese revealed", widthDp = 360, heightDp = 640)
@Composable
private fun WrongAnswerPreview() = AnswerSequencePreview(revealCorrect = false)

@Preview(name = "Wrong answer · correct revealed", widthDp = 360, heightDp = 640)
@Preview(name = "Wrong answer · compact dark", widthDp = 320, heightDp = 640, fontScale = 1.3f,
  uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CorrectAnswerRevealedPreview() = AnswerSequencePreview(revealCorrect = true)

@Preview(name = "Correct answer · Chinese revealed", widthDp = 360, heightDp = 640)
@Preview(name = "Correct answer · compact dark", widthDp = 320, heightDp = 640, fontScale = 1.3f,
  uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CorrectAnswerFeedbackPreview() = AnswerSequencePreview(revealCorrect = true, selectedCorrect = true)

@Composable
private fun AnswerSequencePreview(revealCorrect: Boolean, selectedCorrect: Boolean = false) {
  FlashcardTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize().background(studyBackgroundBrush())) {
        PageColumn(studyPage = true) {
          Text("Progress 6 / 10", modifier = Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelLarge, color = FlashcardStyle.colors.gradientSecondaryInk)
          DailyProgress(6, 10)
          StudyWordHeading(PreviewWord, enabled = false, onSpeak = {}, onWrite = null, card = PreviewCard)
          Text("Choose the meaning", color = FlashcardStyle.colors.gradientSecondaryInk)
          PreviewCard.options.forEachIndexed { index, option ->
            val wrong = index == 1 && !selectedCorrect
            val correct = index == 0 && (selectedCorrect || revealCorrect)
            MeaningOption(index, option.english, selected = if (selectedCorrect) correct else wrong, enabled = false, onClick = {},
              result = if (wrong) false else if (correct) true else null,
              hanzi = if (wrong || correct) option.hanzi else "",
              pinyin = if (wrong || correct) option.pinyin else "", dimmed = !wrong && !correct)
          }
          AnswerFeedback(selectedCorrect, if (selectedCorrect) "thank you" else "good morning")
          Button(onClick = {}, enabled = revealCorrect, modifier = Modifier.fillMaxWidth()) { Text("Next") }
        }
      }
    }
  }
}

@Preview(name = "Explanation · light", widthDp = 360, heightDp = 640)
@Preview(name = "Explanation · dark", widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Explanation · compact", widthDp = 320, heightDp = 640, fontScale = 1.3f)
@Preview(name = "Explanation · compact dark", widthDp = 320, heightDp = 640, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ExplanationPreview() {
  FlashcardTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize().background(studyBackgroundBrush())) {
        PageColumn(studyPage = true) {
          StudyWordHeading(PreviewWord, enabled = true, onSpeak = {}, onWrite = {})
          Text("expression · thank you")
          AnswerFeedback(correct = true, selectedMeaning = "thank you")
          WordExplanation(PreviewWord, onSpeak = {})
          AnswerFeedback(correct = false, selectedMeaning = "good morning")
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
              border = BorderStroke(1.dp, FlashcardStyle.colors.gradientAction)) { Text("Write") }
            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Next word") }
          }
        }
      }
    }
  }
}
