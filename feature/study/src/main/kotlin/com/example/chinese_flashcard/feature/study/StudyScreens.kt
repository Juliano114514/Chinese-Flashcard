package com.example.chinese_flashcard.feature.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.ui.AvatarPickerDialog
import com.example.chinese_flashcard.core.ui.PresetAvatar
import com.example.chinese_flashcard.core.ui.studyBackgroundBrush
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
  var dailyWords by rememberSaveable { mutableIntStateOf(settings.dailyWords.coerceIn(5, 100)) }
  var reviewDays by rememberSaveable { mutableStateOf(settings.reviewDays) }
  val focus = LocalFocusManager.current
  val validName = name.isNotBlank()
  val canSave = validName && reviewDays.isNotEmpty() && !saving
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
          0 -> {
            Box(Modifier.size(80.dp).clickable(enabled = !saving, role = Role.Button) {
              focus.clearFocus(); choosingAvatar = true
            }.semantics { contentDescription = "Choose avatar" }) {
              PresetAvatar(avatarId, Modifier.fillMaxSize())
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Text("Your name", style = MaterialTheme.typography.titleLarge)
              OutlinedTextField(value = name, onValueChange = { value ->
                if (value.length <= 40 && value.none { it.isISOControl() }) name = value
              }, modifier = Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = {
                  if (!saving && validName) { focus.clearFocus(); step = 1 }
                }))
            }
          }
          1 -> {
            Text("Words per day", style = MaterialTheme.typography.headlineMedium,
              textAlign = TextAlign.Center)
            Text(dailyWords.toString(), fontSize = 52.sp, lineHeight = 60.sp,
              fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Slider(value = dailyWords.toFloat(), onValueChange = {
                dailyWords = it.roundToInt().coerceIn(5, 100)
              }, valueRange = 5f..100f, steps = 94, enabled = !saving,
                colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("5", style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("100", style = MaterialTheme.typography.bodySmall,
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
fun StudyScreen(vm: StudyViewModel, onBack: () -> Unit, onWriting: (String) -> Unit, onSpeak: (String) -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val card = state.card
  var selectedOption by rememberSaveable(card?.id) { mutableStateOf<String?>(null) }
  var answerChosen by rememberSaveable(card?.id) { mutableStateOf(false) }
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
  LaunchedEffect(card?.id, card?.writingSessionId, card?.phase, state.busy) {
    val id = card?.writingSessionId
    if (!state.busy && card != null && card.phase == CardPhase.WRITING && id != null &&
      vm.claimCardWriting(card.id, id)) onWriting(id)
  }
  // Complete a saved feedback page from earlier app versions without submitting it twice.
  LaunchedEffect(card?.id, card?.phase, state.busy, state.error) {
    if (card?.phase == CardPhase.FEEDBACK && !state.busy && state.error == null) vm.explain()
  }
  Box(Modifier.fillMaxSize().background(studyBackgroundBrush())) {
  Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
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
        Column(Modifier.padding(end = 12.dp), horizontalAlignment = Alignment.End,
          verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(if (card.reviewRecall) "Recall" else "Round ${card.round} / ${card.targetRounds}",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (!card.reviewRecall) RoundProgress(card)
        }
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
              OutlinedButton(onClick = { selectedOption = null; answerChosen = true }, enabled = !state.busy,
                shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp,
                  if (answerChosen && selectedOption == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { selected = answerChosen && selectedOption == null }) { Text("I don't know") }
              Button(onClick = { vm.onAction(StudyAction.Submit(card.id, selectedOption)) }, enabled = answerChosen && !state.busy,
                shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Next") }
            }
          }
          CardPhase.INTRO, CardPhase.EXPLANATION, CardPhase.FEEDBACK -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedButton(onClick = { vm.startManualWriting(card.word.id) }, enabled = !state.busy,
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
        state.loading || (state.busy && card == null) -> LoadingNotice()
        card == null -> {
          Text("No active session", style = MaterialTheme.typography.headlineMedium)
          Text("Choose Learn or Review from Home.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                color = MaterialTheme.colorScheme.primary)
            }
          }
          Button(onClick = onBack, enabled = !state.busy, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Done") }
        }
        else -> {
          WordHeading(card.word, enabled = !state.busy, onSpeak = { onSpeak(card.word.hanzi) },
            onWrite = if (card.phase in listOf(CardPhase.INTRO, CardPhase.EXPLANATION, CardPhase.FEEDBACK))
              ({ vm.startManualWriting(card.word.id) }) else null)
          when (card.phase) {
            CardPhase.INTRO, CardPhase.EXPLANATION, CardPhase.FEEDBACK -> {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                card.word.meanings.forEach { meaning ->
                  Text(listOf(meaning.partOfSpeech, meaning.english).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge)
                }
              }
              if (card.correct != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(if (card.correct == true) "Correct" else "Incorrect · Round resets to 1",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (card.correct == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                  if (card.correct == false) {
                    val choice = card.options.firstOrNull { it.id == card.selectedOptionId }?.english ?: "I don't know"
                    Text("Your answer: $choice", style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
              }
              WordExplanation(card.word, onSpeak)
            }
            CardPhase.QUESTION -> {
              if (card.showContext && card.word.examples.isNotEmpty()) {
                val index = (card.round - 1).coerceAtLeast(0) % card.word.examples.size
                ExampleCard(card.word.examples[index], onSpeak)
              }
              Text("Choose the meaning", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
              card.options.forEachIndexed { index, option ->
                val selected = answerChosen && selectedOption == option.id
                Surface(modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }, shape = RoundedCornerShape(8.dp),
                  color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .78f),
                  border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                  onClick = { selectedOption = option.id; answerChosen = true }, enabled = !state.busy) {
                  Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(('A' + index).toString(), style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(option.english, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                  }
                }
              }
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
      state.error?.let { ErrorNotice(it, !state.busy, vm::retry) }
    }
  }
  DailyWritingInvitation(state, vm)
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
private fun RoundProgress(card: StudyCard) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics {
    contentDescription = "Round ${card.round} of ${card.targetRounds}"
  }) {
    repeat(card.targetRounds) { index ->
      Spacer(Modifier.size(width = 10.dp, height = 3.dp).background(if (index < card.round) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant))
    }
  }
}

@Composable
private fun WordHeading(word: WordEntry, enabled: Boolean, onSpeak: () -> Unit, onWrite: (() -> Unit)?) {
  var showActions by remember(word.id) { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(word.hanzi, fontSize = 48.sp, lineHeight = 58.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.combinedClickable(enabled = enabled, onClick = onSpeak, onLongClick = { showActions = true },
        onLongClickLabel = "Word actions"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(word.pinyin, fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      IconButton(onClick = onSpeak, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Listen to word", modifier = Modifier.size(20.dp))
      }
    }
  }
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
private fun WordExplanation(word: WordEntry, onSpeak: (String) -> Unit) {
  if (word.examples.isNotEmpty()) {
    StudyPanel {
      word.examples.forEachIndexed { index, example ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
        ExampleContent(example, onSpeak)
      }
    }
  }
  if (word.parts.isNotEmpty() || word.note.isNotBlank()) {
    StudyPanel {
      if (word.parts.isNotEmpty()) {
        SectionLabel("Word breakdown")
        word.parts.forEach { part ->
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(part.hanzi, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(part.pinyin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(part.gloss, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
        word.meanings.firstOrNull()?.let { Text("Together: ${it.english}", style = MaterialTheme.typography.bodyMedium) }
      }
      if (word.note.isNotBlank()) {
        if (word.parts.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
        SectionLabel("Usage note")
        Text(word.note, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
private fun ExampleCard(example: ExampleSentence, onSpeak: (String) -> Unit) {
  StudyPanel { ExampleContent(example, onSpeak) }
}

@Composable
private fun StudyPanel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .72f)),
    shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
  }
}

@Composable
private fun ExampleContent(example: ExampleSentence, onSpeak: (String) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(example.hanzi, fontSize = 18.sp, lineHeight = 27.sp)
      Text(example.pinyin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(example.english, style = MaterialTheme.typography.bodyMedium)
    }
    IconButton(onClick = { onSpeak(example.hanzi) }, modifier = Modifier.size(40.dp)) {
      Icon(Icons.Default.PlayArrow, contentDescription = "Listen to example", modifier = Modifier.size(20.dp))
    }
  }
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
      color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun LoadingNotice() {
  CircularProgressIndicator(Modifier.size(28.dp))
  Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorNotice(message: String, enabled: Boolean, onRetry: () -> Unit) {
  Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
  TextButton(onClick = onRetry, enabled = enabled) { Text("Try again") }
}
