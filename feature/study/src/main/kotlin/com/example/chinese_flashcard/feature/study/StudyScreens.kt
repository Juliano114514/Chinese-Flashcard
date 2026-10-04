package com.example.chinese_flashcard.feature.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chinese_flashcard.core.domain.CardPhase
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.StudyCard
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.WordEntry

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(
  settings: StudySettings,
  saving: Boolean,
  error: String?,
  onSave: (StudySettings) -> Unit,
) {
  var dailyText by remember(settings) { mutableStateOf(settings.dailyWords.toString()) }
  var rounds by remember(settings) { mutableIntStateOf(settings.rounds) }
  var reviewDays by remember(settings) { mutableStateOf(settings.reviewDays) }
  var showReviewDays by remember { mutableStateOf(false) }
  val dailyWords = dailyText.toIntOrNull()
  val validDaily = dailyWords != null && dailyWords in 1..100
  PageColumn {
    Spacer(Modifier.height(24.dp))
    Text("CHINESE FLASHCARD", style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary)
    Text("Chinese words,\nin context.", fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold)
    Text("Understand the word, meet it in a sentence, then recall its meaning.",
      style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    SectionLabel("Your daily plan")
    OutlinedTextField(value = dailyText, onValueChange = { value ->
      if (value.length <= 3 && value.all(Char::isDigit)) dailyText = value
    }, modifier = Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      label = { Text("New words per day") }, isError = !validDaily,
      supportingText = { Text("Choose 1–100. Reviews are added separately.") })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { dailyText = "10" }, enabled = !saving) { Text("10 words") }
      OutlinedButton(onClick = { dailyText = "20" }, enabled = !saving) { Text("20 words") }
    }
    RoundSetting(rounds, !saving) { rounds = it }
    OutlinedButton(onClick = { showReviewDays = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
      Text("Review after ${reviewDays.joinToString(" / ")} days")
    }
    Text("The first two rounds include an example. A wrong answer restarts the word from round 1.",
      style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { ErrorNotice(it, !saving && validDaily) {
      if (validDaily) onSave(settings.copy(dailyWords = dailyWords!!, rounds = rounds,
        reviewDays = reviewDays, welcomed = true))
    } }
    Button(onClick = { onSave(settings.copy(dailyWords = dailyWords!!, rounds = rounds,
      reviewDays = reviewDays, welcomed = true)) }, enabled = validDaily && !saving,
      modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Get started") }
    Text("Offline study · progress saved on this device", style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  if (showReviewDays) ReviewDaysDialog(reviewDays, onDismiss = { showReviewDays = false }) {
    reviewDays = it
    showReviewDays = false
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
  vm: StudyViewModel,
  onStart: (StudyKind) -> Unit,
  onResume: () -> Unit,
  onWriting: (String) -> Unit,
) {
  val state by vm.state.collectAsStateWithLifecycle()
  WritingNavigation(state, vm, onWriting)
  PageColumn {
    Text("TODAY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text("A few words.\nA little every day.", style = MaterialTheme.typography.headlineLarge)
    when {
      state.loading -> LoadingNotice()
      state.today != null -> {
        val today = state.today!!
        Text(today.date, style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${today.completed} / ${today.planned}", fontSize = 42.sp, fontWeight = FontWeight.SemiBold)
        Text("words completed today", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = {
          if (today.planned == 0) 0f else (today.completed.toFloat() / today.planned).coerceIn(0f, 1f)
        }, modifier = Modifier.fillMaxWidth())
        Text("Daily goal: ${today.dailyGoal} new words · ${today.newPlanned} available in today's plan",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.card?.takeIf { it.phase != CardPhase.FINISHED }?.let { card ->
          ActionTile("Resume this word", "${card.word.hanzi}  ${card.word.pinyin}", "Round ${card.round} of ${card.targetRounds}",
            !state.busy, onResume)
        }
        SectionLabel("Your plan")
        ActionTile("Learn", "New words", "${today.newCompleted} / ${today.newPlanned}",
          !state.busy && today.newCompleted < today.newPlanned) { onStart(StudyKind.NEW) }
        ActionTile("Review", "Words due today", "${today.reviewCompleted} / ${today.reviewPlanned}",
          !state.busy && today.reviewCompleted < today.reviewPlanned) { onStart(StudyKind.REVIEW) }
        if (today.carryoverPlanned > 0) ActionTile("Continue", "Unfinished words from earlier days",
          "${today.carryoverCompleted} / ${today.carryoverPlanned}",
          !state.busy && today.carryoverCompleted < today.carryoverPlanned) { onStart(StudyKind.CARRYOVER) }
        today.resumableWritingId?.let { id ->
          OutlinedButton(onClick = { onWriting(id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Resume handwriting")
          }
        }
        if (today.allComplete) {
          Text("Today's plan is complete.", style = MaterialTheme.typography.titleMedium)
          if (today.todayNewWords.isNotEmpty()) TextButton(onClick = vm::startDailyWriting, enabled = !state.busy) {
            Text("Write today's words")
          }
        }
        if (today.planned == 0) Text("No words are waiting today. Your next review will appear here when it is due.",
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("Your wordbook")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
          CountLabel(today.totalWords, "Total")
          CountLabel(today.learnedWords, "Learned")
          CountLabel(today.remainingWords, "Not started")
        }
        Text("Learning is saved after each step.", style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    state.error?.let { ErrorNotice(it, !state.busy, vm::retry) }
  }
  DailyWritingInvitation(state, vm)
}

@Composable
fun StudyScreen(vm: StudyViewModel, onBack: () -> Unit, onWriting: (String) -> Unit, onSpeak: (String) -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val card = state.card
  WritingNavigation(state, vm, onWriting)
  LaunchedEffect(card?.id, card?.writingSessionId, card?.phase, state.busy) {
    val id = card?.writingSessionId
    if (!state.busy && card != null && card.phase == CardPhase.WRITING && id != null &&
      vm.claimCardWriting(card.id, id)) onWriting(id)
  }
  PageColumn(scrollKey = "${card?.id}/${card?.round}/${card?.phase}", resetScroll = card?.phase != CardPhase.FEEDBACK) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = onBack, enabled = !state.busy) { Text("Close") }
      Text(when (card?.kind) {
        StudyKind.NEW -> "LEARN"
        StudyKind.REVIEW -> "REVIEW"
        StudyKind.CARRYOVER -> "CONTINUE"
        null -> "STUDY"
      }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    when {
      state.loading || (state.busy && card == null) -> LoadingNotice()
      card == null -> {
        Text("Ready for your next word", style = MaterialTheme.typography.headlineMedium)
        Text("Choose Learn or Review from Today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onBack, enabled = !state.busy) { Text("Back to Today") }
      }
      card.phase == CardPhase.FINISHED -> {
        Spacer(Modifier.height(32.dp))
        Text("Session complete", style = MaterialTheme.typography.headlineLarge)
        state.today?.let { today ->
          Text("${today.completed} of ${today.planned} words completed today.")
          CompletionCount("New words", today.newCompleted, today.newPlanned)
          CompletionCount("Reviews", today.reviewCompleted, today.reviewPlanned)
          CompletionCount("From earlier days", today.carryoverCompleted, today.carryoverPlanned)
          today.nextReviewDate?.let { date ->
            Text("Next review: $date", style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary)
          }
        }
        Text("Your progress has been saved.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Back to Today") }
      }
      else -> {
        RoundProgress(card)
        WordHeading(card.word) { onSpeak(card.word.hanzi) }
        when (card.phase) {
          CardPhase.INTRO, CardPhase.EXPLANATION -> {
            Text(if (card.phase == CardPhase.INTRO) "Meet this word" else "Understand this word",
              style = MaterialTheme.typography.titleLarge)
            WordExplanation(card.word, onSpeak)
            OutlinedButton(onClick = { vm.startManualWriting(card.word.id) }, enabled = !state.busy) { Text("Write this word") }
            Button(onClick = vm::advance, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
              Text(if (card.phase == CardPhase.INTRO) "Continue" else "Back to practice")
            }
          }
          CardPhase.QUESTION, CardPhase.FEEDBACK -> {
            Text(if (card.reviewRecall) "Recall the meaning" else if (card.showContext) "Learn with context" else "Recall the meaning",
              style = MaterialTheme.typography.titleMedium)
            if (card.showContext && card.word.examples.isNotEmpty()) {
              val index = (card.round - 1).coerceAtLeast(0) % card.word.examples.size
              ExampleCard(card.word.examples[index], onSpeak)
            }
            if (card.phase == CardPhase.QUESTION) Text("Choose the meaning", style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
            card.options.forEachIndexed { index, option ->
              val isAnswer = card.word.meanings.any { it.id == option.id }
              val feedback = card.phase == CardPhase.FEEDBACK
              val selected = card.selectedOptionId == option.id
              val color = when {
                feedback && isAnswer -> MaterialTheme.colorScheme.primaryContainer
                feedback && selected && card.correct == false -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
              }
              Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                color = color, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                onClick = { vm.submit(option.id) }, enabled = !state.busy && !feedback) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                  verticalAlignment = Alignment.CenterVertically) {
                  Text(('A' + index).toString(), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(option.english, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                  if (feedback && isAnswer) Text("Correct", style = MaterialTheme.typography.labelSmall)
                  else if (feedback && selected) Text("Your choice", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
            if (card.phase == CardPhase.QUESTION) {
              TextButton(onClick = { vm.submit(null) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("I don't know") }
            } else {
              Text(if (card.correct == true) "Correct" else "Let's learn it again", style = MaterialTheme.typography.titleLarge,
                color = if (card.correct == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
              Text(if (card.correct == true) "Your answer is saved. Continue to the next step."
                else "This word restarts from round 1.", color = MaterialTheme.colorScheme.onSurfaceVariant)
              TextButton(onClick = vm::explain, enabled = !state.busy) { Text("Explanation") }
              Button(onClick = vm::advance, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
          }
          CardPhase.WRITING -> {
            Text("Handwriting practice", style = MaterialTheme.typography.titleLarge)
            Text("Return here after tracing to continue this word.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            card.writingSessionId?.let { id ->
              Button(onClick = { onWriting(id) }, enabled = !state.busy) { Text("Open handwriting") }
            }
          }
          CardPhase.FINISHED -> Unit
        }
      }
    }
    if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
    state.error?.let { ErrorNotice(it, !state.busy, vm::retry) }
  }
  DailyWritingInvitation(state, vm)
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
    text = { Text("Your daily plan is complete. Take a short handwriting practice with today's new words.") },
    confirmButton = { TextButton(onClick = vm::startDailyWriting, enabled = !state.busy) { Text("Write now") } },
    dismissButton = { TextButton(onClick = vm::dismissInvitation, enabled = !state.busy) { Text("Not now") } })
}

@Composable
private fun RoundProgress(card: StudyCard) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics {
    contentDescription = "Round ${card.round} of ${card.targetRounds}"
  }) {
    Text("Round ${card.round} of ${card.targetRounds}", style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      repeat(card.targetRounds) { index ->
        Spacer(Modifier.size(8.dp).background(if (index < card.round) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.outlineVariant, CircleShape))
      }
    }
  }
}

@Composable
private fun WordHeading(word: WordEntry, onSpeak: () -> Unit) {
  Text(word.hanzi, fontSize = 56.sp, lineHeight = 68.sp, fontWeight = FontWeight.Medium)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(word.pinyin, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = onSpeak) { Text("Listen") }
  }
}

@Composable
private fun WordExplanation(word: WordEntry, onSpeak: (String) -> Unit) {
  SectionLabel("Meaning")
  word.meanings.forEach { meaning ->
    Text(listOf(meaning.partOfSpeech, meaning.english).filter(String::isNotBlank).joinToString(" · "),
      style = MaterialTheme.typography.titleMedium)
  }
  if (word.examples.isNotEmpty()) {
    SectionLabel("In context")
    word.examples.forEach { ExampleCard(it, onSpeak) }
  }
  if (word.parts.isNotEmpty()) {
    SectionLabel("Word breakdown")
    word.parts.forEach { part ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(part.hanzi, style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f)) {
          Text(part.pinyin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(part.gloss, style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
    word.meanings.firstOrNull()?.let { Text("Together: ${it.english}", style = MaterialTheme.typography.bodyLarge) }
    Text("A memory cue for this word.", style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  if (word.note.isNotBlank()) {
    SectionLabel("Usage note")
    Text(word.note, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun ExampleCard(example: ExampleSentence, onSpeak: (String) -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(example.hanzi, style = MaterialTheme.typography.titleLarge)
      Text(example.pinyin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(example.english, style = MaterialTheme.typography.bodyLarge)
      TextButton(onClick = { onSpeak(example.hanzi) }) { Text("Listen to the sentence") }
    }
  }
}

@Composable
private fun PageColumn(scrollKey: Any? = null, resetScroll: Boolean = true,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  val scroll = rememberScrollState()
  LaunchedEffect(scrollKey) { if (resetScroll) scroll.scrollTo(0) }
  Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(scroll)
    .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
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
  Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.headlineSmall,
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
  Text("Loading your saved progress…", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorNotice(message: String, enabled: Boolean, onRetry: () -> Unit) {
  Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
  TextButton(onClick = onRetry, enabled = enabled) { Text("Try again") }
}

@Composable
private fun RoundSetting(rounds: Int, enabled: Boolean, onChange: (Int) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Correct rounds", style = MaterialTheme.typography.titleMedium)
      Text("Consecutive correct answers: $rounds", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = { onChange(rounds - 1) }, enabled = enabled && rounds > 2) { Text("−") }
    Text(rounds.toString(), style = MaterialTheme.typography.titleLarge)
    TextButton(onClick = { onChange(rounds + 1) }, enabled = enabled && rounds < 8) { Text("+") }
  }
}

@Composable
private fun ReviewDaysDialog(current: List<Int>, onDismiss: () -> Unit, onConfirm: (List<Int>) -> Unit) {
  var selected by remember(current) { mutableStateOf(current.toSet()) }
  AlertDialog(onDismissRequest = onDismiss, title = { Text("Review days") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState())) {
      Text("Review after learning a word. Choose at least one day.")
      listOf(1, 3, 7, 14, 30).forEach { day ->
        Row(Modifier.fillMaxWidth().toggleable(value = day in selected, role = Role.Checkbox,
          onValueChange = { checked -> selected = if (checked) selected + day else selected - day }),
          verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = day in selected, onCheckedChange = null)
          Text("Day $day")
        }
      }
    }
  }, confirmButton = {
    TextButton(onClick = { onConfirm(selected.sorted()) }, enabled = selected.isNotEmpty()) { Text("Done") }
  }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
